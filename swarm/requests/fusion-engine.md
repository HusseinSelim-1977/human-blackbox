# Solo Task Brief — fusion-engine (Senior Sensor Fusion Engineer)

Repo root: `/home/MASTER-H/Documents/Bachelor Thesis/human-blackbox`

## Read first
1. `docs/CONTRACTS.md` — `ModalityScore`, `FusionStrategy.decide`.
2. `config/default.yaml` — `fusion.weights`, `fusion.threshold`,
   `fusion.min_confidence`, `fusion.require_two_modalities`.
3. `modules/types.py` — import, never edit.

## Owned files (only these)
- `modules/fusion.py` (< 200 lines)
- `tests/test_fusion.py`
- `swarm/done/fusion-engine.md`

## Deliverable
`class FusionStrategy` with the exact contract:
`__init__(self, config: dict)` and
`decide(self, modality_scores: list[ModalityScore], config: dict) -> ModalityScore`.

Behaviour:
- Weights per modality from `config["fusion"]["weights"]` (defaults
  AUDIO 0.35, MOTION 0.40, VISION 0.25). Normalise over the modalities present;
  unknown modality types are ignored.
- Confidence gate: a score with `confidence < min_confidence` is down-weighted
  by its confidence (`effective = score * confidence`), documented as
  `# ASSUMPTION`.
- `require_two_modalities: true` -> fewer than 2 distinct modalities yields
  `"NORMAL"` regardless of score.
- Weighted score >= threshold -> `"EMERGENCY"`, else `"NORMAL"`. Empty input ->
  `"INDETERMINATE"` with confidence 0.0.
- Output `ModalityScore` with `modality_type="FUSION"`, timestamp = newest
  input timestamp, `raw_features` keys: `per_modality`, `weights_used`,
  `weighted_score`, `threshold`, `modalities_count`, `agreement`.
- Deterministic; input list must not be mutated.

## Tests (must show real output)
Cover: empty -> INDETERMINATE; one high audio score -> NORMAL (two-modality
rule); audio + motion high -> EMERGENCY; contradictory high/low -> NORMAL;
low-confidence score is down-weighted; weights normalise when a modality is
missing; unknown modality ignored; input list unchanged; determinism.
Run `python -m pytest tests/test_fusion.py -v` and paste the actual output
into the done file.

## Handoff
Write `swarm/done/fusion-engine.md` (summary <= 200 words, implementation,
test output, `# ASSUMPTION` lines, files touched). Do not touch other
territories, `config/`, `docs/`, or `README.md`.
