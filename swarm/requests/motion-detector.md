# Solo Task Brief — motion-detector (Senior Motion / IMU Engineer)

Repo root: `/home/MASTER-H/Documents/Bachelor Thesis/human-blackbox`

## Read first
1. `docs/CONTRACTS.md` — `ImuSample`, `ModalityScore`, `MotionDetector.predict`.
2. `config/default.yaml` — `detectors.motion`, `emergency_thresholds`.
3. `modules/types.py` — import, never edit.

## Owned files (only these)
- `modules/motion_detector.py` (< 200 lines)
- `tests/test_motion_detector.py`
- `swarm/done/motion-detector.md`

## Deliverable
`class MotionDetector` with the exact contract:
`__init__(self, model_path: str, config: dict)` and
`predict(self, imu_sample: ImuSample, config: dict) -> ModalityScore`.

Behaviour:
- Lazy LSTM load (`onnxruntime` / `tflite_runtime`) when the model exists;
  otherwise heuristic (`# ASSUMPTION: heuristic fallback`).
- Per-instance rolling history (`collections.deque`, maxlen from
  `sequence_length`, default 16) used for jerk and freefall duration.
- Features: `|a|`, `|gyro|`, jerk (`d|a|/dt`), freefall (|a| < 0.35 g for >= 3
  consecutive samples), impact spike (|a| >= 3.0 g), orientation change,
  temperature outside `[temperature_low, temperature_high]`, pressure sanity.
- Score in [0,1]: impact/freefall dominate, then activity above
  `motion_activity` (0.8), then thermal excursion. `decision == "EMERGENCY"`
  when score >= `detectors.motion.threshold` and confidence >= 0.3.
- Empty/short vectors or non-finite values -> `INDETERMINATE`, confidence 0.
- `raw_features` keys: `accel_magnitude`, `gyro_magnitude`, `jerk`,
  `freefall`, `impact`, `temperature_c`, `pressure_hpa`, `activity`, `mode`.
- Deterministic; state must not leak between instances.

## Tests (must show real output)
Cover: resting `[0,0,9.81]` -> NORMAL; freefall-then-impact sequence ->
EMERGENCY; high temperature 45 C -> EMERGENCY; low temperature 5 C ->
EMERGENCY; malformed vector -> INDETERMINATE; independent instances do not
share history; determinism.
Run `python -m pytest tests/test_motion_detector.py -v` and paste the actual
output into the done file.

## Handoff
Write `swarm/done/motion-detector.md` (summary <= 200 words, implementation,
test output, `# ASSUMPTION` lines, files touched). Do not touch other
territories, `config/`, `docs/`, or `README.md`.
