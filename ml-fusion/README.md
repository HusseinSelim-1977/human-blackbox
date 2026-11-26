# ML Fusion safety decision layer

Pure Kotlin decision logic layered *on top of* `FusionEngine`. The learned model
and its tensor contract are unchanged; this layer only consumes scalar
`Decision` outputs plus coarse context settings. It makes no device, latency or
detection-performance claims.

## Privacy boundary

Stored state is scalar only: sensitivity, context flags, verifier state/streaks,
candidate event and alert stage. `AdaptiveThresholdStore.settings()` contains
exactly two setting strings (`fusion.threshold_schema`, `fusion.sensitivity`);
the caller may persist those in its settings store. No audio, video, IMU,
probabilities, detection objects, histories or evidence payloads are stored by
these classes. `Detection` values are consumed per tick and discarded.

## Tensor and threshold shapes

| Item | Shape / range |
| --- | --- |
| FusionEngine input | `[1, 8, 27]` float32; 8 ticks x (AUDIO 8 + MOTION 8 + VISION 8 + 3 global metadata). Each modality has 7 learned class probs + unknown. |
| FusionEngine output | `[1, 11]` float32; emergency + 7 class probs + 3 modality contributions. |
| `sensitivity` | Float in `[0, 1]`; persisted as a setting string. |
| `SafetyThresholds` | escalation/cancel/notify Floats in `[0, 1]`; hysteresis/verification ticks Int in `[3, 6]`. |
| `ContextFlag` | coarse enum: `VEHICLE`, `LOW_LIGHT`, `NOISY`, `POCKET`, `UNKNOWN`. |
| `VerificationTick` | one `Decision` + `Map<Modality, Detection>` for that tick only. |

### Hard-coded safety floors (never relaxed)

- escalation `>= 0.60`, `<= 0.85`
- cancellation `<= 0.40`, `>= 0.15`
- notification `>= 0.90`
- escalation/cancel separation `>= 0.10`, notify/escalation gap `>= 0.04`
- hysteresis and sustained ticks `>= 3`
- at least 2 agreeing modalities; individual modality vote `>= 0.45`

Sensitivity 0 -> 1 maps escalation `0.82 -> 0.60`, cancel `0.40 -> 0.22`,
hysteresis `5 -> 3`, notify fixed at `0.90`. Context flags only raise escalation
(capped `+0.10`), lower cancel (capped `-0.06`) and add ticks (capped `+2`);
`UNKNOWN` is neutral. Floors are re-applied after every adjustment.

## TemporalVerifier state transitions

| From | Condition | To | Emitted Decision |
| --- | --- | --- | --- |
| IDLE / INDETERMINATE / CANCELLED | strong tick (p >= escalation, actionable event, >= 2 modality votes, same event) | CANDIDATE | `EventType.UNKNOWN`, `NONE` |
| CANDIDATE | strong ticks reach `sustainedTicks` | CONFIRMED | agreed event, `CHECK_IN` |
| CANDIDATE | p <= cancel for `hysteresisTicks` ticks | CANCELLED | `NORMAL`, `NONE` |
| CANDIDATE | gap (p between cancel and escalation, or missing agreement) | INDETERMINATE | `EventType.UNKNOWN`, `NONE` |
| INDETERMINATE | strong tick | CANDIDATE | `EventType.UNKNOWN`, `NONE` |
| CONFIRMED | p > cancel (any strength) | CONFIRMED (latched) | sticky event, `CHECK_IN` |
| CONFIRMED | p <= cancel for `hysteresisTicks` ticks | CANCELLED | `NORMAL`, `NONE` |
| any | `cancel()` | CANCELLED | `NORMAL`, `NONE` |
| any | `reset()` / `configure()` | IDLE | `EventType.UNKNOWN`, `NONE` |
| any | input `Action.MANUAL_SOS` | unchanged | input Decision returned untouched |

INDETERMINATE is deliberately represented as `EventType.UNKNOWN` — there is no
custom indeterminate enum value.

## AlertPolicy state transitions

Feed `AlertPolicy` a Decision that has passed `TemporalVerifier`, not a raw
FusionEngine output. A raw `Action.NOTIFY_CONTACTS` at p >= 0.90 is deliberately
not accepted as a first-tick notification: the policy restarts at `CHECK_IN`
so the user-cancellable ladder is never skipped.

| From | Condition | To | Action |
| --- | --- | --- | --- |
| IDLE / CANCELLED | verified `CHECK_IN` Decision | CHECK_IN | `CHECK_IN` |
| CHECK_IN | no response for 20 s | SILENT_LOCAL_ALERT | `SILENT_LOCAL_ALERT` |
| SILENT_LOCAL_ALERT | no response for 15 s and p >= 0.90 | NOTIFY_CONTACTS | `NOTIFY_CONTACTS` |
| pending stage | p <= cancel or `NORMAL` | CANCELLED | `NONE` (false alarm) |
| pending stage | `cancelFalseAlarm()` | CANCELLED | `NONE` |
| CHECK_IN | `acknowledgeCheckIn()` | IDLE | `NONE` |
| NOTIFY_CONTACTS / MANUAL_SOS | low evidence | latched | existing alert action |
| any | `Action.MANUAL_SOS` input | MANUAL_SOS | `MANUAL_SOS` (immediate) |

Callers supply `nowMs` from a single monotonic clock; the policy itself starts
no timers and performs no I/O. `SILENT_LOCAL_ALERT` is local-only: contacts are
notified only after both timeouts and the 0.90 floor.
