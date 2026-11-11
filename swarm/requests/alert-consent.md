# Solo Task Brief — alert-consent (Senior Safety & Alerting Engineer)

Repo root: `/home/MASTER-H/Documents/Bachelor Thesis/human-blackbox`

## Read first
1. `docs/CONTRACTS.md` — `AlertSender.send`, `cancel_user_countdown`,
   Safety Non-Negotiables (all four are mandatory).
2. `config/default.yaml` — `alert`, `safety`.
3. `modules/types.py` — import, never edit.

## Owned files (only these)
- `modules/alert_sender.py` (< 200 lines)
- `modules/user_consent.py` (< 200 lines)
- `tests/test_alert_sender.py`
- `swarm/done/alert-consent.md`

## Deliverables

### `modules/user_consent.py`
`class UserConsentManager`:
- `request_consent(self, sensor: str) -> bool` (records an explicit grant for
  `"microphone"` / `"camera"`; returns True)
- `revoke_consent(self, sensor: str) -> None`, `has_consent(self, sensor) -> bool`
- `start_recording(self, sensor) -> bool` — requires consent, turns the
  indicator on, returns False if consent missing
- `stop_recording(self, sensor) -> None` — turns the indicator off when no
  sensor is recording
- `recording_indicator_visible` property (True whenever any capture is active)
- `class ConsentRequiredError(RuntimeError)`

### `modules/alert_sender.py`
`class AlertSender` with the exact contract
`__init__(self, device_id: str, encryption_key: str, user_consent_manager: object)`
plus optional keyword-only test hooks
`countdown_sec: int = 15, tick: float = 1.0, sleep_fn=time.sleep, notifier=None`
(`# ASSUMPTION` compatible extension so tests need not wait 15 s).
- `send(decision, evidence_buffer, user_id) -> bool`:
  non-`"EMERGENCY"` decision -> False. If the consent manager exists, require
  microphone consent; missing -> False. Run a visible, tick-by-tick countdown
  (default 15 ticks) calling `notifier(remaining)`; if `cancel_user_countdown()`
  is called at any point -> return False immediately. Otherwise build an
  encrypted payload (hashlib/HMAC stand-in, no raw media inline), return True.
- `cancel_user_countdown()` sets a thread-safe cancel flag for the active send.
- Never uploads raw media; payload carries hashes/metadata only.
  `# ASSUMPTION: stand-in crypto` documented in the docstring.

## Tests (must show real output)
Cover: full countdown (tick=0) returns True; cancel during countdown returns
False; non-emergency returns False; missing consent returns False; revoke after
grant blocks recording; indicator on while recording and off after stop;
countdown notifier receives 15..1; two sequential sends are independent;
payload/log has no raw audio bytes.
Run `python -m pytest tests/test_alert_sender.py -v` and paste the actual
output into the done file.

## Handoff
Write `swarm/done/alert-consent.md` (summary <= 200 words, implementation,
test output, `# ASSUMPTION` lines, files touched). Do not touch other
territories, `config/`, `docs/`, or `README.md`.
