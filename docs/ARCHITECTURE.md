# Architecture — Human BlackBox Emergency Pipeline

## Overview

Human BlackBox is an on-device emergency detection system. Sensor data from
the microphone, IMU and camera is scored independently by one detector per
modality, the scores are combined by a fusion strategy, and the rolling
evidence buffer keeps the last 60 seconds of context. When fusion decides
`EMERGENCY`, the alert sender runs a user-cancellable 15-second countdown
before any alert leaves the device. Raw media never leaves the device unless
the user explicitly opts in.

`modules/pipeline.py` is the integration seam: it owns no models or sensor
drivers, and receives every collaborator through its constructor so it can be
tested deterministically with stubs.

## Flow

```
 microphone ──> AudioDetector ──┐
 IMU        ──> MotionDetector ─┼──> FusionStrategy ──> ModalityScore
 camera     ──> VisionDetector ─┘          │
                                           │ EMERGENCY
                                           v
                                   AlertSender.send()
                                           │
                             15 s user-cancellable countdown
                                           │
                                           v
                                   alert transmitted
        ┌──────────────────────────────────────────────┐
        │ RollingBuffer: last 60 s of chunks + scores  │
        │ (encrypted, on-device evidence)              │
        └──────────────────────────────────────────────┘
```

Every chunk/score pair is appended to the rolling buffer before fusion, so
the evidence window always includes the samples that triggered a decision.
The buffer is passed to `AlertSender.send()` as evidence and is not uploaded
by default.

## Safety gates (non-negotiable)

| # | Gate | Enforcement in code | Config key |
|---|------|---------------------|------------|
| 1 | Visible recording indicator whenever recording | `UserConsentManager.start_recording()` turns it on; `EmergencyPipeline.safety_audit()` reports it | `safety.recording_indicator` |
| 2 | Explicit, revocable mic/camera consent before recording | `start_capture()` delegates to `start_recording()`, which refuses without consent | `safety.require_mic_consent`, `safety.require_camera_consent` |
| 3 | User-cancellable 15 s countdown before any alert | `AlertSender.send()` runs the countdown; `cancel_user_countdown()` aborts it | `alert.countdown_sec` |
| 4 | Raw media stays on-device unless explicit opt-in | Alert payload carries hashes/metadata only; sharing is opt-in | `safety.raw_media_on_device`, `safety.share_evidence_default` |

`safety_audit()` returns a boolean per gate (`recording_indicator`,
`mic_consent`, `camera_consent`, `countdown_cancellable`,
`raw_media_on_device`) so the UI or a supervisor can verify the guarantees at
runtime.

## Data flow

1. A sensor chunk (`AudioChunk`, `ImuSample`, `Frame`) arrives from capture.
2. `EmergencyPipeline.process()` runs only the detectors whose chunk is not
   `None`; each returns a `ModalityScore`.
3. Each `(chunk, score)` pair is appended to the `RollingBuffer`.
4. `FusionStrategy.decide()` combines all scores from this round using the
   configured weights (AUDIO 0.35, MOTION 0.40, VISION 0.25), the fusion
   threshold (0.65) and the two-modality requirement.
5. The fused `ModalityScore` is stored in `last_decision` and returned.
6. If the decision is `EMERGENCY`, `AlertSender.send()` is called exactly once
   for that round. A cancelled or failed send returns `False` but does not
   alter the decision returned to the caller.

## Timing

- **Evidence buffer:** 60 s (`buffer.size_sec`), encrypted at rest, written on
  every processed chunk and loaded back on startup.
- **Countdown:** 15 s (`alert.countdown_sec`) between the emergency decision
  and transmission, cancellable at any point.
- **Inference budget:** 100 ms per round (`inference_timeout_ms`), 16-item
  batches (`batch_size`).

## Failure modes

| Failure | Behaviour |
|---------|-----------|
| Detector raises or model missing | The exception propagates; the round produces no decision. Detection must be fixed rather than silently degraded. |
| Detector unavailable for a round | Its chunk is `None`, so it is skipped; fusion uses the remaining modalities. With fewer than two modalities, fusion returns `NORMAL`/`INDETERMINATE` per its contract. |
| No sensor input at all | Pipeline returns `INDETERMINATE` without calling fusion or the alert sender. |
| Fusion unavailable | Exception propagates; no alert is sent, because no decision was made. |
| Alert send returns `False` | Treated as user cancellation: no retry storm, decision is still returned and logged. |
| Consent missing | `start_capture()` returns `False` and the indicator stays off; `safety_audit()` reports the failing gate. |
| Countdown cancelled | `AlertSender.send()` returns `False`; no payload leaves the device. |
| Buffer write failure | `process()` surfaces the error; evidence integrity is not silently ignored. |
| Device offline | Detection, buffering and countdown are fully local; only transmission is deferred/retried per `alert.max_retries`. |

## Module boundaries

- `modules/types.py` — shared types and constants (coordinator-owned).
- `modules/pipeline.py` — this integration layer (owned by integration-safety).
- Detectors, fusion, buffer, alerting and consent live in their own modules and
  are injected; the pipeline imports only `modules.types` at module level.
