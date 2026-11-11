# Done — fusion-engine

## Summary

Implemented `FusionStrategy` in `modules/fusion.py` (169 lines) per the
`docs/CONTRACTS.md` interface and `swarm/requests/fusion-engine.md` brief.
`decide()` accepts either a full system config or a bare `fusion` sub-config,
normalises per-modality weights over the modalities actually present, ignores
unknown modality types, and applies a confidence gate: scores below
`min_confidence` are down-weighted by their own confidence. With
`require_two_modalities` enabled, fewer than two distinct modalities yields
`NORMAL`; otherwise the weighted score is compared against `threshold`
(`>=` means `EMERGENCY`). Empty (or unusable) input returns `INDETERMINATE`
with confidence 0.0. Output is a `ModalityScore` with `modality_type="FUSION"`,
the newest input timestamp, and the required `raw_features` keys. The input
list and its objects are never mutated, and results are deterministic.
`tests/test_fusion.py` covers all required cases plus boundary, deduplication,
timestamp, and real-config tests: 15 passed.

## Implementation notes

- Config defaults: AUDIO 0.35, MOTION 0.40, VISION 0.25; threshold 0.65;
  min_confidence 0.30; require_two_modalities true.
- Weights are normalised only over present, known modalities, so
  `weights_used` sums to 1.0 (or 1/n when all configured weights are zero).
- Weighted score and output confidence use `math.fsum` and are rounded to 12
  decimal places before comparison so an exact `0.65` boundary is not lost to
  floating-point summation error.
- `raw_features` contains `per_modality`, `weights_used`, `weighted_score`,
  `threshold`, `modalities_count`, `agreement`. `agreement` is the fraction of
  present modalities whose own decision equals the final decision.
- Output confidence is the weighted mean of the input confidences.

## Assumptions

# ASSUMPTION: A score with `confidence < min_confidence` is down-weighted as
`effective = score * confidence`; scores at or above the gate keep
`effective = score`.

# ASSUMPTION: Duplicate entries for one modality are deduplicated by keeping
the highest effective score (first occurrence wins an exact tie).

# ASSUMPTION: Input containing no usable known modality (including only
unknown types) is treated like empty input and returns `INDETERMINATE`.

# ASSUMPTION: All-zero configured weights fall back to equal weights over the
modalities present.

# ASSUMPTION: Scores and confidences are clamped to [0.0, 1.0]; modality type
matching is case-insensitive.

# ASSUMPTION: Output confidence is the weighted mean of present input
confidences; `agreement` is the fraction of present modalities agreeing with
the final decision.

## Tests run

Command: `python -m pytest tests/test_fusion.py -v`

```
============================= test session starts ==============================
platform linux -- Python 3.14.10, pytest-9.2.0, pluggy-1.7.0 -- /usr/bin/python
cachedir: .pytest_cache
rootdir: /home/MASTER-H/.local/share/opencode/worktree/e5232d/curious-wolf
plugins: dash-4.5.0
collecting ... collected 15 items

tests/test_fusion.py::test_empty_input_is_indeterminate PASSED           [  6%]
tests/test_fusion.py::test_single_high_audio_is_normal_due_to_two_modality_rule PASSED [ 13%]
tests/test_fusion.py::test_audio_and_motion_high_is_emergency PASSED     [ 20%]
tests/test_fusion.py::test_contradictory_high_and_low_is_normal PASSED   [ 26%]
tests/test_fusion.py::test_low_confidence_score_is_down_weighted PASSED  [ 33%]
tests/test_fusion.py::test_weights_normalise_when_a_modality_is_missing PASSED [ 40%]
tests/test_fusion.py::test_unknown_modality_is_ignored PASSED            [ 46%]
tests/test_fusion.py::test_only_unknown_modalities_is_indeterminate PASSED [ 53%]
tests/test_fusion.py::test_duplicate_modality_keeps_highest_effective_score PASSED [ 60%]
tests/test_fusion.py::test_threshold_boundary_is_emergency PASSED        [ 66%]
tests/test_fusion.py::test_two_modality_rule_can_be_disabled PASSED      [ 73%]
tests/test_fusion.py::test_timestamp_is_newest_input PASSED              [ 80%]
tests/test_fusion.py::test_input_list_is_not_mutated PASSED              [ 86%]
tests/test_fusion.py::test_decide_is_deterministic PASSED                [ 93%]
tests/test_fusion.py::test_uses_repository_default_config PASSED         [100%]

=============================== warnings summary ===============================
../../../../../lib/python3.14/site-packages/dash/testing/plugin.py:104
  /home/MASTER-H/.local/lib/python3.14/site-packages/dash/testing/plugin.py:104: PytestRemovedIn10Warning: The hookimpl pytest_addhooks uses old-style configuration options (marks or attributes).
  Please use the pytest.hookimpl(tryfirst=True) decorator instead
   to configure the hooks.
   See https://docs.pytest.org/en/latest/deprecations.html#configuring-hook-specs-impls-using-markers
    @pytest.mark.tryfirst

-- Docs: https://docs.pytest.org/en/stable/how-to/capture-warnings.html
======================== 15 passed, 1 warning in 0.02s =========================
```

## Files touched

- `modules/fusion.py` (new)
- `tests/test_fusion.py` (new)
- `swarm/done/fusion-engine.md` (new)
