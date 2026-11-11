# Assumptions

These are the documented assumptions for the Human BlackBox Edge AI Emergency Detection System, as recorded in the configuration file (`config/default.yaml`).

## Model Assumptions
- Model version: 1.0.0
- Model size: 8B
- Edge compute platform: NVIDIA Jetson Nano
- Latency target: 50ms per inference
- Power consumption: 5W
- # ASSUMPTION: No TensorFlow/PyTorch runtime is installed in this environment. Detectors must load a model when present and otherwise fall back to a deterministic signal-processing heuristic, clearly marked `# ASSUMPTION: heuristic fallback`.
- # ASSUMPTION: Model files live under `modules/models/` and are not shipped in this repository.

## Sensor Assumptions
- Temperature sensor type: thermistor
- Pressure sensor type: barometer
- Motion sensor type: accelerometer
- Audio sensor type: microphone

## Emergency Detection Thresholds
- Temperature high threshold: 40.0°C
- Temperature low threshold: 10.0°C
- Motion activity threshold: 0.8 (normalized)
- Audio noise threshold: 80.0 dB
- Per-modality detector thresholds, fusion weights and the 15s countdown are defined in `config/default.yaml` under `detectors`, `fusion`, `alert`, `buffer` and `safety`.

## System Behavior Assumptions
- Inference timeout: 100ms
- Batch size: 16
- Buffer size: 1024 samples
- Rolling buffer retains 60 seconds of encrypted evidence.
- # ASSUMPTION: Encryption uses a platform-provided AES-256-GCM primitive keyed from a device-unique secret; the crypto provider is injected, not implemented here.

## Operational Assumptions
- Edge device has constant power supply
- Network connectivity available for periodic sync
- Sensor calibration performed before deployment
- False positive rate < 1%
- False negative rate < 0.5%

## Safety Assumptions
- A visible recording indicator is shown whenever mic or camera capture is active.
- Mic and camera consent are explicit, per-sensor, and revocable at any time.
- Every alert passes through a 15-second countdown that the user can cancel.
- Raw media stays on-device unless the user explicitly opts in to share it.
