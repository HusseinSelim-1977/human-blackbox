# Implementation Plan — Human BlackBox v2

**Status: guidance only.** All 11 Gradle modules have build files and source
skeletons, but v2 has not been compiled, run, benchmarked or deployed here.
There is no Gradle wrapper/SDK, dataset with licenses, model artifact, Firebase
configuration, target hardware or measured result in this checkout. Every
metric is **TBD / unmeasured** until an operator supplies inputs and records a
command, environment and provenance with the result.

> **Assistive tool only — not authoritative emergency detection.** No automatic
> emergency calls are implemented or authorized. Any such feature needs explicit
> configuration, user confirmation and legal review.

Scope: turn the skeleton into a buildable, testable on-device implementation
without downloading data or inventing results. Design references:
[`ARCHITECTURE.md`](./ARCHITECTURE.md), [`PHASED_PLAN.md`](./PHASED_PLAN.md),
[`EVALUATION_PLAN.md`](./EVALUATION_PLAN.md), [`MODEL_CARDS.md`](./MODEL_CARDS.md),
[`DEPLOYMENT_GUIDE.md`](./DEPLOYMENT_GUIDE.md),
[`../scripts/README.md`](../scripts/README.md).

## 1. Exact file tree (checkout snapshot)

Module source files are actively being filled in by parallel workstreams; new
files inside the listed module directories are additive and do not change the
module boundaries below. Generated, VCS and cache directories are omitted.

```text
.
├── README.md
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── .gitignore
├── config/
│   ├── __init__.py
│   └── default.yaml
├── core/
│   ├── build.gradle.kts
│   └── src/main/kotlin/com/humanblackbox/core/
│       ├── EventModels.kt
│       ├── FeatureFlags.kt
│       ├── ModelStatusRegistry.kt
│       ├── PrivacyAuditLog.kt
│       └── RuntimeConfig.kt
├── sensors/
│   ├── build.gradle.kts
│   └── src/main/kotlin/com/humanblackbox/sensors/SensorPipeline.kt
├── ml-audio/
│   ├── build.gradle.kts
│   └── src/main/kotlin/com/humanblackbox/mlaudio/AudioClassifier.kt
├── ml-motion/
│   ├── build.gradle.kts
│   └── src/main/kotlin/com/humanblackbox/mlmotion/MotionClassifier.kt
├── ml-vision/
│   ├── build.gradle.kts
│   └── src/main/kotlin/com/humanblackbox/mlvision/VisionClassifier.kt
├── ml-fusion/
│   ├── build.gradle.kts
│   └── src/main/kotlin/com/humanblackbox/mlfusion/FusionEngine.kt
├── data/
│   ├── build.gradle.kts
│   ├── src/main/AndroidManifest.xml
│   └── src/main/kotlin/com/humanblackbox/data/
│       ├── BlackBoxDatabase.kt
│       ├── BlackBoxDataRepository.kt
│       ├── DecisionLogEntity.kt
│       ├── EventHistoryEntity.kt
│       ├── KeyRotationManager.kt
│       ├── ModelStatusEntity.kt
│       ├── PrivacySettings.kt
│       └── SecureEvidenceStore.kt
├── cloud/
│   ├── build.gradle.kts
│   ├── src/main/AndroidManifest.xml
│   └── src/main/kotlin/com/humanblackbox/cloud/
│       ├── AlertRepository.kt
│       ├── ConsentedEvidenceUploadWorker.kt
│       ├── FcmTopicRegistrar.kt
│       ├── FirestoreAlertGateway.kt
│       └── MinimalAlertSyncWorker.kt
├── testing/
│   ├── build.gradle.kts
│   ├── src/main/AndroidManifest.xml
│   └── src/main/kotlin/com/humanblackbox/testing/
│       ├── BenchmarkHarness.kt
│       └── BenchmarkHelpers.kt
├── scripts/
│   ├── README.md
│   ├── build.gradle.kts
│   ├── python/
│   │   ├── augment.py
│   │   ├── benchmark.py
│   │   ├── convert_tflite.py
│   │   ├── dataset_manifest.py
│   │   ├── features.py
│   │   ├── train_all.py
│   │   └── validate_repo.py
│   └── src/main/kotlin/com/humanblackbox/scripts/Main.kt
├── app/
│   ├── build.gradle.kts
│   ├── src/main/AndroidManifest.xml
│   ├── src/main/kotlin/com/humanblackbox/app/
│   │   ├── LocalEventStore.kt
│   │   ├── MainActivity.kt
│   │   ├── ManualSosController.kt
│   │   └── Screens.kt
│   └── src/main/res/
│       ├── drawable/ic_sos_notification.xml
│       └── values/themes.xml
├── docs/
│   ├── ARCHITECTURE.md
│   ├── ASSUMPTIONS.md
│   ├── CONTRACTS.md
│   ├── DEPLOYMENT_GUIDE.md
│   ├── EVALUATION_PLAN.md
│   ├── FUSION_STRATEGY_REPORT.md
│   ├── IMPLEMENTATION_PLAN.md
│   ├── MODEL_CARDS.md
│   ├── MODALITY_CALIBRATION.md
│   ├── PHASED_PLAN.md
│   ├── ROADMAP.md
│   ├── ROUNDTABLE.md
│   ├── TIER1-CONFORMAL-FUSION.md
│   ├── TIER2-TEMPORAL-EVENT-FUSION.md
│   ├── TIER3-PERSONALIZATION.md
│   └── TIER4-DEPLOYMENT-PRIVACY.md
├── modules/                      # runnable Python baseline
│   ├── __init__.py
│   ├── alert_sender.py
│   ├── audio_detector.py
│   ├── evidence_store.py
│   ├── fusion.py
│   ├── fusion_enhanced.py
│   ├── fusion_report.md
│   ├── modality_types.py
│   ├── motion_detector.py
│   ├── pipeline.py
│   ├── rolling_buffer.py
│   ├── types.py
│   ├── ui_notifiers.py
│   ├── user_consent.py
│   └── vision_detector.py
├── tests/                        # Python baseline suite
│   ├── test_alert_sender.py
│   ├── test_audio_detector.py
│   ├── test_config.py
│   ├── test_evidence_store.py
│   ├── test_fusion.py
│   ├── test_fusion_ambient_boost.py
│   ├── test_fusion_temporal.py
│   ├── test_integration_e2e.py
│   ├── test_motion_detector.py
│   ├── test_pipeline.py
│   ├── test_rolling_buffer.py
│   └── test_vision_detector.py
├── swarm/                        # baseline process records and barrier
│   ├── STATE.md
│   ├── barrier.py
│   ├── verify_subagents.js
│   ├── done/
│   │   ├── alert-consent.md
│   │   ├── audio-detector.md
│   │   ├── buffer-crypto.md
│   │   ├── evidence-store.md
│   │   ├── fusion-engine.md
│   │   ├── integration-safety.md
│   │   ├── motion-detector.md
│   │   └── vision-detector.md
│   ├── requests/
│   │   ├── alert-consent.md
│   │   ├── audio-detector.md
│   │   ├── buffer-crypto.md
│   │   ├── evidence-store.md
│   │   ├── fusion-engine.md
│   │   ├── integration-safety.md
│   │   ├── motion-detector.md
│   │   ├── roundtable-review.md
│   │   └── vision-detector.md
│   ├── results/barrier.md
│   ├── reviews/
│   │   ├── consolidated.md
│   │   └── coordinator-judge.md
│   └── votes/
│       ├── alert-consent-signoff.md
│       ├── audio-detector-signoff.md
│       ├── buffer-crypto-signoff.md
│       ├── evidence-store-signoff.md
│       ├── fusion-engine-signoff.md
│       ├── integration-safety-signoff.md
│       ├── motion-detector-signoff.md
│       └── vision-detector-signoff.md
├── fusion_strategy_report.md
└── test_fusion_ambient.py
```

The v2 runtime is the Kotlin modules above. `modules/` and `tests/` are the
still-runnable Python baseline; they are kept as the comparison implementation,
not as v2 sources. `backup/` is historical and is not part of the build.

## 2. Module dependency order

Build bottom-up; Gradle resolves this automatically, and `./gradlew printModules`
lists the 11 included projects once a wrapper is supplied.

```text
:core                         shared contracts, config, flags, audit log
  ├── :sensors                (:core) capture Flows, service, wake locks
  ├── :ml-audio               (:core) audio head
  ├── :ml-motion              (:core, :sensors) motion head
  ├── :ml-vision              (:core, :sensors) vision head
  ├── :ml-fusion              (:core) temporal fusion and hysteresis
  ├── :data                   (:core) Room, DataStore, encrypted evidence
  │     └── :cloud            (:core, :data) FCM/Firestore/Storage gateways
  └── :testing                (:core) benchmark harness/helpers (test-only)
:app                          depends on all eight runtime libraries above
:scripts                      JVM helper, no Android/project dependencies
```

Practical order: `core → sensors → data → ml-audio/ml-motion/ml-vision/ml-fusion
→ cloud → testing → scripts → app`. `:scripts` is offline tooling and is never
a runtime dependency of `:app`.

## 3. Gradle, dependencies and permissions

- Root `settings.gradle.kts` declares Android application/library 8.5.2,
  Kotlin Android/JVM 2.0.20, Compose and serialization plugins 2.0.20, KSP
  `2.0.20-1.0.25`, Google Services 4.4.2 and GraalVM native 0.13.0 (apply
  false). Repositories are `google()` + Maven Central with
  `FAIL_ON_PROJECT_REPOS`; no repository may add its own.
- Android libraries compile against SDK 34, `minSdk 29`; the app targets SDK 34
  with application id `com.humanblackbox.humanblackbox.v2`, JDK 17, Compose.
  `gradle.properties` pins the SDK location and JVM/cache settings; these are
  environment assumptions, not verified capabilities.
- Key libraries: Kotlin coroutines 1.8.1, serialization-json 1.6.3, TFLite
  2.16.1 in each `:ml-*`, Room 2.6.1 with KSP in `:data`, DataStore 1.1.1,
  security-crypto 1.1.0-alpha06, WorkManager 2.9.0, Firebase messaging/
  Firestore/Storage in `:cloud`, Compose BOM 2024.09.02 in `:app`.
- Firebase plugin is applied conditionally when `app/google-services.json`
  exists; no credentials are in the repo. Cloud-only Docker/Kubernetes
  components are described in [`DEPLOYMENT_GUIDE.md`](./DEPLOYMENT_GUIDE.md)
  and are not present.
- App manifest declares `RECORD_AUDIO`, `CAMERA`, `ACCESS_FINE_LOCATION`,
  `ACCESS_COARSE_LOCATION`, `POST_NOTIFICATIONS` and `INTERNET`; all must be
  runtime-requested with a visible indicator and must not be granted
  implicitly. `INTERNET` is for the optional cloud path only;
  `usesCleartextTraffic="false"`. `:cloud` also declares `INTERNET`. No call
  permission and no automatic-call path exists. A foreground-service
  declaration and the gyro/capture plumbing remain Phase 3 work.
- There is no Gradle wrapper or installed SDK in this checkout; all Gradle
  commands below are instructions for an operator-supplied environment.

## 4. Interfaces to preserve

- `:core` `ModalityClassifier.classify(input: FloatArray): Detection` is the
  contract for audio/motion/vision heads. `Detection` carries modality,
  `ClassProbability` list, unknown probability, latency, model version and
  timestamp; `Decision` carries `EventType`, emergency probability,
  `Explanation`, `Action` and timestamp. `EvidenceBundle` is a locked 60 s
  encrypted snapshot. `EventType`/`EVENT_CLASSES` are the shared class map;
  `Action` covers `NONE`, `CHECK_IN`, `SILENT_LOCAL_ALERT`,
  `NOTIFY_CONTACTS`, `MANUAL_SOS`.
- Tensor contract to verify at runtime: audio `[1,96,64,1] → [1,8]`, motion
  `[1,32,9] → [1,8]`, vision `[1,320,320,3]` plus pose/temporal `[1,40] →
  [1,8]`, fusion `[1,8,27] → [1,11]`. The fusion input declared as 27 features
  per tick while 33 are written per tick is an open Phase 1 blocker; resolve it
  with a shape test in `:ml-fusion` before training an export against it.
- `:sensors` owns capture Flows; `:ml-*` own one interpreter/head each and
  return `Detection` only. `:ml-fusion` owns history, hysteresis and
  explanations. `:data` owns preferences, evidence encryption/key rotation and
  decision logs. `:cloud` owns metadata alert and consent-gated upload
  gateways. `:app` owns UI, permission prompts and the manual SOS bypass.
  `:testing` exposes `BenchmarkHarness`/`BenchmarkMeasure` for locally supplied
  inputs; it never claims a device result.

## 5. Decision state machine

```text
IDLE --explicit consent + visible indicator--> CAPTURING
CAPTURING --chunks appended to 60 s encrypted buffer--> SCORING
SCORING --Detection per available modality--> FUSING
FUSING --NORMAL / INDETERMINATE / <2 informative modalities--> IDLE
FUSING --EMERGENCY after hysteresis ticks--> COUNTDOWN
MANUAL_SOS --UI bypass of model thresholds--> COUNTDOWN
COUNTDOWN --user cancels--> DISMISSED (no payload leaves device)
COUNTDOWN --confirmed/elapsed--> ALERTED (local action + metadata only)
ALERTED --> EVIDENCE_LOCKED (snapshot; upload only with separate consent)
ANY STATE --missing model / sensor error--> ERROR (no alert, surfaced to UI)
```

Offline operation keeps capture, inference, countdown and local alert intact;
only cloud delivery is deferred. Consent is checked at call time for both
capture and upload. No state transition may start recording without consent,
hide the indicator, skip the countdown for model-driven alerts, or upload raw
media by default.

## 6. Training and export commands (operator-supplied data only)

Nothing below downloads data or models. Paths prefixed `OPERATOR_SUPPLIED/`
must exist locally with a validated license; `BUILD/` is generated output.
TensorFlow tooling is external and absent from this checkout.

```bash
python3 scripts/python/validate_repo.py
python3 scripts/python/dataset_manifest.py OPERATOR_SUPPLIED/manifest.json

python3 scripts/python/features.py audio --input OPERATOR_SUPPLIED/pcm.npy --output BUILD/audio_mel.npy
python3 scripts/python/features.py imu --input OPERATOR_SUPPLIED/imu.npy --output BUILD/imu_windows.npy --steps 32 --sample-rate 100
python3 scripts/python/augment.py noise --input BUILD/audio_mel.npy --output BUILD/audio_mel_noisy.npy --seed 0 --snr-db 10
python3 scripts/python/augment.py time-mask --input BUILD/audio_mel.npy --output BUILD/audio_mel_masked.npy --seed 0 --max-steps 8
python3 scripts/python/augment.py imu-jitter --input BUILD/imu_windows.npy --output BUILD/imu_windows_jitter.npy --seed 0 --std 0.01

python3 scripts/python/train_all.py audio --manifest OPERATOR_SUPPLIED/manifest.json --output BUILD/audio_saved_model
python3 scripts/python/train_all.py motion --manifest OPERATOR_SUPPLIED/manifest.json --output BUILD/motion_saved_model
python3 scripts/python/train_all.py vision --manifest OPERATOR_SUPPLIED/manifest.json --output BUILD/vision_saved_model
python3 scripts/python/train_all.py fusion --manifest OPERATOR_SUPPLIED/manifest.json --output BUILD/fusion_saved_model
python3 scripts/python/train_all.py personalization --manifest OPERATOR_SUPPLIED/manifest.json --output BUILD/personalization_saved_model  # design-only head; no runtime module

python3 scripts/python/convert_tflite.py audio --saved-model BUILD/audio_saved_model --output BUILD/audio_int8.tflite --mode int8
python3 scripts/python/convert_tflite.py motion --saved-model BUILD/motion_saved_model --output BUILD/motion_int8.tflite --mode int8
python3 scripts/python/convert_tflite.py vision --saved-model BUILD/vision_saved_model --output BUILD/vision_float16.tflite --mode float16
python3 scripts/python/convert_tflite.py fusion --saved-model BUILD/fusion_saved_model --output BUILD/fusion_int8.tflite --mode int8
```

Record every command, dataset manifest hash, model checksum and quantization
mode beside the artifact. Do not put generated artifacts or credentials in the
repository.

## 7. Test and benchmark plan

```bash
python3 -m compileall -q scripts/python
python3 scripts/python/validate_repo.py           # layout, links, no invented metric claims
python3 -m pytest tests/ -q                       # baseline suite; reported count is not a v2 result

# Only after SDK/wrapper and toolchain are supplied:
./gradlew printModules assemble test lint
./gradlew :testing:test
```

1. Unit tests: tensor shapes and unknown-class handling for each head, fusion
   history/hysteresis and cancel ticks, evidence encryption/deletion, consent
   denial, countdown cancellation, manual SOS bypass, offline queueing.
2. Instrumented tests when hardware exists: permission prompts, visible
   indicator, 60 s buffer snapshot, local notification path, no raw media in
   alert payload, upload blocked without consent.
3. Benchmarks: `testing/BenchmarkHarness` times locally supplied inputs only;
   export through `BenchmarkFormats.toCsvRow()` with a source label. Summarise
   the exported CSV with `scripts/python/benchmark.py --input ... --threshold-ms
   "$BUDGET_MS" --criterion p95 --source-label "..." --format json`. `$BUDGET_MS`
   is an operator-approved budget; no default is asserted here. p95 is
   nearest-rank in both the Kotlin and Python helpers.
4. Label every number as a supplied measurement with date, hardware/OS,
   manifest hash and model checksum. Without that provenance the result stays
   **TBD / unmeasured**. Safety gates are reported separately from accuracy;
   no result is published from this checkout.

## 8. Phased rollout (gates and blockers)

| Phase | Work | Gate / blocker |
|---|---|---|
| 0 | Field validation (`validate_repo.py`), link and claim checks | Passes offline; no build or measurement implied |
| 1 | Supply SDK/wrapper/JDK; reconcile fusion shape; compile all modules, unit tests, lint | All 11 modules build and shape test passes / toolchain and shape mismatch |
| 2 | Supply licensed datasets, train and export artifacts with recorded checksums | Artifacts load in TFLite and licenses attached / no data, licenses or TensorFlow |
| 3 | Wire sensors, permissions, visible indicator, consent UI, encrypted evidence | Consent denial blocks capture; evidence deletable / no device or service plumbing |
| 4 | Load interpreters, implement detection/hysteresis/SOS and local alert | Tensor contracts and cancel path tested / depends on Phases 1–3 |
| 5 | Wire FCM/Firestore, consent-gated upload and cloud boundary | Payload metadata-only; upload denied without consent / no Firebase config or rules |
| 6 | Calibrate on operator data; execute [`EVALUATION_PLAN.md`](./EVALUATION_PLAN.md); soak and privacy audit | Budgets agreed and met with provenance / no hardware or labeled test split |
| 7 | Supervised limited field trial with approval and incident review | Explicit go decision; stop on safety regression / ethics, legal and participants |

No phase is marked complete in this checkout. See
[`../README.md`](../README.md) for the top-level caveats and
[`PHASED_PLAN.md`](./PHASED_PLAN.md) for the full blocker list.
