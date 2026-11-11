# motion-detector — done

## Summary

`modules/motion_detector.py` adds `MotionDetector`, the IMU/motion modality of Human BlackBox. It lazily loads an LSTM via `onnxruntime` (`.onnx`) or `tflite_runtime` (`.tflite`) when the model file exists; since `modules/models/motion_lstm.onnx` is not shipped, it runs a deterministic heuristic. Each instance keeps a per-instance `deque` (maxlen `sequence_length`, default 16) of `(timestamp, |a|, |gyro|, jerk)` pruned to `window_sec`. The heuristic scores `max` of: activity (deviation from 1 g, jerk, gyro rate — each normalized to a full-scale constant), thermal excursion outside `[temperature_low, temperature_high]`, freefall (|a| < `freefall_g` for ≥ `freefall_min_samples` consecutive samples → 0.95), and impact (|a| ≥ `impact_g` → 1.0). Confidence starts at 0.5, boosted by impact/freefall and history fill, halved when barometric pressure is implausible. Decision is EMERGENCY when score ≥ `detectors.motion.threshold` (0.80) and confidence ≥ 0.3; INDETERMINATE when score is high but confidence is not; malformed vectors, non-finite scalars, or `None` samples → INDETERMINATE with confidence 0, never touching history. Predict-time config overrides init config; defaults mirror `config/default.yaml`. 27 tests pass.

## Implementation notes

- Module is 198 lines (< 200 required). Pure stdlib at import time; `numpy` and model runtimes are imported lazily inside `_model_score`/`_load_model` only.
- Malformed input (`_vec3` length/finite check; float-coercion of timestamp/temperature/pressure) returns `INDETERMINATE` score 0 confidence 0 and never mutates the rolling history, so one bad sample cannot poison later decisions.
- Freefall uses a trailing streak over the pruned window; the impact sample itself breaks the streak, so freefall+impact reports `impact=True, freefall=False`.
- Rolling window: `while` popleft keeps samples within `window_sec`; deque `maxlen=sequence_length` bounds memory.
- `raw_features` carries exactly the nine contract keys: `accel_magnitude, gyro_magnitude, jerk, freefall, impact, temperature_c, pressure_hpa, activity, mode`.
- Model path (when one ships): `(1, seq, 3)` float32 window `[|a|/g, |gyro|/fs, min(1, jerk/fs)]`; model output replaces the activity term but never masks freefall/impact/thermal floors (safety floor). Any load or inference failure falls back to the heuristic.

## ASSUMPTIONS

- `# ASSUMPTION: heuristic fallback — no trained LSTM ships with the repo` (`modules/models/motion_lstm.onnx` does not exist); LSTM path only runs when the file exists and its runtime imports.
- `# ASSUMPTION: heuristic fallback on any load error` — any exception while loading/running the model (including `ImportError`) reverts to heuristic mode.
- `# ASSUMPTION` g conversion: accelerometer is m/s² per docs/CONTRACTS.md; g-based thresholds (`freefall_g`, `impact_g`) are divided by `STANDARD_G = 9.80665`.
- `# ASSUMPTION` full-scale normalizers (severity 1.0): jerk 200 m/s³, gyro 2 rad/s (fast tumble), |g−1| 2 g; thermal severity saturates 5 °C beyond the band at base 0.8 + 0.2·severity.
- `# ASSUMPTION` pressure plausibility 300–1100 hPa; outside it confidence is halved (suspect barometer), which can yield INDETERMINATE on high scores with confidence < 0.3.
- `# ASSUMPTION` magnetometer is validated (finite 3-vector) but not scored; orientation change is proxied by gyro rate.
- `# ASSUMPTION` LSTM tensor spec: `(1, seq, 3)` float32 `[|a|/g, |gyro|/fs, jerk/fs]`, one output score in [0, 1]; non-finite/out-of-range output is discarded.
- `# ASSUMPTION` score priority read as `max()`: impact (1.0) and freefall (0.95) dominate activity, then thermal; the model replaces the activity term only.

## Files touched

- `modules/motion_detector.py` (new, 198 lines)
- `tests/test_motion_detector.py` (new, 27 tests)
- `swarm/done/motion-detector.md` (this file)

## Test output

Command: `python -m pytest tests/test_motion_detector.py -v`

```
============================= test session starts ==============================
platform linux -- Python 3.14.10, pytest-9.2.0, pluggy-1.7.0 -- /usr/bin/python
cachedir: .pytest_cache
rootdir: /home/MASTER-H/.local/share/opencode/worktree/e5232d/quick-rocket
plugins: dash-4.5.0
collecting ... collected 27 items

tests/test_motion_detector.py::test_returns_modality_score_with_motion_type PASSED [  3%]
tests/test_motion_detector.py::test_raw_features_has_exactly_required_keys PASSED [  7%]
tests/test_motion_detector.py::test_missing_model_falls_back_to_heuristic PASSED [ 11%]
tests/test_motion_detector.py::test_corrupt_model_file_falls_back_to_heuristic PASSED [ 14%]
tests/test_motion_detector.py::test_resting_is_normal PASSED [ 18%]
tests/test_motion_detector.py::test_temperature_inside_band_is_normal PASSED [ 22%]
tests/test_motion_detector.py::test_predict_config_none_uses_defaults PASSED [ 25%]
tests/test_motion_detector.py::test_freefall_then_impact_is_emergency PASSED [ 29%]
tests/test_motion_detector.py::test_impact_alone_is_emergency PASSED [ 33%]
tests/test_motion_detector.py::test_high_temperature_is_emergency PASSED [ 37%]
tests/test_motion_detector.py::test_low_temperature_is_emergency PASSED [ 40%]
tests/test_motion_detector.py::test_high_gyro_activity_is_emergency PASSED [ 44%]
tests/test_motion_detector.py::test_high_jerk_is_emergency PASSED [ 48%]
tests/test_motion_detector.py::test_implausible_pressure_halves_confidence PASSED [ 51%]
tests/test_motion_detector.py::test_high_score_low_confidence_is_indeterminate PASSED [ 55%]
tests/test_motion_detector.py::test_short_vector_is_indeterminate PASSED [ 59%]
tests/test_motion_detector.py::test_nan_accelerometer_is_indeterminate PASSED [ 62%]
tests/test_motion_detector.py::test_none_sample_is_indeterminate PASSED  [ 66%]
tests/test_motion_detector.py::test_non_finite_temperature_is_indeterminate PASSED [ 70%]
tests/test_motion_detector.py::test_non_numeric_pressure_is_indeterminate PASSED [ 74%]
tests/test_motion_detector.py::test_malformed_sample_keeps_timestamp_and_type PASSED [ 77%]
tests/test_motion_detector.py::test_malformed_sample_does_not_touch_history PASSED [ 81%]
tests/test_motion_detector.py::test_instances_do_not_share_history PASSED [ 85%]
tests/test_motion_detector.py::test_detector_recovers_after_emergency PASSED [ 88%]
tests/test_motion_detector.py::test_window_expires_old_samples PASSED    [ 92%]
tests/test_motion_detector.py::test_predict_time_config_overrides_thresholds PASSED [ 96%]
tests/test_motion_detector.py::test_deterministic_across_instances PASSED [100%]

=============================== warnings summary ===============================
../../../../../lib/python3.14/site-packages/dash/testing/plugin.py:104
  /home/MASTER-H/.local/lib/python3.14/site-packages/dash/testing/plugin.py:104: PytestRemovedIn10Warning: The hookimpl pytest_addhooks uses old-style configuration options (marks or attributes).
  Please use the pytest.hookimpl(tryfirst=True) decorator instead to configure the hooks.
  See https://docs.pytest.org/en/latest/deprecations.html#configuring-hook-specs-impls-using-markers-or-attributes
    @pytest.mark.tryfirst

-- Docs: https://docs.pytest.org/en/stable/how-to/capture-warnings.html
======================== 27 passed, 1 warning in 0.03s =========================
```

(The single warning comes from the unrelated third-party `dash` pytest plugin, not from this module or its tests.)
