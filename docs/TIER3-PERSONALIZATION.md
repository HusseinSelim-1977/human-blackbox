# Tier 3 Design — On-Device Personalization and Privacy-Preserving Learning

Status: design proposal (not yet implemented)
Target modules: `modules/adaptation.py` (new), `modules/replay.py` (new),
`federation/client.py` (new, opt-in, not imported by runtime),
`modules/pipeline.py` (hook), `modules/user_consent.py` (extend scopes),
`config/default.yaml` (extend), `tests/test_adaptation.py` (new).

Depends on: Tier 1 (calibrated, cost-sensitive decision layer) and Tier 2
(temporal windows). Tier 3 adapts Tier 1's thresholds and Tier 2's heads; it
never retrains the backbones.

## 1. Problem

Tiers 1 and 2 make the system *honest* about its errors and *temporal*, but the
decision layer is still global. Every threshold, weight and calibration
quantile is shared across all users. In practice:

- resting gait, tremor and device placement produce different IMU baselines;
- hearing range and household acoustics shift the audio envelope;
- a bedroom camera sees different furniture and lighting than a hallway one.

A global model is therefore always slightly wrong for every user, and those
errors are *systematic* (bias), not random noise. Conformal calibration from
Tier 1 bounds the false-alarm rate only under exchangeability with the
calibration stream; a new user violates that until some adaptation has happened.

## 2. Goals

- Adapt the decision layer (thresholds, head) per device, on-device, with no
  raw data leaving the device.
- Bound catastrophic forgetting explicitly, and be able to roll back.
- Keep personalization strictly opt-in and privacy-preserving by construction.
- Preserve legacy behavior when disabled (byte-identical decisions).
- Work within a tight budget: no GPU, bounded RAM, bounded flash.

## 3. Design overview

1. **`AdaptiveHead`** — frozen slow path + fast field-learning path (CFAM
   pattern). Only a small linear/logistic head and the Tier 1 calibration
   quantiles adapt. Backbone weights are immutable.
2. **`PrototypeMemory`** — exemplar-free synthetic replay: store *condensed*
   prototypes (class means plus small residual noise), never raw frames or
   audio. This is what FALLNEST-style condensation buys you at runtime.
3. **`FederatedClient`** — optional DP-SGD + secure aggregation round client.
   Off by default. Requires an explicit consent scope. Gradients only.

Design rule: **personalization may make the system more sensitive or less
sensitive, but it may never remove a hard safety floor** (freefall, impact,
audio distress scream). Those floors are applied after adaptation.

## 4. Concrete API

### 4.1 `modules/adaptation.py`

```python
from __future__ import annotations

from dataclasses import dataclass
from typing import Dict, Optional, Tuple

import numpy as np


@dataclass(frozen=True)
class AdaptationStats:
    steps: int
    loss: float
    accepted: int
    rejected: int


class AdaptiveHead:
    """Logistic head over frozen features, adapted with bounded steps.

    The backbone (Tier 2 GatedCNN1D) produces ``features``; only the head and
    an optional threshold offset are updated. Every ``checkpoint_every`` steps
    the current weights are shadow-copied for rollback.
    """

    def __init__(
        self,
        n_features: int,
        lr: float = 0.01,
        max_steps: int = 200,
        l2: float = 1e-3,
        min_confidence: float = 0.6,
        checkpoint_every: int = 50,
    ) -> None:
        if n_features < 1:
            raise ValueError("n_features must be positive")
        if not 0.0 < lr < 1.0:
            raise ValueError("lr must be in (0, 1)")
        self.n_features = int(n_features)
        self.lr = float(lr)
        self.max_steps = int(max_steps)
        self.l2 = float(l2)
        self.min_confidence = float(min_confidence)
        self.checkpoint_every = int(checkpoint_every)

        self._w = np.zeros(n_features, dtype=np.float64)
        self._b = 0.0
        self._steps = 0
        self._shadow: Optional[Tuple[np.ndarray, float]] = None
        self._stats = AdaptationStats(0, 0.0, 0, 0)

    # -- inference ------------------------------------------------------
    def predict(self, features: np.ndarray) -> Tuple[float, float]:
        """Return (probability, confidence) in [0, 1]."""
        x = np.asarray(features, dtype=np.float64).ravel()
        if x.shape[0] != self.n_features or not np.isfinite(x).all():
            return 0.0, 0.0
        z = float(x @ self._w + self._b)
        p = 1.0 / (1.0 + np.exp(-max(min(z, 60.0), -60.0)))
        # confidence grows with |logit| and with adaptation evidence
        conf = min(1.0, abs(z) / 4.0) * min(1.0, self._steps / 20.0)
        return float(p), float(conf)

    # -- adaptation -----------------------------------------------------
    def update(self, features: np.ndarray, label: int,
               sample_confidence: float = 1.0) -> bool:
        """One SGD step. Returns False if the sample was rejected.

        Rejection rules protect against label poisoning:
          * low-confidence label source,
          * non-finite / wrong-shape features,
          * step budget exhausted.
        """
        x = np.asarray(features, dtype=np.float64).ravel()
        if x.shape[0] != self.n_features or not np.isfinite(x).all():
            self._reject()
            return False
        if float(sample_confidence) < self.min_confidence:
            self._reject()
            return False
        if self._steps >= self.max_steps:
            self._reject()
            return False

        y = float(1 if int(label) else 0)
        z = float(x @ self._w + self._b)
        p = 1.0 / (1.0 + np.exp(-max(min(z, 60.0), -60.0)))
        grad = (p - y)
        self._w -= self.lr * (grad * x + self.l2 * self._w)
        self._b -= self.lr * grad
        self._steps += 1
        self._stats = AdaptationStats(
            steps=self._steps,
            loss=float(-(y * np.log(max(p, 1e-9))
                         + (1 - y) * np.log(max(1 - p, 1e-9)))),
            accepted=self._stats.accepted + 1,
            rejected=self._stats.rejected,
        )
        if self.checkpoint_every and self._steps % self.checkpoint_every == 0:
            self._shadow = (self._w.copy(), self._b)
        return True

    def _reject(self) -> None:
        s = self._stats
        self._stats = AdaptationStats(
            s.steps, s.loss, s.accepted, s.rejected + 1)

    def rollback(self) -> bool:
        """Restore the last shadow checkpoint. False if none exists."""
        if self._shadow is None:
            return False
        self._w, self._b = self._shadow[0].copy(), self._shadow[1]
        return True

    @property
    def stats(self) -> AdaptationStats:
        return self._stats

    def state_dict(self) -> Dict[str, object]:
        return {
            "n_features": self.n_features,
            "lr": self.lr,
            "max_steps": self.max_steps,
            "l2": self.l2,
            "min_confidence": self.min_confidence,
            "checkpoint_every": self.checkpoint_every,
            "w": self._w.tolist(),
            "b": self._b,
            "steps": self._steps,
            "shadow": None if self._shadow is None else {
                "w": self._shadow[0].tolist(), "b": self._shadow[1]},
        }

    @classmethod
    def from_state_dict(cls, state: Dict[str, object]) -> "AdaptiveHead":
        obj = cls(
            n_features=int(state["n_features"]),
            lr=float(state["lr"]),
            max_steps=int(state["max_steps"]),
            l2=float(state["l2"]),
            min_confidence=float(state["min_confidence"]),
            checkpoint_every=int(state["checkpoint_every"]),
        )
        obj._w = np.asarray(state["w"], dtype=np.float64)
        obj._b = float(state["b"])
        obj._steps = int(state["steps"])
        sh = state.get("shadow")
        if sh:
            w = np.asarray(sh["w"], dtype=np.float64)
            obj._shadow = (w, float(sh["b"]))
        return obj
```

### 4.2 `modules/replay.py`

Exemplar-free memory. Stores per-class prototypes and residual scale, plus a
hard type guard so no raw sensor object can ever be stored.

```python
from __future__ import annotations

from typing import Dict, List, Sequence, Tuple

import numpy as np

_FORBIDDEN_TYPES = ("AudioChunk", "ImuSample", "Frame")


class PrototypeMemory:
    """Condensed, exemplar-free memory for replay during adaptation.

    ``add_prototypes`` accepts only numeric arrays. Anything that looks like a
    raw sensor record is rejected by type name, so the memory cannot become a
    covert recording of the user.
    """

    def __init__(self, capacity: int = 256, n_classes: int = 2) -> None:
        if capacity < 1 or n_classes < 1:
            raise ValueError("capacity and n_classes must be positive")
        self.capacity = int(capacity)
        self.n_classes = int(n_classes)
        self._protos: Dict[int, List[np.ndarray]] = {
            c: [] for c in range(n_classes)}

    def add_prototypes(self, label: int, arrays: Sequence[np.ndarray]) -> int:
        cls = int(label)
        if cls not in self._protos:
            raise ValueError(f"unknown class {label!r}")
        added = 0
        for a in arrays:
            if type(a).__name__ in _FORBIDDEN_TYPES:
                raise TypeError(
                    "raw sensor objects may not enter replay memory")
            arr = np.asarray(a, dtype=np.float64).ravel()
            if arr.size == 0 or not np.isfinite(arr).all():
                continue
            if self._total() >= self.capacity:
                break
            self._protos[cls].append(arr)
            added += 1
        return added

    def _total(self) -> int:
        return sum(len(v) for v in self._protos.values())

    def sample(self, label: int, k: int = 1) -> List[np.ndarray]:
        pool = self._protos.get(int(label), [])
        return list(pool[:k])

    def counts(self) -> Dict[int, int]:
        return {c: len(v) for c, v in self._protos.items()}

    def clear(self) -> None:
        for c in self._protos:
            self._protos[c].clear()

    def state_dict(self) -> Dict[str, object]:
        return {
            "capacity": self.capacity,
            "n_classes": self.n_classes,
            "protos": {
                c: [a.tolist() for a in v]
                for c, v in self._protos.items()
            },
        }

    @classmethod
    def from_state_dict(cls, state: Dict[str, object]) -> "PrototypeMemory":
        obj = cls(int(state["capacity"]), int(state["n_classes"]))
        for c, items in state.get("protos", {}).items():
            obj._protos[int(c)] = [
                np.asarray(a, dtype=np.float64) for a in items]
        return obj
```

### 4.3 `federation/client.py` (opt-in, not imported by runtime)

```python
from __future__ import annotations

from typing import Dict, List, Optional

import numpy as np

from modules.user_consent import UserConsent   # existing module


class FederatedClient:
    """DP-SGD round client. Never sends samples; only clipped, noised deltas.

    ``requires_consent=True`` means ``local_train`` raises unless the consent
    store records the ``federation`` scope.
    """

    def __init__(self, consent: UserConsent, clip: float = 1.0,
                 noise: float = 1.1, requires_consent: bool = True) -> None:
        if clip <= 0 or noise < 0:
            raise ValueError("clip must be positive and noise non-negative")
        self._consent = consent
        self.clip = float(clip)
        self.noise = float(noise)
        self.requires_consent = bool(requires_consent)

    def _check_consent(self) -> None:
        if not self.requires_consent:
            return
        if not self._consent.has_scope("federation"):
            raise PermissionError("federation consent not granted")

    def local_train(
        self, grads: List[np.ndarray], rng=None
    ) -> Dict[str, object]:
        """Clip each per-sample gradient, sum, then add Gaussian noise."""
        self._check_consent()
        rng = rng or np.random.default_rng(0)
        clipped = []
        for g in grads:
            arr = np.asarray(g, dtype=np.float64).ravel()
            if not np.isfinite(arr).all():
                continue
            n = float(np.linalg.norm(arr))
            scale = min(1.0, self.clip / max(n, 1e-12))
            clipped.append(arr * scale)
        total = np.sum(clipped, axis=0) if clipped else np.zeros(1)
        sigma = self.noise * self.clip
        noisy = total + rng.normal(0.0, sigma, size=total.shape)
        return {"delta": noisy.tolist(), "n": len(clipped)}

    def apply_global(self, aggregated_weights: Dict[str, object]) -> None:
        raise NotImplementedError(
            "wired in the deployment tier, not the library")
```

## 5. Runtime integration (pipeline hook)

`EmergencyPipeline` gains one optional hook, called *after* Tier 2 event fusion
and *before* the Tier 1 decision:

```python
def _personalize(self, features, base_score: float) -> float:
    cfg = self._config.get("adaptation", {})
    if not cfg.get("enabled", False) or self._head is None:
        return base_score                      # legacy path
    p, conf = self._head.predict(features)
    if conf < cfg.get("min_confidence", 0.6):
        return base_score                      # not enough personal evidence
    return float(cfg.get("blend", 0.5) * base_score
                 + (1.0 - cfg.get("blend", 0.5)) * p)
```

Hard safety floors are applied *after* `_personalize`, so freefall / impact /
distress-scream always win.

## 6. Config additions (`config/default.yaml`)

```yaml
adaptation:
  enabled: false
  lr: 0.01
  max_steps: 200
  l2: 0.001
  min_confidence: 0.6
  blend: 0.5            # weight of the global score vs. adapted head
  checkpoint_every: 50
  replay_capacity: 256
  state_path: null      # null = in-memory only, nothing persisted

federation:
  enabled: false
  requires_consent: true
  dp_clip: 1.0
  dp_noise: 1.1
  rounds: 0             # 0 = never schedule
```

## 7. Test plan (`tests/test_adaptation.py`)

`AdaptiveHead`:

- `test_cold_start_matches_global_blend` (steps=0 → base score passes through)
- `test_update_moves_probability_in_label_direction`
- `test_low_confidence_label_rejected`
- `test_malformed_features_rejected`
- `test_max_steps_budget_enforced`
- `test_rollback_restores_checkpoint`
- `test_rollback_without_checkpoint_returns_false`
- `test_state_dict_roundtrip_is_deterministic`
- `test_bounded_forgetting_on_synthetic_drift` (accuracy after drift ≥ 0.9 ×
  accuracy before drift, using the shadow checkpoint as reference)

`PrototypeMemory`:

- `test_raw_sensor_objects_rejected`
- `test_capacity_is_enforced`
- `test_unknown_class_rejected`
- `test_state_dict_roundtrip`
- `test_clear_empties_all_classes`

`FederatedClient`:

- `test_requires_consent_when_configured`
- `test_no_consent_raises_permission_error`
- `test_clipping_bounds_delta_norm`
- `test_noise_is_deterministic_with_seeded_rng`
- `test_non_finite_gradients_skipped`
- `test_apply_global_not_implemented_in_library`

Integration (`tests/test_pipeline.py` additions):

- `test_adaptation_disabled_is_byte_identical`
- `test_adaptation_never_bypasses_freefall_floor`
- `test_adaptation_never_bypasses_impact_floor`
- `test_personalized_score_respects_calibrated_threshold`

## 8. Rollout

1. Land `adaptation.py` + `replay.py` with tests, `enabled: false`.
2. Add the pipeline hook behind the flag; legacy tests must stay green.
3. Enable on a single-user pilot with `state_path: null` (in-memory only) and
   watch Tier 1 `coverage_estimate()`; roll back if it drifts.
4. Persist state only after encryption parity with `RollingBuffer` exists.
5. Federation stays disabled until consent UX, secure aggregation and a DP
   accounting report exist. Do not ship it as part of the bachelor artifact.

## 9. Risks and mitigations

- **Label poisoning from operator feedback.** Confidence-gated updates, step
  budget, shadow rollback.
- **Forgetting the global baseline.** Frozen backbone, blend with the global
  score, bounded steps.
- **Replay memory becomes a covert recording.** Type guard + prototypes only,
  never raw frames; encrypt if persisted.
- **DP noise destroys recall on rare falls.** Tune `dp_noise` against the
  Tier 1 false-alarm budget, not accuracy; keep `dp_noise: 1.1` as a
  conservative default.
- **Calibration drift per user.** Tier 1 `coverage_estimate()` alarm;
  re-calibrate on device after N normal samples.
- **Federation leaks via gradients.** DP-SGD accounting, secure aggregation,
  off by default, consent-gated.

## 10. References

- *Continual Field-Adaptive Models (CFAMs).* arXiv:2609.04552.
- *APRIL: exemplar-free continual learning.* arXiv:2610.27942.
- *EPFL: Ensembled Penalized Federated Learning for Fall Detection.*
  arXiv:2510.20960.
- *SF2D: Privacy-Preserving Multi-Stage Fall Detection.* arXiv:2507.10474.
- *PULSE: Personalized Opportunistic Learning for Wearable Devices.*
  arXiv:2610.28081.
- *FALLNEST: Dual-Domain Dataset Condensation of Imbalanced IMU Data.*
  arXiv:2610.18260 (basis for prototype-only memory).
