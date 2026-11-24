# Modality-Specific Calibration Report

**Date:** November 24, 2026  
**Focus:** Tuning per-modality scores for improved emergency detection  
**Mode:** Heuristic fallback (model paths exist but runtime imports are fragile)

---

## Executive Summary

All three detectors currently operate in "heuristic fallback" mode, relying on hand-tuned gains and sensor-specific characteristics. This report synthesizes empirical threshold behaviors from test files and cross-references sensor context to produce actionable tuning recommendations.

---

## Per-Modality Insights

### Vision Detector (`modules/vision_detector.py`)

**Current Config:** `threshold: 0.50`  
**Sensor Context:** RGB camera (fire/obstruction/person cues)

**Key Behaviors:**
- **Fire detection** uses three competing cues: `fire_ratio`, `dark_ratio`, `red_dominance`
- A "partial fire" frame (7 of 20 pixels fire-coloured) yields:
  - `fire_ratio: 0.35` → `score: 0.56` → `confidence: 0.35`
- **Black frame** (dark scene): `dark_ratio: 1.0` with low confidence
- **Threshold gate:** 0.50 triggers EMERGENCY
- **Confidence gate:** 0.50 blocks emergency if below

**Calibration Insight:**
The vision detector is tuned for *contrast*—it needs bright, saturated pixels to trigger. Dark scenes are penalized by `dark_ratio`, which may cause false negatives in low-light conditions.

**Recommendations:**
1. **Lower threshold to 0.45** to catch mid-range fire ratios (0.35–0.50 range)
2. **Add `dark_ratio_penalty: 0.25`** to offset low-light suppression
3. **Raise `min_confidence` to 0.35** to prevent over-blocking

---

### Audio Detector (`modules/audio_detector.py`)

**Current Config:** `threshold: 0.50`, `audio_noise: 80.0 dB`  
**Sensor Context:** Microphone (RMS/ZCR/flatness blending)

**Key Behaviors:**
- **Gates loudness** by 80 dB noise threshold first
- **Loud broadband burst** (~88 dB SPL) = EMERGENCY
- **Loud tone** = NORMAL (broadband evidence stays low)
- **Spectral flatness/ZCR** blend determines final score
- **RMS 0.5** ≈ 88 dB SPL; **RMS 0.05** ≈ 68 dB SPL

**Calibration Insight:**
Audio is *spectrally selective*—it rewards broadband energy over tonal spikes. The 80 dB gate is aggressive, potentially missing mid-level events like "Screaming" that sit at 75–80 dB.

**Recommendations:**
1. **Lower `audio_noise` to 75.0 dB** to catch mid-level events
2. **Keep threshold at 0.50** (well-calibrated for broadband bursts)
3. **Add `spectral_weight: 0.7`** to emphasize flatness over peak

---

### Motion Detector (`modules/motion_detector.py`)

**Current Config:** `threshold: 0.80`, `freefall_g: 0.35`, `impact_g: 3.0`  
**Sensor Context:** Accelerometer (rolling-window activity/thermal/freefall)

**Key Behaviors:**
- **Rolling activity deviation** from 1g baseline
- **Freefall:** |a| below 0.35g for ≥3 samples (impact risk)
- **Impact:** |a| spike at or above 3.0g
- **Temperature band:** 10–40°C (inclusive edges)
- **High-gyro activity** (3.0+ rad/s) = EMERGENCY

**Calibration Insight:**
Motion is *sequence-aware*—it needs sustained deviation to trigger. The 0.80 threshold is high, requiring near-peak activity. Freefall and impact are "hard triggers" that override the main threshold.

**Recommendations:**
1. **Lower threshold to 0.65** to catch sustained activity
2. **Tighten `freefall_g` to 0.45** for earlier fall detection
3. **Keep `impact_g` at 3.0** (hard trigger behavior)

---

## Fusion Strategy

**Current Config:** `mode: "adaptive"`, `weights: {AUDIO: 0.35, MOTION: 0.40, VISION: 0.25}`  
**Behavior:** Two modalities required; effective score = `score * confidence` when below `min_confidence`

**Calibration Insight:**
Motion dominates (0.40) but has the highest threshold (0.80). Audio is well-calibrated (0.50) but gets diluted. Vision is the "wildcard" at 0.25.

**Recommendations:**
1. **Shift weights:** `MOTION: 0.35`, `AUDIO: 0.40`, `VISION: 0.25`
2. **Lower fusion threshold to 0.55** to reduce two-modality friction
3. **Keep `min_confidence` at 0.30** (balanced gate)

---

## Quick-Apply Config Edits

```yaml
# config/default.yaml

detectors:
  vision:
    model_path: "modules/models/yolov8n.pt"
    threshold: 0.45          # was 0.50
    min_confidence: 0.35     # was 0.30
    min_person_area_ratio: 0.15
    frame_width: 640
    frame_height: 480

  audio:
    model_path: "modules/models/yamnet.tflite"
    threshold: 0.50          # keep as-is
    sample_rate_hz: 16000
    window_sec: 0.975
    audio_noise: 75.0        # was 80.0

  motion:
    model_path: "modules/models/motion_lstm.onnx"
    threshold: 0.65          # was 0.80
    sequence_length: 16
    freefall_g: 0.45         # was 0.35
    impact_g: 3.0            # keep as-is
    window_sec: 1.0

fusion:
  mode: "adaptive"
  weights:
    AUDIO: 0.40              # was 0.35
    MOTION: 0.35             # was 0.40
    VISION: 0.25             # keep as-is
  threshold: 0.55            # was 0.65
  min_confidence: 0.30
  require_two_modalities: true
```

---

## Expected Impact

| Modality | Current Trigger | Tuned Trigger | Change |
|----------|-----------------|---------------|--------|
| Vision   | Bright, saturated | Mid-range, contrast | -10% threshold |
| Audio    | Loud broadband   | Mid-level, broadband | -6 dB noise gate |
| Motion   | Near-peak activity | Sustained deviation | -18% threshold |

**Net Effect:** Lowered the bar for individual modalities, allowing the fusion layer to do its work. Vision and audio will fire more reliably; motion will catch sustained events rather than requiring near-peak spikes.

---

## Next Steps

1. **Apply config edits** (see above)
2. **Run test suite** to verify no regressions
3. **Monitor false positives** in low-light and mid-level audio scenarios
4. **Iterate** on `freefall_g` if freefall events feel too aggressive

---

## Relevant Files

- `/home/MASTER-H/Documents/Bachelor Thesis/human-blackbox/config/default.yaml`
- `/home/MASTER-H/Documents/Bachelor Thesis/human-blackbox/modules/vision_detector.py`
- `/home/MASTER-H/Documents/Bachelor Thesis/human-blackbox/modules/audio_detector.py`
- `/home/MASTER-H/Documents/Bachelor Thesis/human-blackbox/modules/motion_detector.py`
- `/home/MASTER-H/Documents/Bachelor Thesis/human-blackbox/tests/test_vision_detector.py`
- `/home/MASTER-H/Documents/Bachelor Thesis/human-blackbox/tests/test_audio_detector.py`
- `/home/MASTER-H/Documents/Bachelor Thesis/human-blackbox/tests/test_motion_detector.py`
