# Human BlackBox — Edge AI Emergency Detection

An on-device emergency detection system: three sensor modalities (audio, motion,
vision) are scored independently, fused into one decision, and the last 60
seconds of evidence is kept encrypted on the device. When an emergency is
detected the user gets a **15-second cancellable countdown** before any alert
leaves the device.

This repository is a working replication of the architecture, built by eight
senior specialists working in parallel under a shared contract.

> **Assistive tool only — not authoritative emergency detection.** **No automatic
> emergency calls are implemented or authorized.** Any future calling/PSAP
> integration requires explicit configuration, user confirmation and legal
> review before use.

## v2 migration status

The Human BlackBox v2 multi-module project is present with build files and
source skeletons/wiring files for **all 11 modules** (`:core`, `:sensors`,
`:ml-audio`, `:ml-motion`, `:ml-vision`, `:ml-fusion`, `:data`, `:cloud`,
`:testing`, `:scripts`, `:app`) plus Python feature/training/conversion helpers.

**v2 remains unbuilt and unbenchmarked.** There is no Gradle wrapper/Gradle
executable, Android SDK, Firebase/FCM/Firestore/Auth configuration, trained or
quantized model bundle, dataset with licenses, target hardware or measured
result in this checkout. The Compose SOS screen is a UI stub rather than
production wiring; no cloud-only Docker/Kubernetes components or credentials
are present. The runnable implementation today remains the Python pipeline
below with heuristic fallbacks (baseline suite reported as 159 tests). Nothing
is downloaded and no result is invented by default. Before continuing, the
operator must supply the SDK/wrapper, dataset paths and licenses, model
artifacts, Firebase configuration/secrets, and target hardware.

Documentation for the unresolved v2 work:

| Document | Contents |
|---|---|
| [`docs/ARCHITECTURE.md`](./docs/ARCHITECTURE.md) | v2 module diagram, on-device flow, 60 s evidence buffer, local alert/FCM, consent-gated upload |
| [`docs/MODEL_CARDS.md`](./docs/MODEL_CARDS.md) | Audio, Motion, Vision, Fusion, Personalization cards; all metrics TBD/unmeasured |
| [`docs/DEPLOYMENT_GUIDE.md`](./docs/DEPLOYMENT_GUIDE.md) | Firebase placeholders, cloud-only components, privacy defaults, required inputs |
| [`docs/PHASED_PLAN.md`](./docs/PHASED_PLAN.md) | Phases 1–7, gates and blockers |
| [`docs/EVALUATION_PLAN.md`](./docs/EVALUATION_PLAN.md) | Metrics, splits and conditions; no results claimed |

## Pipeline

```text
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

Full diagram and failure modes:
[`docs/ARCHITECTURE.md`](./docs/ARCHITECTURE.md).

## Safety guarantees

These are enforced in the runnable Python baseline and covered by its tests,
not just documented; the v2 skeletons are not yet built or verified:

1. **Visible recording indicator** —
   `UserConsentManager.recording_indicator_visible`
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
| `modules/vision_detector.py` | YOLOv8-nano scoring, heuristic fallback |
| `modules/fusion.py` | Confidence-gated weighted fusion |
| `modules/rolling_buffer.py` | 60 s encrypted ring buffer |
| `modules/alert_sender.py` | Consent-gated alert with cancellable countdown |
| `modules/user_consent.py` | Per-sensor consent + recording indicator |
| `modules/evidence_store.py` | Encrypted per-session evidence files |
| `modules/pipeline.py` | End-to-end wiring, safety audit |
| `modules/modality_types.py` | Canonical shared contract types and constants |
| `modules/types.py` | Backwards-compatible re-export shim for `modality_types` |

## Quick start

```bash
pip install numpy pyyaml pytest

# configuration
python -c "from config import load_config; print(load_config()['fusion'])"

# full test suite (159 tests)
python -m pytest tests/ -q

# swarm barrier (solo stage status + suite report)
python swarm/barrier.py
```

No TensorFlow, PyTorch or ultralytics install is required: each detector loads
its model when present and otherwise uses a deterministic signal-processing
fallback, recorded as `mode: heuristic` in `raw_features` and documented in
[`docs/ASSUMPTIONS.md`](./docs/ASSUMPTIONS.md).

## Configuration

All thresholds, fusion weights, the 60 s buffer window, the 15 s countdown and
the safety flags live in [`config/default.yaml`](./config/default.yaml). No
magic numbers in the modules. `FusionStrategy` supports an optional ambient
Z-score boost: when runtime `ambient_mean`/`ambient_std` statistics are supplied
alongside `ambient_baselines.per_modality` feature lists, scores that deviate
from the rolling baseline are boosted and reported under
`raw_features["ambient_boosts"]`.

## Swarm process

Eight specialists, one territory each, governed by
[`docs/CONTRACTS.md`](./docs/CONTRACTS.md): solo stage → barrier (all eight
`swarm/done/*.md` present, full suite green) → four roundtable rounds
(judge, consolidate, jointly modify, re-judge) → sign-off (≥ 6/8 SHIP) →
outside jury. Protocol: [`docs/ROUNDTABLE.md`](./docs/ROUNDTABLE.md); live
state: [`swarm/STATE.md`](./swarm/STATE.md).
