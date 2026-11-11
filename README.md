# Human BlackBox — Edge AI Emergency Detection

An on-device emergency detection system: three sensor modalities (audio, motion,
vision) are scored independently, fused into one decision, and the last 60
seconds of evidence is kept encrypted on the device. When an emergency is
detected the user gets a **15-second cancellable countdown** before any alert
leaves the device.

This repository is a working replication of the architecture, built by eight
senior specialists working in parallel under a shared contract.

## Pipeline

```
 mic ──► AudioDetector (YAMNet)  ─┐
 IMU ──► MotionDetector (LSTM)   ─┼──► FusionStrategy ──► EMERGENCY?
 cam ──► VisionDetector (YOLOv8n)─┘         │                  │
                                            │                  ▼
                                  RollingBuffer (60 s,     AlertSender
                                  encrypted)  ◄─────────  consent + 15 s
                                            │             countdown
                                            ▼                  │
                                     EvidenceStore ◄───────────┘
```

Full diagram and failure modes: [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md).

## Safety guarantees

These are enforced in code and covered by tests, not just documented:

1. **Visible recording indicator** — `UserConsentManager.recording_indicator_visible`
   is true whenever any sensor is capturing.
2. **Explicit, revocable consent** — capture refuses to start without a
   per-sensor grant; `revoke_consent()` stops it.
3. **Cancellable countdown** — `AlertSender.send()` runs a 15-tick countdown and
   returns `False` the moment `cancel_user_countdown()` is called.
4. **Raw media stays on-device** — the alert payload carries hashes and
   metadata only; buffers and evidence are encrypted at rest.

## Modules

| Module | Responsibility |
|--------|----------------|
| `modules/audio_detector.py` | YAMNet audio scoring, heuristic fallback |
| `modules/motion_detector.py` | LSTM motion scoring, freefall/impact |
| `modules/vision_detector.py` | YOLOv8-nano vision scoring, heuristic fallback |
| `modules/fusion.py` | Confidence-gated weighted fusion |
| `modules/rolling_buffer.py` | 60 s encrypted ring buffer |
| `modules/alert_sender.py` | Consent-gated alert with cancellable countdown |
| `modules/user_consent.py` | Per-sensor consent + recording indicator |
| `modules/evidence_store.py` | Encrypted per-session evidence files |
| `modules/pipeline.py` | End-to-end wiring, safety audit |
| `modules/types.py` | Shared contract data types (read-only) |

## Quick start

```bash
pip install numpy pyyaml pytest

# configuration
python -c "from config import load_config; print(load_config()['fusion'])"

# full test suite
python -m pytest tests/ -v

# swarm barrier (solo stage status + suite report)
python swarm/barrier.py
```

No TensorFlow, PyTorch or ultralytics install is required: each detector loads
its model when present and otherwise uses a deterministic signal-processing
fallback, recorded as `mode: heuristic` in `raw_features` and documented in
[`docs/ASSUMPTIONS.md`](docs/ASSUMPTIONS.md).

## Configuration

All thresholds, fusion weights, the 60 s buffer window, the 15 s countdown and
the safety flags live in [`config/default.yaml`](config/default.yaml). No magic
numbers in the modules.

## Swarm process

Eight specialists, one territory each, governed by
[`docs/CONTRACTS.md`](docs/CONTRACTS.md): solo stage → barrier (all eight
`swarm/done/*.md` present, full suite green) → four roundtable rounds
(judge, consolidate, jointly modify, re-judge) → sign-off (≥ 6/8 SHIP) →
outside jury. Protocol: [`docs/ROUNDTABLE.md`](docs/ROUNDTABLE.md); live state:
[`swarm/STATE.md`](swarm/STATE.md).
