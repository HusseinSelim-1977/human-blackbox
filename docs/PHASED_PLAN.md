# Phased Plan — Skeleton to Field Testing

**Status: plan only.** All 11 Gradle modules have build files/source skeletons,
but v2 has not been compiled, run or benchmarked. The baseline Python pipeline
remains the only runnable implementation. No phase below is marked complete.

> **Assistive tool only — not authoritative emergency detection.** No automatic
> emergency calls are implemented or authorized without explicit configuration
> and legal review.

Cross-phase rules: no downloads, no invented results, no claim of measurement
without a recorded command/environment; every unmet input is a hard blocker;
safety gates (visible indicator, revocable consent, cancellable countdown,
on-device raw media) are checked at every phase gate.

## Phase 1 — Buildable skeleton and contract reconciliation

**Goal:** make the existing v2 skeleton compile and pass unit tests with a real
toolchain.
- Supply Android SDK, Gradle wrapper/executable, JDK 17 and verify plugin IDs in
  `settings.gradle.kts` against the installed toolchain.
- Reconcile `FusionEngine` tensor contract: declared `[1, 8, 27]` versus 33
  values written per tick in source; agree the feature layout with `:core` and
  fix in the owning source module (documentation pass made no code changes).
- Confirm 11-module dependency graph (`./gradlew printModules`, assemble, unit
  tests, lint) and restore the Python suite as a separate runnable path.
- **Gate:** all modules compile; unit tests and lint pass; fusion shape
  mismatch resolved with a test. **Blockers:** SDK/wrapper absent; plugin
  validity unverified; fusion contract inconsistency.

## Phase 2 — Model artifacts, datasets and licenses

**Goal:** replace placeholder inference with licensed, reproducible artifacts.
- Supply dataset manifests with `license`, `provenance`, `train`/`val`/`test`
  splits; validate with `scripts/python/dataset_manifest.py`.
- Train/export audio, motion, vision, fusion heads and the exact audio
  mel filterbank via operator-supplied TensorFlow tooling; quantize per modality
  and record conversion commands/checksums.
- Define the class map against `:core` `EVENT_CLASSES` and record provenance in
  [`MODEL_CARDS.md`](./MODEL_CARDS.md).
- **Gate:** artifacts load in TFLite; all licenses attached; no raw dataset
  redistributed without permission. **Blockers:** no datasets, licenses, model
  files, TensorFlow environment or conversion results in the checkout.

## Phase 3 — Sensors, consent UI and evidence plumbing

**Goal:** wire platform capture behind explicit consent.
- Implement audio (AudioRecord) and vision (CameraX) flows in `:sensors`,
  supply gyro alongside accelerometer at 100 Hz, and add visible recording
  indicator.
- Connect `PrivacySettings` kill switches and evidence-upload consent to the
  UI; default upload to false.
- Wire `SecureEvidenceStore` rolling 60 s snapshot, key lifecycle/deletion and
  event-id handling.
- **Gate:** consent denial prevents capture; indicator verified; evidence files
  are encrypted and user-deletable; sensor formats match
  [`ARCHITECTURE.md`](./ARCHITECTURE.md). **Blockers:** foreground service,
  permissions and sensor drivers not implemented; no target device.

## Phase 4 — On-device inference and local safety behavior

**Goal:** run the four heads and inspect decisions without cloud dependence.
- Load TFLite interpreters in `:ml-*`; verify audio `[1,96,64,1]`, motion
  `[1,32,9]`, vision `[1,320,320,3]` and fusion `[1,8,27]` inputs at runtime.
- Implement two-stage action, hysteresis tick counters and manual SOS bypass
  behind the app UI; keep manual SOS independent of model confidence.
- Add unit tests with synthetic inputs and record per-modality latency using
  `:testing` harness; no thresholds are tuned from unpublished data.
- **Gate:** all tensor contracts pass runtime tests; offline detection produces
  explanations; cancel path tested. **Blockers:** model artifacts (Phase 2),
  sensors (Phase 3), unwired SOS button.

## Phase 5 — Alerts, cloud boundary and privacy verification

**Goal:** connect minimal metadata alerts and consent-gated evidence upload.
- Wire `FirestoreAlertGateway` and FCM; keep payload limited to event type,
  confidence, timestamp, coarse location, device status and recommended action.
- Wire `ConsentedEvidenceUploader` to `PrivacySettings`; require consent
  predicate at call time; never upload raw media by default.
- Add Firebase Auth dependency and sign-in/authorization flow if the operator
  requires authenticated alerts; author cloud-only Docker/Kubernetes templates
  and secret references per [`DEPLOYMENT_GUIDE.md`](./DEPLOYMENT_GUIDE.md).
- **Gate:** offline behavior unchanged; alert payload contains no raw media;
  consent denial blocks upload; rules reviewed. **Blockers:** no Firebase
  config, no Auth dependency, no cloud components or secrets.

## Phase 6 — Calibration, evaluation and release candidates

**Goal:** establish measured operating points before any field trial.
- Fit and record calibration/temperature or conformal thresholds on operator
  data; keep hard-coded defaults only as uncalibrated baselines.
- Execute [`EVALUATION_PLAN.md`](./EVALUATION_PLAN.md) on device classes:
  baseline Python, upgraded v2 and ablations; record commands, environment,
  splits and per-class metrics with no claimed results until measured.
- Run privacy/permission audit, 10-minute thermal/battery soak and crash log
  review; publish numbers with provenance.
- **Gate:** false-alarm and latency budgets agreed and met; calibration
  diagnostics recorded; safety audit green; no unresolved high-severity defects.
  **Blockers:** no calibrated artifacts, no hardware, no labeled test data.

## Phase 7 — Supervised field testing and limited rollout

**Goal:** trial with consenting participants under an approved protocol.
- Recruit under institutional review/legal approval; written notice that the
  tool is assistive and not authoritative; no automatic emergency calls.
- Deploy to a limited device cohort; collect incident logs, false alarms,
  missed events and user cancellations with consent-based retention.
- Review after each trial block; stop on any safety-gate regression; document
  observed conditions and failures.
- **Gate:** protocol approval, incident review, privacy review and explicit go
  decision before expanding. **Blockers:** all of Phases 1–6 plus hardware,
  participants, ethics/legal approval and operator cloud infrastructure.
