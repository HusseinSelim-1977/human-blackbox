# Model Cards — Human BlackBox v2

**Status: contract/design cards only. No trained model, quantized artifact,
dataset, calibration fit or benchmark result exists in this checkout.** Every
metric below is `TBD / unmeasured`; thresholds shown are source-code defaults,
not validated operating points.

> **Assistive tool only — not authoritative emergency detection.** This system
> must not be used as the sole basis for calling emergency services. No automatic
> emergency call is implemented or authorized; any future calling integration
> requires explicit configuration, user confirmation and legal review.

## Shared contract

All v2 modality heads implement `ModalityClassifier` (`:core`) and return a
`Detection`: calibrated-in-name multi-class probabilities over seven learned
classes (`FALL`, `SUDDEN_IMPACT`, `MEDICAL_DISTRESS`, `VERBAL_DISTRESS`,
`PHYSICAL_ASSAULT`, `FIRE_OR_SMOKE`, `NORMAL`) plus a separate `unknownProbability`.
`Detection` also records modality, latency, model version and timestamp. A
detection is treated as uninformative when unknown probability is at least 0.5
or the top label is `NORMAL`. "Calibrated" here describes the output interface;
no calibration has been fitted or verified.

Common limitations: no operator-supplied data or model bundle is present; the
Kotlin sources have not been compiled; TFLite interpreter wiring, GPU/NPU
delegates and real sensor drivers are not integrated; the app screen is a UI
stub. No performance, accuracy or safety claim can be made.

Common training-data requirement: dataset manifests must declare `license`,
`provenance`, and `train`/`val`/`test` splits (`scripts/python/dataset_manifest.py`).
Paths, class maps, licenses and consent status are operator-supplied; nothing is
downloaded by this repository. Subject-wise/participant-disjoint splits are
required for evaluation; record per-source licensing and any restrictions on
redistribution of derived weights. Any classes beyond the shared seven require a
contract revision in `:core`.

---

## Audio — Perch/EfficientAT-style embedding + custom head

- **Intended use:** classify emergency-relevant sounds (distress vocalization,
  impact/shatter, alarm/siren cues) from a 16 kHz mono microphone stream.
- **Input:** `[1, 96, 64, 1]` float32 log-mel spectrogram (96 frames, 64 mel
  bands), derived from a 960 ms / 15,360-sample window with a 480 ms / 7,680
  sample hop.
- **Output:** `[1, 8]` float32 logits → seven class probabilities plus unknown.
- **Preprocessing:** PCM amplitude windowing, energy-based placeholder
  filterbank in `AudioClassifier.logMel`; backend export is expected to include
  `exports/audio_mel_filterbank.bin` with the exact training filterbank. Both
  INT8 and FP16 exports expose a float32 interface. The current filterbank is a
  placeholder and must match training exactly before any measurement.
- **Training data:** operator-supplied, licensed audio with emergency/non-emergency
  labels, explicit recording consent and speaker-disjoint splits. No audio
  dataset, license or manifest is included.
- **Calibration:** softmax over logits; no temperature/conformal fit, no
  threshold validation. Baseline Python config uses a 0.50 score threshold with
  YAMNet heuristics; it is not transferable to the v2 head.
- **Limitations:** noisy or overlapping audio, unseen languages/accents, mic
  placement, clipping and the placeholder mel table; 960 ms window adds
  intrinsic detection delay.
- **Privacy:** inference is on-device; raw PCM remains in the encrypted 60 s
  rolling buffer; upload requires separate explicit consent.
- **Metrics:** P/R/F1, recall at fixed FP rates, false alarms/hour, latency,
  model size, RAM/CPU use: **TBD / unmeasured**.

## Motion — TCN → GRU → temporal attention

- **Intended use:** recognize falls, sudden impact and abnormal motion from IMU.
- **Input:** `[1, 32, 9]` float32, 32 timesteps at 100 Hz (320 ms). Nine features
  per step: linear acceleration xyz; gyro xyz; `|a|`; `|g|`; gravity-removed `|a|`.
  Training export stacks several windows to cover a 2–5 s context.
- **Output:** `[1, 8]` float32 logits → seven class probabilities plus unknown.
- **Preprocessing:** low-pass gravity estimate (α=0.8), linear acceleration,
  outlier clipping to ±32, gap interpolation in the training path. Android IMU
  source currently supplies accelerometer only (gyro values are zero), so the
  declared nine-feature input cannot be populated end to end until gyro capture
  and window stacking are wired.
- **Training data:** licensed IMU/fall datasets with subject-wise splits and
  documented sensor placement/sampling rate. No dataset, license or manifest is
  included.
- **Calibration:** softmax; no fitted calibration. Baseline Python uses a 0.65
  normalized activity threshold plus freefall/impact heuristics; not a v2
  operating point.
- **Limitations:** device orientation/placement, sensor saturation, slow falls,
  non-fall impacts, short 320 ms runtime window versus longer training context,
  missing gyro signal in current capture.
- **Privacy:** IMU samples stay on-device in encrypted evidence; no raw upload
  without consent.
- **Metrics:** per-class P/R/F1, confusion matrix, missed-event rate, latency,
  model size, RAM/CPU use: **TBD / unmeasured**.

## Vision — YOLO11-nano detector + pose keypoints + temporal head

- **Intended use:** infer fall/assault/distress cues from RGB frames with pose
  geometry; user-disableable.
- **Inputs:** detector and pose each `[1, 320, 320, 3]` float32 RGB in [0,1];
  temporal head `[1, 40]` float32 (17 smoothed xy keypoints plus six normalized
  wrist/hip/head transition deltas).
- **Outputs:** detector boxes `[1, 20, 6]`, pose `[1, 51]` keypoints
  (x, y, score), final `[1, 8]` logits → seven class probabilities plus unknown.
- **Preprocessing:** ARGB→RGB normalization from CameraX frames downscaled to
  320×320; keypoint smoothing (0.65) and frame-to-frame deltas; target 5–15 FPS
  with dynamic frame skipping. No optical flow.
- **Training data:** operator-supplied, licensed image/video data with pose and
  event labels, shooting-consent provenance, subject-disjoint splits, and
  documented lighting/occlusion coverage. No dataset, license or manifest is
  included.
- **Calibration:** softmax; no fitted calibration. Baseline Python uses a 0.45
  YOLOv8n threshold and COCO-class heuristics; not a v2 operating point.
- **Limitations:** identifiable imagery, low light, occlusion, crowded scenes,
  privacy-sensitive locations; detector/pose/head integration is unbuilt and
  quantized artifacts are absent.
- **Privacy:** frames processed on-device; raw frames stay in encrypted evidence
  unless the user explicitly consents to upload; per-modality kill switch in
  `:data` defaults to enabled but must be presented to the user before capture.
- **Metrics:** per-class P/R/F1, confusion matrix, recall at fixed FP rates,
  FPS, latency, model size, RAM/CPU/GPU/NPU use: **TBD / unmeasured**.

## Fusion — learned explainable multimodal temporal fusion

- **Intended use:** combine recent per-modality detections into one `Decision`
  with an emergency probability, event type, explanation and recommended action
  (`NONE`, `CHECK_IN`, `SILENT_LOCAL_ALERT`, `NOTIFY_CONTACTS`, `MANUAL_SOS`).
- **Declared input:** `[1, 8, 27]` float32: eight detection ticks (~4 s at 2 Hz);
  declared 27 features per tick = 3 × 8 class probabilities (including unknown)
  + 3 metadata (presence, confidence, modality-dropout mask). Missing modalities
  are zeroed and masked.
- **Declared output:** `[1, 11]` float32 = emergency probability, seven type
  probabilities, and three modality contributions.
- **Contract anomaly (blocking):** `FusionEngine.decide` currently writes four
  metadata values per modality (11 values per modality, 33 per tick) into a
  `8 × 27` array, so the serialized input does not match the declared shape and
  overruns the buffer. This must be reconciled with the matrix contract before
  compilation or evaluation; no source change was made in this documentation pass.
- **Preprocessing:** tick history assembled in `:core` contract; hysteresis
  requires three consecutive high ticks (default threshold 0.72) or three low
  ticks (cancel 0.40); a 20 s check-in delay is a code default; emergency
  probability ≥ 0.90 recommends contact notification. Two-stage action and
  manual SOS bypass are defined in code, but the app button is an unwired stub.
- **Training data:** operator-supplied synchronized multimodal event logs with
  labels, modality-dropout simulation and subject-wise splits. No data/manifest.
- **Calibration:** probabilities are clamped, not calibrated; thresholds are
  defaults, not fitted. No conformal/policy calibration is implemented.
- **Limitations:** contract mismatch above; no online calibration; explanations
  are unvalidated; manual SOS bypasses every model and threshold, so it must
  never be represented as model output.
- **Privacy:** fusion consumes probabilities, not raw media; decisions and
  minimal metadata are eligible for alerts; raw evidence remains on-device.
- **Metrics:** per-class P/R/F1, confusion matrix, recall at fixed FP rates,
  false alarms/hour, missed-event rate, detection-to-alert latency, end-to-end
  latency, hysteresis impact: **TBD / unmeasured**.

## Personalization — design-only, not implemented in v2

- **Intended use:** adapt thresholds/heads per user while keeping raw data local;
  described in [`TIER3-PERSONALIZATION.md`](./TIER3-PERSONALIZATION.md).
- **Inputs/outputs/tensor shapes:** **TBD**; no Kotlin module, artifact or
  interface exists. The design proposes numeric feature vectors and per-class
  prototypes/residual scales rather than stored raw frames; exact dimensions
  depend on the chosen base head and must be fixed before implementation.
- **Preprocessing:** proposed prototype replay with class means plus small
  residual noise, input type guards and bounded replay memory; none is shipped.
- **Training data:** on-device user events only after explicit consent; optional
  federated updates require a separate `federation` consent scope. Data licenses
  and retention policy are operator/legal decisions, unresolved.
- **Calibration:** adaptation is only meaningful against an existing calibrated
  threshold/conformal coverage signal (Tier 1 design); that signal is not
  implemented or measured.
- **Limitations:** design record only; drift, catastrophic forgetting, replay
  privacy and federation consent need verification before any trial.
- **Privacy:** local prototypes, no raw uploads by default; explicit consent
  required for any federation or evidence sharing.
- **Metrics:** all **TBD / unmeasured**.
