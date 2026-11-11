# integration-safety — Handoff

## Summary

Wired the three modality detectors, fusion strategy, rolling evidence buffer,
alert sender and consent manager into `EmergencyPipeline`. The pipeline runs
only the detectors that receive input, appends every `(chunk, score)` pair to
the buffer, fuses the round's scores, records `last_decision`, and calls
`AlertSender.send()` exactly once when the fused decision is `EMERGENCY`.
`start_capture()`/`stop_capture()` delegate to the consent manager so the
recording indicator follows real capture state, and `safety_audit()` reports
each of the four safety non-negotiables as a boolean. Added an architecture
document covering flow, safety gates, timing and failure modes, plus a
stub-driven test suite that needs no models, sensors or network.

## Implementation notes

- `modules/pipeline.py` imports only `modules.types` at module level; all
  collaborators are injected via the constructor.
- Config access supports both nested mappings (PyYAML) and attribute objects.
- `safety_audit()` combines config flags with live consent-manager state:
  consent gates are satisfied when not required or when granted; the
  recording-indicator gate additionally requires the indicator to be visible
  whenever a consent-gated capture is active.
- Alert failure (user cancellation) is intentionally non-fatal: the decision
  is still stored and returned.
- No-input rounds return `INDETERMINATE` without invoking fusion.
- Motion/IMU capture is not consent-gated; microphone/camera capture is.

## Tests

Command: `python -m pytest tests/test_pipeline.py -v`

```
============================= test session starts ==============================
platform linux -- Python 3.14.10, pytest-9.2.0, pluggy-1.7.0 -- /usr/bin/python
cachedir: .pytest_cache
rootdir: /home/MASTER-H/.local/share/opencode/worktree/e5232d/gentle-canyon
plugins: dash-4.5.0
collecting ... collected 8 items

tests/test_pipeline.py::test_two_high_scores_trigger_emergency_and_alert_once PASSED [ 12%]
tests/test_pipeline.py::test_one_high_score_is_normal_and_sends_no_alert PASSED [ 25%]
tests/test_pipeline.py::test_alert_failure_still_returns_the_decision PASSED [ 37%]
tests/test_pipeline.py::test_only_provided_inputs_are_evaluated PASSED   [ 50%]
tests/test_pipeline.py::test_no_input_reports_indeterminate PASSED       [ 62%]
tests/test_pipeline.py::test_safety_audit_all_true_for_shipped_config PASSED [ 75%]
tests/test_pipeline.py::test_safety_audit_detects_missing_consent PASSED [ 87%]
tests/test_pipeline.py::test_capture_requires_consent_and_indicator_toggles PASSED [100%]

=============================== warnings summary ===============================
../../../../../lib/python3.14/site-packages/dash/testing/plugin.py:104
  /home/MASTER-H/.local/lib/python3.14/site-packages/dash/testing/plugin.py:104: PytestRemovedIn10Warning: The hookimpl pytest_addhooks uses old-style configuration options (marks or attributes).
  Please use the pytest.hookimpl(tryfirst=True) decorator instead
   to configure the hooks.
   See https://docs.pytest.org/en/latest/deprecations.html#configuring-hook-specs-impls-using-markers
    @pytest.mark.tryfirst

-- Docs: https://docs.pytest.org/en/stable/how-to/capture-warnings.html
========================= 8 passed, 1 warning in 0.04s =========================
```

## Assumptions

- `# ASSUMPTION`: An empty round (no chunks) has no evidence, so fusion is not
  invoked and the pipeline reports `INDETERMINATE`.
- `# ASSUMPTION`: `AlertSender.send()` owns the user-cancellable countdown and
  the transmission decision; a `False` return means cancellation and does not
  change the decision returned to the caller.
- `# ASSUMPTION`: The user identifier is not part of the `process()` signature,
  so it is read from an optional top-level `user_id` config key and defaults
  to `"default_user"`; it can be overridden via `pipeline.user_id`.
- `# ASSUMPTION`: Microphone and camera capture require consent; motion/IMU
  capture does not and is tracked locally.
- `# ASSUMPTION`: The consent manager exposes `has_consent`, `start_recording`,
  `stop_recording` and `recording_indicator_visible` as specified in the
  alert-consent brief.

## Files touched

- `modules/pipeline.py` (new)
- `tests/test_pipeline.py` (new)
- `docs/ARCHITECTURE.md` (new)
- `swarm/done/integration-safety.md` (new)
