# Solo Task Brief — integration-safety (Senior Integration & Safety Engineer)

Repo root: `/home/MASTER-H/Documents/Bachelor Thesis/human-blackbox`

## Read first
1. `docs/CONTRACTS.md` — every interface, Section 4 territory map, Safety
   Non-Negotiables.
2. `config/default.yaml` — whole file, especially `fusion`, `alert`, `safety`.
3. `modules/types.py` — import, never edit.

## Owned files (only these)
- `modules/pipeline.py` (< 200 lines)
- `docs/ARCHITECTURE.md`
- `tests/test_pipeline.py`
- `swarm/done/integration-safety.md`

## Deliverable
`modules/pipeline.py` — `class EmergencyPipeline` that wires the contract
interfaces **by dependency injection**:
`__init__(self, audio_detector, motion_detector, vision_detector, fusion, buffer, alert_sender, consent_manager, config)`.
Methods:
- `process(self, audio_chunk=None, imu_sample=None, frame=None) -> ModalityScore`:
  run each detector whose input is provided, append each `(chunk, score)` to the
  buffer, fuse all scores, and when the fused decision is `"EMERGENCY"` call
  `alert_sender.send(fused, buffer, user_id)` exactly once. Store the last
  decision in `self.last_decision`.
- `safety_audit(self) -> dict` with boolean keys `recording_indicator`,
  `mic_consent`, `camera_consent`, `countdown_cancellable`,
  `raw_media_on_device`; read from config + consent manager.
- `start_capture(self, sensor)` / `stop_capture(self, sensor)` delegating to the
  consent manager (indicator must be visible while capturing).
Only `modules.types` may be imported at module level; detectors/fusion/buffer/
alert are injected, so this file works before the others land.

`docs/ARCHITECTURE.md`: overview, ASCII diagram of sensor -> detector ->
fusion -> buffer -> alert path, safety gates table (4 non-negotiables), data
flow, failure modes, and the 60 s buffer / 15 s countdown timing.

## Tests (must show real output)
Use stubs defined in the test file (no imports of unfinished modules):
- two high-scoring stubs -> EMERGENCY and alert called once;
- one high-scoring stub -> NORMAL and alert not called;
- alert cancelled (stub returns False) -> `process` still returns the decision;
- only provided inputs run;
- safety_audit returns all True for the shipped config;
- capture start requires consent and indicator toggles.
Run `python -m pytest tests/test_pipeline.py -v` and paste the actual output
into the done file.

## Handoff
Write `swarm/done/integration-safety.md` (summary <= 200 words, implementation,
test output, `# ASSUMPTION` lines, files touched). Do not touch other
territories, `config/`, `README.md`, or `docs/CONTRACTS.md`.
