# Tier 2 Design — Temporal Models and Event-Level Fusion

Status: future design proposal — not implemented in the shipped 159-test baseline.
Target modules: `modules/temporal.py` (new), `modules/motion_detector.py`
(extend), `modules/fusion.py` (extend), `modules/pipeline.py` (wire),
`config/default.yaml` (extend), `tests/test_temporal.py` (new), `training/`
(new, not shipped at runtime)

Depends on: Tier 1 (calibration + cost-sensitive thresholds) for the final
decision layer.

## 1. Problem

Today each detector scores a *single instant* and fusion combines those
instants:

- `MotionDetector` keeps a `deque(maxlen=16)` but only uses it for a freefall
  streak; the decision is still a per-sample `max()` of hand-tuned terms.
- `FusionStrategy` receives one `ModalityScore` per modality per `process()`
  call. A fall — freefall, impact, then stillness — never appears as one
  event.

Two consequences:

1. **Impact signatures are short and easy to miss** when judged
   frame-by-frame;
2. **The system cannot represent "something is happening over the next
   2 seconds."**

## 2. Goals

- Add a **temporal per-modality model** (gated 1-D CNN) that replaces
  hand-tuned thresholds with a learned classifier over a short window.
- Add **event-level fusion** that consumes a window of per-modality scores and
  emits one decision per event.
- Stay deployable on-device: no attention, no cloud, weights loadable as
  numpy/ONNX.
- Keep the heuristic path as the cold-start / fallback, exactly as today.
- Provide an offline training/eval pipeline (not part of the runtime package).

## 3. Why gated CNN, not attention

Attention is quadratic in sequence length and distributes weight across all
timesteps, which blurs the brief impact signature inside a short window. A
dual-stream 1-D CNN with sigmoid gating suppresses background activations and
amplifies fall-discriminative features; it matches Transformer accuracy at a
fraction of the cost and has been validated on commodity smartwatches
(arXiv:2605.20275: F1 97%, zero missed falls, Pixel Watch 3).

## 4. Architecture

```text
 IMU window (T×3) ──► GatedCNN1D ──► (motion_score, motion_conf)
 Audio window    ──► GatedCNN1D ──► (audio_score,  audio_conf)
 Vision window   ──► GatedCNN1D ──► (vision_score, vision_conf)
                          │
                          ▼
                   EventAggregator
                          │
                          ▼
                     EventFusion   (+ Tier 1 calibrator)
                          │
        ┌─────────────────┴─────────────────┐
        ▼                                   ▼
  RollingBuffer                      EMERGENCY?
```

## 5. Concrete API

### 5.1 `modules/temporal.py`

```python
from __future__ import annotations

from dataclasses import dataclass, field
from typing import Deque, Dict, List, Optional
from collections import deque

import numpy as np


@dataclass(frozen=True)
class ModalityFrame:
    """One detector output, time-stamped for windowing."""
    timestamp: float
    score: float
    confidence: float
    mode: str = "model"          # "model" | "tflite" | "heuristic"


class EventAggregator:
    """Per-modality rolling windows that align modalities by time.

    Not a model: it only stores and slices frames. Fusion reads from it.
    """

    def __init__(self, window_sec: float = 3.0, max_frames: int = 256) -> None:
        if window_sec <= 0:
            raise ValueError("window_sec must be positive")
        self.window_sec = float(window_sec)
        self._max = int(max_frames)
        self._buckets: Dict[str, Deque[ModalityFrame]] = {
            "AUDIO": deque(maxlen=self._max),
            "MOTION": deque(maxlen=self._max),
            "VISION": deque(maxlen=self._max),
        }

    def push(self, modality: str, frame: ModalityFrame) -> None:
        key = modality.upper()
        if key not in self._buckets:
            raise ValueError(f"unknown modality {modality!r}")
        bucket = self._buckets[key]
        bucket.append(frame)
        newest = bucket[-1].timestamp
        while bucket and bucket[0].timestamp < newest - self.window_sec:
            bucket.popleft()

    def window(self, modality: str) -> List[ModalityFrame]:
        return list(self._buckets[modality.upper()])

    def event_features(self, now: float) -> Dict[str, float]:
        """Compact event shape: peak, rise time, sustained stillness."""
        feats: Dict[str, float] = {}
        for name, bucket in self._buckets.items():
            if not bucket:
                feats[f"{name.lower()}_peak"] = 0.0
                feats[f"{name.lower()}_frames"] = 0.0
                continue
            scores = [f.score for f in bucket]
            feats[f"{name.lower()}_peak"] = float(max(scores))
            feats[f"{name.lower()}_mean"] = float(np.mean(scores))
            feats[f"{name.lower()}_frames"] = float(len(scores))
        # stillness = motion score fell back to ~0 after a peak
        motion = [f.score for f in self._buckets["MOTION"]]
        if motion:
            feats["motion_still_after_peak"] = float(
                motion[-1] < 0.2 and max(motion) > 0.6
            )
        else:
            feats["motion_still_after_peak"] = 0.0
        return feats


class GatedCNN1D:
    """Dual-stream gated 1-D CNN for a single modality window.

    Weights: a `.npz` of numpy arrays (pure-numpy inference, no framework) or
    `.onnx` via onnxruntime. If neither loads, `mode == "heuristic"` and the
    caller keeps using the existing detector heuristic.

    Input shape: (T, C) with C channels (3 for IMU, 1 for audio envelope,
    3 for vision colour/flow features).
    """

    def __init__(self, weights_path: Optional[str] = None,
                 config: Optional[dict] = None) -> None:
        self._config = dict(config or {})
        self._weights: Optional[Dict[str, np.ndarray]] = None
        self._session = None
        self.mode = "heuristic"
        if weights_path:
            self._load(weights_path)

    def _load(self, path: str) -> None:
        try:
            if path.endswith(".npz"):
                with np.load(path) as data:
                    self._weights = {k: data[k] for k in data.files}
                self.mode = "model"
            elif path.endswith(".onnx"):
                import onnxruntime as ort  # type: ignore
                self._session = ort.InferenceSession(path)
                self.mode = "model"
        except Exception:          # silent fallback is contractual
            self._weights = None
            self._session = None
            self.mode = "heuristic"

    def predict(self, window: np.ndarray) -> tuple[float, float]:
        """Return (score, confidence) in [0, 1], or (0, 0) if unusable."""
        arr = np.asarray(window, dtype=np.float32)
        if arr.ndim != 2 or arr.shape[0] < 2 or not np.isfinite(arr).all():
            return 0.0, 0.0
        if self.mode == "heuristic":
            return 0.0, 0.0
        try:
            if self._session is not None:
                return self._infer_onnx(arr)
            return self._infer_numpy(arr)
        except Exception:
            return 0.0, 0.0

    def _infer_numpy(self, arr: np.ndarray) -> tuple[float, float]:
        w = self._weights
        assert w is not None
        # Stream A: 2 conv blocks + gap. Stream B: same, gated by sigmoid(A).
        a = self._conv_block(arr, w["a1"], w["b1"], 3)
        b = self._conv_block(arr, w["a2"], w["b2"], 3)
        gate = 1.0 / (1.0 + np.exp(-a))
        fused = np.mean(b * gate) + np.mean(b * (1.0 - gate))
        head = w["head"].reshape(-1)[0]
        bias = w["bias"].reshape(-1)[0]
        logit = float(fused * head + bias)
        score = 1.0 / (1.0 + np.exp(-logit))
        return float(min(max(score, 0.0), 1.0)), 0.9

    @staticmethod
    def _conv_block(x: np.ndarray, weight: np.ndarray, bias: np.ndarray,
                    k: int) -> np.ndarray:
        # 'valid' 1-D convolution over time, ReLU, global average pool.
        out = np.zeros((x.shape[0] - k + 1, weight.shape[-1]), dtype=np.float32)
        for t in range(out.shape[0]):
            out[t] = (x[t:t + k] @ weight.reshape(k * x.shape[1], -1)) + bias
        relu = np.maximum(out, 0.0)
        return relu.mean(axis=0)

    def _infer_onnx(self, arr: np.ndarray) -> tuple[float, float]:
        name = self._session.get_inputs()[0].name
        out = self._session.run(None, {name: arr[None]})[0]
        score = float(np.asarray(out).ravel()[0])
        return min(max(score, 0.0), 1.0), 0.9
```

### 5.2 Event fusion (extends `modules/fusion.py`)

```python
class EventFusionStrategy:
    """Fusion over a time window instead of a single instant.

    Same decide() contract, but consumes EventAggregator features to boost
    decisions where a full event shape is present (freefall -> impact ->
    stillness) and to suppress isolated single-frame spikes.
    """

    def __init__(self, base: "FusionStrategy", aggregator: EventAggregator,
                 config: dict) -> None:
        self._base = base
        self._agg = aggregator
        self._config = config

    def decide(self, modality_scores, now: float) -> ModalityScore:
        base = self._base.decide(modality_scores, self._config)
        feats = self._agg.event_features(now)
        bonus = 0.0
        if feats.get("motion_still_after_peak", 0.0) >= 1.0:
            bonus = float(self._config.get("fusion", {})
                          .get("event", {}).get("stillness_bonus", 0.1))
        if bonus:
            base = _rescore(base, min(1.0, base.score + bonus))
        return base
```

### 5.3 `pipeline.py` wiring

`EmergencyPipeline.process` keeps its signature but also pushes each detector
output into the aggregator:

```python
self.aggregator.push(AUDIO, ModalityFrame(score.timestamp, score.score,
                                          score.confidence, _mode_of(score)))
```

A new `process_event(now: float)` path runs `EventFusionStrategy.decide` and
then the Tier 1 `CostSensitivePolicy` / `ConformalThreshold`.

## 6. Config additions

```yaml
detectors:
  temporal:
    enabled: false
    window_sec: 3.0
    motion_weights: "modules/models/motion_gatedcnn.npz"
    audio_weights:  "modules/models/audio_gatedcnn.npz"
    vision_weights: "modules/models/vision_gatedcnn.onnx"
    min_frames: 5
fusion:
  event:
    enabled: false
    stillness_bonus: 0.10
    spike_suppression: 0.15
```

## 7. Offline training pipeline (`training/`, not shipped)

Per arXiv:2610.18260 (FALLNEST) and arXiv:2608.13197 (real-world scarcity):

1. **Ingest** FallAllD, SisFall, KFall, MobiFall, and real-world FARSEEING.
2. **Condense** imbalanced IMU data to tiny class-balanced synthetic windows
   (dual-domain condensation; 32–146 windows suffice for a 102K-param TinyCNN).
3. **Train** each `GatedCNN1D` with subject-wise splits.
4. **Validate cross-dataset** (train FallAllD → test FARSEEING) and report
   PR-AUC, macro-F1, recall at fixed false-alarm rate — not raw accuracy.
5. **Export** `.npz` (numpy) and `.onnx` (runtime) artifacts.
6. **Freeze** the calibration quantiles from Tier 1 on the validation split.

Train a *tiny* model deliberately: the point is latency and data efficiency,
not squeezing another point of lab accuracy.

## 8. Test plan (`tests/test_temporal.py`)

`EventAggregator`:

- `test_push_and_window_ordering`
- `test_window_expiry_by_timestamp`
- `test_unknown_modality_rejected`
- `test_event_features_shape_and_ranges`
- `test_stillness_after_peak_detected`

`GatedCNN1D`:

- `test_missing_weights_falls_back_to_heuristic`
- `test_corrupt_weights_fall_back_to_heuristic`
- `test_deterministic_numpy_inference`
- `test_malformed_window_returns_zero`
- `test_output_in_unit_range`

`EventFusionStrategy`:

- `test_event_bonus_only_with_stillness_pattern`
- `test_isolated_spike_suppressed`
- `test_single_instant_still_matches_base_fusion`

Backward compatibility:

- `test_temporal_disabled_is_byte_identical` (event fusion off → base path)
- `test_pipeline_without_aggregator_unchanged`

Training (fast smoke tests on synthetic data):

- `test_condensation_produces_balanced_small_set`
- `test_export_npz_roundtrip`

## 9. Rollout

1. Land `temporal.py` + tests with `enabled: false`.
2. Train and evaluate offline; publish metrics in `docs/ASSUMPTIONS.md`.
3. Enable event fusion for one modality at a time (motion first — highest
   value and easiest to validate).
4. A/B against the heuristic on real normal streams; watch the Tier 1
   `coverage_estimate()`.
5. Only then enable audio/vision temporal models.

## 10. Risks and mitigations

- **Lab accuracy does not transfer to real falls.** Cross-dataset eval
  (`arXiv:2608.13197`); keep the heuristic as a floor.
- **Numpy inference is slower than expected.** Keep windows short (≤ 3 s),
  CNNs small, profile per modality; use the ONNX path if needed.
- **Temporal model masks freefall/impact hard rules.** Same safety-floor
  pattern as `MotionDetector`: `max(model, impact, freefall)`.
- **Training data licensing / privacy.** Use public datasets only; never ship
  user data; condensation happens offline.
- **Aggregator memory growth.** Bounded `deque(maxlen)` per modality.

## 11. References

- Alamgeer et al. *You Don't Need Attention: Gated Convolutional Modeling for
  Watch-Based Fall Detection.* arXiv:2605.20275.
- Niyas P, Jain, Tiwari. *FALLNEST: Dual-Domain Dataset Condensation of
  Imbalanced IMU Data for Wearable Fall Detection.* arXiv:2610.18260.
- Aderinola et al. *Beyond Simulated Benchmarks: Evaluating Motion
  Representations for Fall Detection Under Real-World Data Scarcity.*
  arXiv:2608.13197.
- Zhou, Wang. *An Edge-Cloud Collaborative Architecture for Proactive Elderly
  Care.* arXiv:2604.14154.
