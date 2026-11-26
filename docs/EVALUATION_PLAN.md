# Evaluation Plan — Human BlackBox v2

**No results are claimed in this document.** No v2 build, model artifact,
dataset, device hardware or benchmark run exists in this checkout. Everything
below is a protocol to execute once inputs are supplied. Recorded values must
include command, date, hardware/OS, dataset manifest hash, model checksums and
software versions; otherwise mark `TBD / unmeasured`.

> **Assistive tool only — not authoritative emergency detection.** Evaluation
> does not authorize automatic emergency calls; any such feature requires
> explicit configuration and legal review.

## 1. Objectives and comparisons

Compare, on identical splits and hardware:
- **Baseline:** current Python pipeline with heuristic fallbacks (159 tests are
  reported for that tree; that count is not a v2 result).
- **Upgraded:** v2 Kotlin modules with trained/quantized TFLite artifacts.
- **Ablations:** each modality removed in turn; fusion replaced by baseline
  weighted fusion; hysteresis disabled; personalization on vs off (if
  implemented); RGB vision replaced by no-vision or privacy-preserving sensor
  (if implemented); each quantization variant.

Report per-modality and fused results separately. Do not compare a heuristic
baseline run on one dataset with a learned model on another.

## 2. Metrics to report (all currently TBD / unmeasured)

- **Per-class precision, recall, F1** for each of the seven learned event
  classes plus the unknown/normal outcome; macro and weighted averages.
- **Confusion matrices** (counts and row-normalized) per model and ablation,
  including UNKNOWN and NORMAL as explicit outcomes.
- **Recall at fixed false-positive rates:** report recall at operator-agreed FP
  budgets (for example a fixed number of false alarms per 24 h, as proposed in
  [`ROADMAP.md`](./ROADMAP.md)); include the operating threshold used.
- **False alarms per hour** on long negative/ambient streams, with confidence
  intervals and the exact negative-stream composition.
- **Missed-event rate** (false negatives) per event class and by severity.
- **Detection-to-alert latency:** event occurrence → decision, decision →
  countdown start, countdown → local alert, countdown → cloud delivery (if
  configured); median, p95 and max.
- **End-to-end latency:** sensor capture → fused decision, and capture → alert;
  include all preprocessing and buffering.
- **Model size:** per-artifact and total flash footprint, including pose and
  temporal heads and the mel filterbank.
- **Resource use:** peak RAM/RSS, sustained CPU (cores and duty cycle), GPU/NPU
  delegate usage and fallback rate, battery drain per hour, device
  temperature/thermal throttling over a 10-minute soak.
- **Calibration diagnostics:** reliability curve, expected calibration error,
  conformal coverage if implemented, and threshold drift observations.
- **Robustness:** performance by condition (below), modality dropout and
  missing-sensor behavior; count of crashes and silent fallback activations.

## 3. Splits and conditions

- **Splits:** participant/subject-disjoint `train`/`val`/`test` for every
  dataset, with manifest license and provenance recorded. Test split is
  untouched by threshold tuning. Where possible, add cross-dataset transfer
  (train on one source, test on another) to expose lab-to-real degradation.
- **Conditions to stratify or report separately:** day/night lighting, indoor/
  outdoor, quiet/noisy audio, accents/languages, occlusion/crowding, camera
  disabled, mic disabled, gyro unavailable, device placement (pocket/hand/
  waist), low battery, offline vs connected, stationary/ambient hours.
- **Negative streams:** long ambient recordings with no target events are
  required for false-alarms/hour; do not derive this rate from a balanced test
  set.
- **Event windows:** define ground-truth onset and tolerance windows before
  evaluation; detection-to-alert timing is measured from that definition.

## 4. Execution sequence

1. Validate dataset manifests and record hashes; freeze class map and splits.
2. Verify all 11 modules build on the target device/toolchain (see
   [`PHASED_PLAN.md`](./PHASED_PLAN.md)); record any module that cannot run.
3. Run baseline Python suite and baseline inference on the frozen test split.
4. Run per-modality v2 inference, then fused decisions, then full alert path
   with countdown cancellation exercised.
5. Repeat for each ablation and quantization variant; repeat on at least one
   second device class if available.
6. Run 10-minute continuous soak for RAM, CPU, battery and thermal measurements;
   record device model and OS.
7. Store raw eval logs, derived tables and reproduction commands beside the
   results; update [`MODEL_CARDS.md`](./MODEL_CARDS.md) with measured values only.

## 5. Interpretation and stop conditions

- No threshold is chosen from the test split; report the tuning split used.
- A result is publishable only with its command, environment, manifest and
  model checksum; otherwise label it `TBD / unmeasured`.
- Safety gates are evaluated separately from accuracy: visible indicator,
  revocable consent, cancellable countdown, and no raw media leaving the device
  without consent. Any failure blocks field testing regardless of F1.
- If false alarms/hour or missed-event rate exceeds an operator-agreed budget,
  do not proceed to field trial; revisit calibration or sensor policy.

## 6. Current status

All metrics in this plan are **TBD / unmeasured**. No evaluation dataset,
license, model artifact, Gradle build or target hardware is present, and no
benchmark has been run in this checkout.
