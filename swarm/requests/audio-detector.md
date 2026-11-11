# Solo Task Brief — audio-detector (Senior Audio Detection Engineer)

Repo root: `/home/MASTER-H/Documents/Bachelor Thesis/human-blackbox`

## Read first
1. `docs/CONTRACTS.md` — Data Types (`AudioChunk`, `ModalityScore`), Interfaces
   (`AudioDetector.predict`), Safety Non-Negotiables.
2. `config/default.yaml` — `detectors.audio`, `emergency_thresholds.audio_noise`.
3. `modules/types.py` — import, never edit.

## Owned files (only these)
- `modules/audio_detector.py` (< 200 lines)
- `tests/test_audio_detector.py`
- `swarm/done/audio-detector.md`

## Deliverable
`class AudioDetector` with the exact contract:
`__init__(self, model_path: str, config: dict)` and
`predict(self, audio_chunk: AudioChunk, config: dict) -> ModalityScore`.

Behaviour:
- If `model_path` exists, lazily try `tflite_runtime.interpreter` / `tensorflow`;
  if the import or load fails, fall back silently to the heuristic and record
  `mode` in `raw_features`. No TensorFlow is installed here.
- Heuristic (`# ASSUMPTION: heuristic fallback`): RMS -> dBFS
  (`20*log10(rms)`), dB SPL = dBFS + 94 (0 dBFS == 94 dB SPL), zero-crossing
  rate, FFT spectral centroid, spectral flatness (broadband noise = emergency
  cue). Score blends loudness above `audio_noise` threshold with flatness/ZCR.
- Empty or non-finite audio -> `INDETERMINATE`, confidence `0.0`.
- `decision` is exactly `"EMERGENCY"` when `score >= threshold` and
  `confidence >= 0.3`, otherwise `"NORMAL"`. Threshold from
  `config["detectors"]["audio"]["threshold"]` with a safe `.get` default.
- `raw_features` keys: `rms_db_spl`, `peak`, `zcr`, `spectral_centroid_hz`,
  `spectral_flatness`, `threshold_db`, `mode`.
- Deterministic, no network, no writes, no global state.

## Tests (must show real output)
Cover: silence -> NORMAL; loud broadband burst (rms ~0.5) -> EMERGENCY;
score/confidence in [0,1]; decision in the allowed set; empty chunk ->
INDETERMINATE; timestamp/source passthrough; determinism on repeat call.
Run `python -m pytest tests/test_audio_detector.py -v` and paste the actual
output into the done file.

## Handoff
Write `swarm/done/audio-detector.md` (summary <= 200 words, implementation,
test output, `# ASSUMPTION` lines, files touched). Do not touch other
territories, `config/`, `docs/`, or `README.md`.
