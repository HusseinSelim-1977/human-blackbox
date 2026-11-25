# Fusion Strategy Optimization Report

**Date:** Nov 24, 2026 (final review: Nov 25, 2026)  
**Focus:** Validating temporal coherence and event duration capture in `fusion.py`

---

## Executive Summary

The shipped fusion engine is **`modules/fusion.py`** (`FusionStrategy`), wired
into `modules/pipeline.py`. `modules/fusion_enhanced.py` remains an experimental
alternative (multiple modes, dominant-modality selection) and is **not** used by
the pipeline or the 159-test suite.

The shipped engine provides:
- Confidence-gated weighted fusion with normalized weights
- Built-in temporal coherence and event start/end span tracking
- An optional ambient Z-score boost driven by runtime baseline statistics

The config values in `config/default.yaml` (`temporal_window: 0.35`,
`temporal_precedence: 0.18`, `event_duration_window: 0.35`) have been applied and
the suite is green. `event_duration_window` is currently declarative only:
`fusion.py` derives `duration_span` directly from per-modality event timestamps.

**Recommendation:** Keep `fusion.py` as the single shipped fusion path; treat
`fusion_enhanced.py` as an unshipped experiment.

---

## Key Findings

### 1. Temporal Coherence Analysis

| Modality | Rolling Window | Timestamp Precision |
|----------|----------------|---------------------|
| **Motion** | 1.0s (deque, maxlen=16) | Direct from IMU |
| **Audio** | 0.975s (YAMNet input) | Chunk-based |
| **Vision** | Frame-based | Discrete (60Hz) |

**Observation:** Motion detector's 1.0s rolling window feeds context to fusion, but fusion's `temporal_window` is 0.35s. This is a deliberate "decision window": modalities must fall within 0.35s of each other to score full temporal coherence, which avoids over-blurring the motion→audio sequence.

### 2. Event Duration Tracking

**Current:** `event_duration_window: 0.35s` (declarative)  
**Purpose:** Track first-to-last timestamp per modality during an event.

**Validation:** `fusion.py` reports `duration_span` as the first-to-last event
timestamp across contributing modalities. The synthetic temporal tests exercise
a motion→audio sequence and a long-duration case; they assert the reported span,
not a hard-coded fraction of a window.

### 3. Detector-Specific Nuances

- **Motion:** Uses a rolling deque (maxlen=16) to feed sequence context. Timestamps are raw IMU samples.
- **Audio:** 0.975s window with spectral features (flatness, ZCR) for broadband detection.
- **Vision:** Frame-based pixel statistics (brightness, edge density, fire ratio).

---

## Recommended Config Tweaks (Applied)

### A. Strengthen Temporal Coherence

Motion precedes audio within `temporal_window`:

```yaml
fusion:
  temporal_window: 0.35        # ↑ from 0.30 to accommodate motion's 1.0s context
  temporal_precedence: 0.18    # ↑ from 0.15 for stronger "motion leads audio" signal
```

### B. Tune Event Duration Window

Capture event start-to-end spans:

```yaml
fusion:
  event_duration_window: 0.35  # ↑ from 0.25 to match audio's 0.975s window
```

### C. Config Mode Selection

```yaml
fusion:
  mode: "adaptive"             # informational; fusion.py does not branch on mode
```

### D. Ambient Z-Score Boost

`fusion.py` supports an optional ambient boost. It is a no-op unless runtime
`ambient_mean`/`ambient_std` tables are supplied; `config/default.yaml` only
declares which features to track via `ambient_baselines.per_modality`.
Applied factors are reported in `raw_features["ambient_boosts"]`.

---

## Implementation Notes

### If Using `fusion.py` (Current Path)

The core weighted fusion implements:
- **Temporal coherence:** `1.0` when all modalities fall inside `temporal_window`, `0.5` when they do not
- **Event duration:** First-to-last timestamp tracking per modality (`event_start`, `event_end`, `duration_span`)
- **Temporal precedence:** `temporal_coherence` drops to `0.5` when audio clearly leads motion, contradicting the expected impact-then-scream ordering

### If Using `fusion_enhanced.py` (Experimental, Not Wired)

Offers:
- **Multiple fusion modes:** `["simple", "adaptive", "dominant", "gated"]`
- **Dominant modality detection:** Identifies strongest signal
- **Confidence-gated fusion:** Only fuses when confidence exceeds threshold

---

## Upstream Detector Tuning

To feed more consistent timestamps into fusion:

### Motion Detector
```python
# modules/motion_detector.py
seq_len = 16  # Already set; ensures rolling window has enough context
```

### Audio Detector
```python
# modules/audio_detector.py
window_sec = 0.975  # YAMNet input window; matches current config
```

### Vision Detector
```python
# modules/vision_detector.py
# Frame-based; no explicit window, but timestamp is discrete
```

---

## Verification Plan (Completed)

1. **Config:** Weights and temporal parameters in `config/default.yaml` match the shipped `fusion.py` defaults. ✓
2. **Synthetic Test:** Temporal tests in `tests/test_fusion_temporal.py` cover motion→audio ordering, audio-only, and long-duration spans. ✓
3. **Regression:** `python3 -m pytest tests/ -q` → 159 passed. ✓
4. **Live Validation:** Not performed; detectors run in heuristic mode unless models are present. This remains a deployment-time check.

---

## Decision (Final)

| Criteria | `fusion.py` (shipped) | `fusion_enhanced.py` (experimental) |
|----------|----------------------|-------------------------------------|
| **Simplicity** | ✓ Clean, weighted | Multiple modes |
| **Temporal Logic** | Built-in | Config-driven |
| **Dominant Modality** | Implicit | Explicit detection |
| **Config Flexibility** | Weights + gate | Via `mode` + weights |
| **Wired into pipeline** | ✓ Yes | ✗ No |
| **Best For** | Balanced, predictable | Research experiments |

---

## Next Steps

No further fusion work is required for the shipped baseline. Optional future
work: add dedicated unit tests for the ambient Z-score boost inside `tests/`
(currently exercised only by root-level scratch scripts) and decide whether to
retire `fusion_enhanced.py`.

---

## Relevant Files

- `/config/default.yaml`: System-level weights and temporal settings
- `/modules/fusion.py`: Shipped weighted fusion with temporal logic and ambient boost
- `/modules/fusion_enhanced.py`: Experimental strategies, not wired into the pipeline
- `/modules/motion_detector.py`: Rolling deque context (maxlen=16)
- `/modules/audio_detector.py`: 0.975s window with spectral features
- `/modules/vision_detector.py`: Frame-based pixel statistics
