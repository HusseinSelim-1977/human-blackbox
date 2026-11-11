# Audio Detector — Done Report

**Status:** COMPLETE — stage `audio-detector` (solo territory), 32/32 tests passing.

## Summary

Implemented `modules/audio_detector.py` (196 lines) with the contract surface from `docs/CONTRACTS.md`: `AudioDetector(model_path, config)` and `predict(audio_chunk, config) -> ModalityScore`. If `model_path` names an existing file, a TFLite interpreter is lazily loaded (`tflite_runtime.interpreter`, then `tensorflow.lite`); on any import/load failure it silently falls back to a pure-numpy heuristic, recording the active `mode` in `raw_features`. No model file ships in this repo, so the heuristic path is what the tests exercise. The heuristic computes RMS loudness (dBFS→dB SPL, 0 dBFS = 94 dB SPL), peak, zero-crossing rate, FFT spectral centroid and spectral flatness. Score blends loudness above the `audio_noise` dB threshold with broadband (flatness + ZCR) evidence; decision is EMERGENCY iff score ≥ threshold and confidence ≥ 0.3, else NORMAL. Empty or non-finite audio yields INDETERMINATE with confidence 0.0. Deterministic: no network, no writes, no global state.

## Implementation notes

- Signatures exactly per contract; `modality_type` is always `AUDIO`; `timestamp` comes from `AudioChunk.timestamp`; `source_id` travels in `raw_features` (ModalityScore has no source field).
- `raw_features` always carries `rms_db_spl`, `peak`, `zcr`, `spectral_centroid_hz`, `spectral_flatness`, `threshold_db`, `mode` (plus `source_id`; plus `reason` on invalid input). For empty/non-finite/non-numeric input the seven keys are present but `None`.
- Heuristic: loudness = clip((rms_db_spl − threshold_db)/12, 0, 1); broadband = 0.5·(flatness + clip(zcr/0.5, 0, 1)); score = loudness·(0.6 + 0.4·broadband); confidence = 0.25 + 0.75·(0.7·loudness + 0.3·broadband); all clipped to [0, 1].
- Config reads use safe `.get` chains with defaults matching `config/default.yaml` (threshold 0.5, sample_rate 16000, window 0.975 s, audio_noise 80 dB); an empty config dict still works.
- TFLite path pads/truncates to the model window, feeds the input tensor, takes the max over the output tensor as score/confidence; any runtime error falls back to the heuristic for that chunk.

## Assumptions

Every `# ASSUMPTION` marker in the code:

1. **Heuristic fallback / dB SPL calibration** (module docstring, `_DBFS_TO_SPL`): digital full scale is treated as 94 dB SPL, i.e. dB SPL = 20·log10(rms) + 94, so absolute sample values map to physical loudness without hardware calibration.
2. **TFLite output layout** (`_predict_model`): the model's output tensor is assumed to be a per-class score vector where the maximum is the emergency evidence; mapping tensor indices to `emergency_classes` names is deferred to integration (untestable here — no TF runtime and no `.tflite` file in the repo).

Additional heuristic design choices (documented in code): 12 dB loudness headroom above the configured `audio_noise` threshold; equal weighting of spectral flatness and ZCR in the broadband term; the 0.3 minimum-confidence gate for EMERGENCY mirrors `fusion.min_confidence`.

## Pytest output

`python -m pytest tests/test_audio_detector.py -v` (repo root, Python 3.14.10, pytest 9.2.0):

============================= test session starts ==============================
platform linux -- Python 3.14.10, pytest-9.2.0, pluggy-1.7.0 -- /usr/bin/python
cachedir: .pytest_cache
rootdir: /home/MASTER-H/.local/share/opencode/worktree/e5232d/crisp-harbor
plugins: dash-4.5.0
collecting ... collected 32 items

tests/test_audio_detector.py::test_silence_is_normal PASSED              [  3%]
tests/test_audio_detector.py::test_loud_broadband_burst_is_emergency PASSED [  6%]
tests/test_audio_detector.py::test_quiet_broadband_noise_is_normal PASSED [  9%]
tests/test_audio_detector.py::test_loud_tone_is_normal PASSED            [ 12%]
tests/test_audio_detector.py::test_constant_dc_is_normal PASSED          [ 15%]
tests/test_audio_detector.py::test_score_and_confidence_in_unit_range[wave0] PASSED [ 18%]
tests/test_audio_detector.py::test_score_and_confidence_in_unit_range[wave1] PASSED [ 21%]
tests/test_audio_detector.py::test_score_and_confidence_in_unit_range[wave2] PASSED [ 25%]
tests/test_audio_detector.py::test_score_and_confidence_in_unit_range[wave3] PASSED [ 28%]
tests/test_audio_detector.py::test_score_and_confidence_in_unit_range[wave4] PASSED [ 31%]
tests/test_audio_detector.py::test_score_and_confidence_in_unit_range[wave5] PASSED [ 34%]
tests/test_audio_detector.py::test_decision_in_allowed_set[wave0] PASSED [ 37%]
tests/test_audio_detector.py::test_decision_in_allowed_set[wave1] PASSED [ 40%]
tests/test_audio_detector.py::test_decision_in_allowed_set[wave2] PASSED [ 43%]
tests/test_audio_detector.py::test_decision_in_allowed_set[wave3] PASSED [ 46%]
tests/test_audio_detector.py::test_decision_in_allowed_set[wave4] PASSED [ 50%]
tests/test_audio_detector.py::test_empty_chunk_is_indeterminate PASSED   [ 53%]
tests/test_audio_detector.py::test_non_finite_audio_is_indeterminate[wave0] PASSED [ 56%]
tests/test_audio_detector.py::test_non_finite_audio_is_indeterminate[wave1] PASSED [ 59%]
tests/test_audio_detector.py::test_non_finite_audio_is_indeterminate[wave2] PASSED [ 62%]
tests/test_audio_detector.py::test_non_finite_audio_is_indeterminate[wave3] PASSED [ 65%]
tests/test_audio_detector.py::test_timestamp_passthrough PASSED          [ 68%]
tests/test_audio_detector.py::test_source_id_passthrough PASSED          [ 71%]
tests/test_audio_detector.py::test_modality_type_is_audio PASSED         [ 75%]
tests/test_audio_detector.py::test_raw_features_keys PASSED              [ 78%]
tests/test_audio_detector.py::test_missing_model_falls_back_to_heuristic PASSED [ 81%]
tests/test_audio_detector.py::test_config_threshold_is_respected PASSED  [ 84%]
tests/test_audio_detector.py::test_audio_noise_db_threshold_is_respected PASSED [ 87%]
tests/test_audio_detector.py::test_defaults_when_config_keys_missing PASSED [ 90%]
tests/test_audio_detector.py::test_deterministic_on_repeat_call PASSED   [ 93%]
tests/test_audio_detector.py::test_two_detectors_agree PASSED            [ 96%]
tests/test_audio_detector.py::test_with_repo_default_config PASSED       [100%]

=============================== warnings summary ===============================
../../../../../lib/python3.14/site-packages/dash/testing/plugin.py:104
  /home/MASTER-H/.local/lib/python3.14/site-packages/dash/testing/plugin.py:104: PytestRemovedIn10Warning: The hookimpl pytest_addhooks uses old-style configuration options (marks or attributes).
  Please use the pytest.hookimpl(tryfirst=True) decorator instead
   to configure the hooks.
   See https://docs.pytest.org/en/latest/deprecations.html#configuring-hook-specs-impls-using-markers
    @pytest.mark.tryfirst

-- Docs: https://docs.pytest.org/en/stable/how-to/capture-warnings.html
======================== 32 passed, 1 warning in 0.07s =========================

## Files touched

- `modules/audio_detector.py` (new, 196 lines)
- `tests/test_audio_detector.py` (new, 233 lines)
- `swarm/done/audio-detector.md` (this file)
