# Tier 1 Design — Conformal-Calibrated, Cost-Sensitive Fusion

Status: design proposal (not yet implemented)
Target modules: `modules/calibration.py` (new), `modules/fusion.py`
(extend), `modules/pipeline.py` (wire), `config/default.yaml` (extend),
`tests/test_calibration.py` (new)

## 1. Problem

`FusionStrategy.decide` currently produces `EMERGENCY` when a fixed weighted
score crosses a hard-coded `0.65`, gated by a `require_two_modalities` rule.
Two failures follow:

1. **No error guarantee.** The threshold encodes a guess, not a false-alarm
   budget. We cannot state "alerts fire at most 5% of the time when nothing is
   wrong."
2. **Cost blindness.** A missed fall and a false alarm are treated as equally
   bad, even though their real-world costs differ by orders of magnitude.

Additionally, detectors fall back silently to heuristics, so a fused
"confidence" does not distinguish a real model from a formula. Fusion should
know this.

## 2. Goals

- Provide a distribution-free bound on the false-alarm rate of alerting.
- Make the alert threshold a function of an explicit cost matrix, not a
  magic constant.
- Penalize heuristic-mode inputs during fusion.
- Preserve backward compatibility: with calibration disabled, behavior is
  byte-for-byte identical to today.
- Keep everything on-device; no network, no new heavy dependency.

## 3. Design overview

Three small pieces:

1. **`ConformalThreshold`** — calibrates the alerting threshold from a rolling
   window of *normal* (non-emergency) fused scores, using split/online
   conformal quantiles. Declaring emergency above the calibrated threshold
   gives a marginal false-alarm rate ≤ `alpha`.
2. **`CostSensitivePolicy`** — given empirical score distributions for normal
   and emergency windows and a cost matrix `(cost_false_alarm,
   cost_missed_emergency)`, picks the threshold that minimizes expected cost.
3. **Mode-aware confidence** — a small transform applied to each
   `ModalityScore` before fusion that down-weights `mode == "heuristic"`
   inputs.

`FusionStrategy` composes these behind a config switch.

## 4. Concrete API

### 4.1 `modules/calibration.py`

```python
from __future__ import annotations

from collections import deque
from typing import Deque, Dict, Optional


class ConformalThreshold:
    """Distribution-free threshold for a target false-alarm rate.

    Calibrates on scores observed during known-NORMAL operation. The
    (1 - alpha) empirical quantile of those scores is the alert threshold:
    a new score above it is declared EMERGENCY. Under exchangeability the
    marginal false-alarm rate is <= alpha.
    """

    def __init__(
        self,
        alpha: float = 0.05,
        window: int = 500,
        min_samples: int = 50,
    ) -> None:
        if not 0.0 < alpha < 1.0:
            raise ValueError("alpha must be in (0, 1)")
        if window < 1 or min_samples < 1:
            raise ValueError("window and min_samples must be positive")
        self.alpha = float(alpha)
        self.window = int(window)
        self.min_samples = int(min_samples)
        self._normal_scores: Deque[float] = deque(maxlen=self.window)

    # -- calibration ---------------------------------------------------
    def observe_normal(self, score: float) -> None:
        """Record a score observed while the system was known to be normal."""
        self._normal_scores.append(float(score))

    def is_ready(self) -> bool:
        return len(self._normal_scores) >= self.min_samples

    def threshold(self) -> Optional[float]:
        """Conformal quantile, or None until enough normal samples exist."""
        if not self.is_ready():
            return None
        ordered = sorted(self._normal_scores)
        # conformal quantile index: ceil((n + 1) * (1 - alpha)) - 1, clamped
        import math
        idx = math.ceil((len(ordered) + 1) * (1.0 - self.alpha)) - 1
        idx = min(max(idx, 0), len(ordered) - 1)
        return ordered[idx]

    def coverage_estimate(self) -> float:
        """Fraction of observed normal scores at or below the threshold."""
        t = self.threshold()
        if t is None:
            return 0.0
        n = len(self._normal_scores)
        return sum(1 for s in self._normal_scores if s <= t) / n

    # -- persistence ---------------------------------------------------
    def state_dict(self) -> Dict[str, object]:
        return {
            "alpha": self.alpha,
            "window": self.window,
            "min_samples": self.min_samples,
            "normal_scores": list(self._normal_scores),
        }

    @classmethod
    def from_state_dict(cls, state: Dict[str, object]) -> "ConformalThreshold":
        obj = cls(
            alpha=float(state["alpha"]),
            window=int(state["window"]),
            min_samples=int(state["min_samples"]),
        )
        for s in state.get("normal_scores", []):
            obj.observe_normal(float(s))
        return obj


class CostSensitivePolicy:
    """Choose a decision threshold that minimizes expected cost.

    Expected cost at threshold t:
        c_fa * P(normal) * P(score > t | normal)
      + c_miss * P(emergency) * P(score <= t | emergency)
    """

    def __init__(
        self,
        cost_false_alarm: float = 1.0,
        cost_missed_emergency: float = 100.0,
    ) -> None:
        if cost_false_alarm < 0 or cost_missed_emergency < 0:
            raise ValueError("costs must be non-negative")
        if cost_false_alarm == 0 and cost_missed_emergency == 0:
            raise ValueError("at least one cost must be positive")
        self.cost_false_alarm = float(cost_false_alarm)
        self.cost_missed_emergency = float(cost_missed_emergency)

    def optimal_threshold(
        self,
        normal_scores: list[float],
        emergency_scores: list[float],
    ) -> Optional[float]:
        """Grid-search the candidate threshold with the lowest expected cost."""
        if not normal_scores and not emergency_scores:
            return None
        candidates = sorted({0.0, 1.0, *normal_scores, *emergency_scores})
        n_norm = max(len(normal_scores), 1)
        n_em = max(len(emergency_scores), 1)
        total = len(normal_scores) + len(emergency_scores) or 1
        p_norm = len(normal_scores) / total
        p_em = 1.0 - p_norm

        best_t = 0.5
        best_cost = float("inf")
        for t in candidates:
            fa = sum(1 for s in normal_scores if s > t) / n_norm
            miss = sum(1 for s in emergency_scores if s <= t) / n_em
            cost = (
                self.cost_false_alarm * p_norm * fa
                + self.cost_missed_emergency * p_em * miss
            )
            if cost < best_cost:
                best_cost = cost
                best_t = t
        return best_t
```

### 4.2 `modules/fusion.py` additions

Add an optional keyword-only `calibrator` and `policy`, plus a
`_effective_score` transform:

```python
_HEURISTIC_PENALTY = 0.85  # configurable; see fusion.mode_penalty

def _mode_penalty(modality_score) -> float:
    mode = (modality_score.raw_features or {}).get("mode")
    return 1.0 if mode in (None, "model", "tflite") else _HEURISTIC_PENALTY
```

Extend `decide` (illustrative):

```python
def decide(self, modality_scores, config):
    # ... existing weighted fusion ...
    effective = score * _mode_penalty(ms)  # per modality, before weighting
    # ...
    threshold = self._decision_threshold(fused_score)
    decision = EMERGENCY if fused_score >= threshold else NORMAL
```

```python
def _decision_threshold(self, fused_score: float) -> float:
    cfg = self._config.get("fusion", {})
    if not cfg.get("calibration", {}).get("enabled", False):
        return float(cfg.get("threshold", 0.65))  # unchanged legacy path
    t = self._calibrator.threshold()
    if t is None:
        return float(cfg.get("threshold", 0.65))  # warm-up fallback
    return float(t)
```

Feedback into calibration:

```python
def observe_outcome(self, fused_score: float, is_emergency: bool) -> None:
    """Called by the pipeline with ground truth / operator feedback."""
    if is_emergency:
        self._emergency_scores.append(fused_score)
    else:
        self._calibrator.observe_normal(fused_score)
```

### 4.3 `modules/pipeline.py` wiring

`EmergencyPipeline.process` gains an optional `because`/`feedback` path — in
practice the UI (or a later labelled event) calls
`pipeline.feedback(fused_score, is_emergency=True/False)` after the fact.
For safety, calibration must **never** lower the threshold during warm-up:
until `is_ready()` is true, the legacy threshold applies.

## 5. Config additions (`config/default.yaml`)

```yaml
fusion:
  weights:
    AUDIO: 0.35
    MOTION: 0.40
    VISION: 0.25
  threshold: 0.65            # legacy / cold-start threshold
  min_confidence: 0.30
  require_two_modalities: true
  mode_penalty: 0.85         # multiply score of heuristic-mode inputs
  calibration:
    enabled: false           # opt-in; keeps current behavior by default
    alpha: 0.05              # target false-alarm rate (5%)
    window: 500              # rolling normal-score window
    min_samples: 50          # cold-start guard
  costs:
    false_alarm: 1.0
    missed_emergency: 100.0
```

## 6. Test plan (`tests/test_calibration.py`)

Unit — `ConformalThreshold`:

- `test_requires_min_samples_before_threshold`
- `test_threshold_is_conformal_quantile` (hand-computed small vectors)
- `test_alpha_controls_false_alarm_rate` (synthetic normal stream:
  fraction above threshold ≈ alpha ± tolerance)
- `test_window_expiry_forgets_old_scores`
- `test_state_dict_roundtrip`

Unit — `CostSensitivePolicy`:

- `test_higher_miss_cost_lowers_threshold`
- `test_zero_false_alarm_cost_pushes_threshold_low`
- `test_symmetric_costs_meet_near_boundary`
- `test_empty_inputs_return_none`

Integration — `tests/test_fusion.py` additions:

- `test_calibration_disabled_is_legacy_behavior` (bit-identical decisions)
- `test_calibration_enabled_warmup_uses_legacy_threshold`
- `test_heuristic_mode_input_is_down_weighted`
- `test_calibrated_threshold_respects_alpha_on_normal_stream`

Integration — `tests/test_pipeline.py` additions:

- `test_feedback_updates_calibrator`
- `test_no_alert_during_warmup_regression`

Property tests (optional, Hypothesis):

- For any alpha in (0, 1) and n ≥ min_samples,
  `coverage_estimate() >= 1 - alpha - tol`.

## 7. Migration / rollout

1. Ship `calibration.py` with tests; nothing imports it yet.
2. Add config block with `enabled: false`; merge.
3. Extend `FusionStrategy` behind the flag; legacy tests must stay green.
4. Add `feedback()` to the pipeline; wire an operator/label source later.
5. Flip `enabled: true` in a staging config once real normal streams are
   collected (≥ `min_samples`).
6. Record achieved false-alarm rate in `docs/ASSUMPTIONS.md`.

## 8. Risks and mitigations

- **Exchangeability violated by non-stationary sensor streams.** Use a rolling
  window (online conformal) and re-calibrate; consider `arXiv:2610.02357`
  localized CP.
- **Warm-up has no threshold.** Fall back to legacy threshold until
  `min_samples` reached.
- **Heuristic penalty hides a real emergency.** Penalty only scales score,
  never vetoes; the cost matrix still forces conservative behaviour.
- **Calibration drift.** Track `coverage_estimate()`; raise an audit flag if
  it falls below `1 - alpha - 0.1`.

## 9. References

- Zhai, Cheng, Wu. *Valid and Efficient Split Conformal Regression for Time
  Series.* arXiv:2609.33866.
- Lee, Lee, Xie. *Conformal Prediction for Time Series with Deep Sequence
  Models.* arXiv:2610.02357.
- Jin et al. *Retrieval-Corrected Conformal Prediction for Time Series.*
  arXiv:2608.10553 (CIKM '26).
- Aderinola et al. *Watch Your Step: A Cost-Sensitive Framework for
  Accelerometer-Based Fall Detection in Real-World Streaming Scenarios.*
  arXiv:2509.11789.
- Zhou, Wang. *An Edge-Cloud Collaborative Architecture for Proactive Elderly
  Care.* arXiv:2604.14154 (weighted multimodal fusion with confidence
  propagation).
