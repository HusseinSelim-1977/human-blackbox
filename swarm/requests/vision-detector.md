# Solo Task Brief — vision-detector (Senior Vision Engineer)

Repo root: `/home/MASTER-H/Documents/Bachelor Thesis/human-blackbox`

## Read first
1. `docs/CONTRACTS.md` — `Frame`, `ModalityScore`, `VisionDetector.predict`.
2. `config/default.yaml` — `detectors.vision`.
3. `modules/types.py` — import, never edit.

## Owned files (only these)
- `modules/vision_detector.py` (< 200 lines)
- `tests/test_vision_detector.py`
- `swarm/done/vision-detector.md`

## Deliverable
`class VisionDetector` with the exact contract:
`__init__(self, model_path: str, config: dict)` and
`predict(self, frame: Frame, config: dict) -> ModalityScore`.

Behaviour:
- Lazy `ultralytics` YOLOv8 load when the model file exists; no ultralytics is
  installed here, so the heuristic path must work (`# ASSUMPTION: heuristic fallback`).
- Heuristic over `numpy.asarray(frame.image_data, dtype=float).reshape(h, w, 3)`:
  mean brightness, per-channel means, red dominance
  (`mean_r - mean_b`) / 255, fire-like pixel ratio (R > 180, G < 140, B < 90),
  dark ratio (< 40), edge density via `np.gradient` magnitude.
- Score = max(fire-like cue, edge/dark "collapse or obstruction" cue, large
  close-person proxy from dark/edge density). Keep weights explicit and tunable.
- Length mismatch with `width * height * 3` or non-finite pixels ->
  `INDETERMINATE`, confidence 0.
- `decision == "EMERGENCY"` when score >= `detectors.vision.threshold` and
  confidence >= 0.3, else `"NORMAL"`.
- `raw_features` keys: `mean_rgb`, `brightness`, `red_dominance`,
  `fire_ratio`, `dark_ratio`, `edge_density`, `mode`.
- Deterministic, no network, no file writes.

## Tests (must show real output)
Cover: uniform gray frame -> NORMAL; fire-coloured frame (R=220,G=80,B=20) ->
EMERGENCY; black frame -> NORMAL (low confidence allowed); wrong pixel count ->
INDETERMINATE; 640x480 frame is fast (< 1 s); determinism; timestamp passthrough.
Run `python -m pytest tests/test_vision_detector.py -v` and paste the actual
output into the done file.

## Handoff
Write `swarm/done/vision-detector.md` (summary <= 200 words, implementation,
test output, `# ASSUMPTION` lines, files touched). Do not touch other
territories, `config/`, `docs/`, or `README.md`.
