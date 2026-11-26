# Scripts — offline operator tooling

All CLIs in `python/` run locally on **operator-supplied** files. They do not
download datasets/models, contact a network, run an Android device, or produce
a measured v2 result. Replace every `OPERATOR_SUPPLIED/...` path with a local
file that has been checked for license and provenance; write outputs to a
generated directory such as `BUILD/`. Any result without a recorded command,
environment, dataset manifest and model checksum stays **TBD / unmeasured**.

Run from the repository root:

```bash
python3 -m compileall -q scripts/python
python3 scripts/python/validate_repo.py
python3 scripts/python/<tool>.py --help    # authoritative flag list
```

## Repository validation

```bash
# 11 module dirs, root Gradle files, Markdown links, no invented numeric claims
python3 scripts/python/validate_repo.py

# validate another checkout explicitly
python3 scripts/python/validate_repo.py --root /path/to/checkout
```

Exit 0 prints a short PASS line; a broken link, missing module/build file or an
unqualified numeric result claim fails with exit 1. Targets, budgets,
assumptions, examples, literature citations and `TBD` are allowed. The check is
offline and does not build, train or benchmark anything.

## Dataset manifest and feature extraction

```bash
python3 scripts/python/dataset_manifest.py OPERATOR_SUPPLIED/manifest.json

python3 scripts/python/features.py audio \
  --input OPERATOR_SUPPLIED/pcm.npy --output BUILD/audio_mel.npy
python3 scripts/python/features.py imu \
  --input OPERATOR_SUPPLIED/imu.npy --output BUILD/imu_windows.npy \
  --steps 32 --sample-rate 100
```

`features.py audio` turns mono PCM (`.npy`/`.csv`) into log-mel frames;
`features.py imu` turns 100 Hz IMU into fixed-length windows. Sample rate, FFT
size, hop length and mel bins are operator inputs, not defaults to trust
blindly. See [`../docs/MODEL_CARDS.md`](../docs/MODEL_CARDS.md) for tensor
contracts.

## Augmentation

```bash
python3 scripts/python/augment.py noise \
  --input BUILD/audio_mel.npy --output BUILD/audio_mel_noisy.npy \
  --seed 0 --snr-db 10
python3 scripts/python/augment.py time-mask \
  --input BUILD/audio_mel.npy --output BUILD/audio_mel_masked.npy \
  --seed 0 --max-steps 8
python3 scripts/python/augment.py imu-jitter \
  --input BUILD/imu_windows.npy --output BUILD/imu_windows_jitter.npy \
  --seed 0 --std 0.01
```

Every augmentation is seeded so it can be reproduced. Apply it only to the
training split; keep validation and test splits untouched.

## Training and TFLite export

```bash
python3 scripts/python/train_all.py audio \
  --manifest OPERATOR_SUPPLIED/manifest.json --output BUILD/audio_saved_model
python3 scripts/python/train_all.py motion \
  --manifest OPERATOR_SUPPLIED/manifest.json --output BUILD/motion_saved_model
python3 scripts/python/train_all.py vision \
  --manifest OPERATOR_SUPPLIED/manifest.json --output BUILD/vision_saved_model
python3 scripts/python/train_all.py fusion \
  --manifest OPERATOR_SUPPLIED/manifest.json --output BUILD/fusion_saved_model
python3 scripts/python/train_all.py personalization \
  --manifest OPERATOR_SUPPLIED/manifest.json --output BUILD/personalization_saved_model

python3 scripts/python/convert_tflite.py audio \
  --saved-model BUILD/audio_saved_model --output BUILD/audio_int8.tflite --mode int8
python3 scripts/python/convert_tflite.py motion \
  --saved-model BUILD/motion_saved_model --output BUILD/motion_int8.tflite --mode int8
python3 scripts/python/convert_tflite.py vision \
  --saved-model BUILD/vision_saved_model --output BUILD/vision_float16.tflite --mode float16
python3 scripts/python/convert_tflite.py fusion \
  --saved-model BUILD/fusion_saved_model --output BUILD/fusion_int8.tflite --mode int8
```

Training requires operator-provided TensorFlow tooling, which is absent here.
`personalization` is a design-only model: conversion is supported for
experiments, but there is no v2 runtime module for it. Record the manifest
hash, SavedModel/TFLite checksums and quantization mode with each artifact.

## Benchmark summary (supplied measurements only)

`benchmark.py` never runs an app, device, model or benchmark suite. It reads
millisecond samples that were measured elsewhere and compares one statistic
with a budget you supply. JSON input may be a list of numbers, an object with
`latency_ms`, or an object with `values`/`samples`/`measurements`/`latencies`/
`results`. CSV input may be one numeric column or a header row containing a
recognized latency column (override with `--column`). `--criterion` selects
`mean`, `p95` (nearest-rank, matching `testing/BenchmarkHelpers.kt`) or `max`;
`PASS` means that statistic is at or below `--threshold-ms`. No threshold is
assumed, and the verdict describes only the supplied samples.

```bash
BUDGET_MS=...   # operator-approved budget; not asserted by this repository

python3 scripts/python/benchmark.py \
  --input OPERATOR_SUPPLIED/latency.csv \
  --column latency_ms \
  --threshold-ms "$BUDGET_MS" \
  --criterion p95 \
  --source-label "device/OS/build/run recorded by operator" \
  --format json

python3 scripts/python/benchmark.py \
  --input OPERATOR_SUPPLIED/latency.json \
  --threshold-ms "$BUDGET_MS" \
  --source-label "supplied inference samples"
```

Attach date, hardware/OS, dataset manifest hash and model checksum to the
exported record. Without provenance, label the value `TBD / unmeasured`; never
present it as a v2 app result. See
[`../docs/EVALUATION_PLAN.md`](../docs/EVALUATION_PLAN.md) and
[`../docs/IMPLEMENTATION_PLAN.md`](../docs/IMPLEMENTATION_PLAN.md).
