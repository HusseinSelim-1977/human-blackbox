"""Motion / IMU emergency detector for Human BlackBox (docs/CONTRACTS.md).

MotionDetector scores each ImuSample in [0, 1] and returns a ModalityScore.
# ASSUMPTION: heuristic fallback — no trained LSTM ships with the repo
(`modules/models/motion_lstm.onnx` does not exist); the LSTM path only runs
when a model file exists and its runtime imports successfully.
"""

import math
import os
from collections import deque
from typing import Any, Deque, Dict, List, Optional, Tuple

from modules.types import (EMERGENCY, INDETERMINATE, MOTION, NORMAL, ImuSample,
                           ModalityScore)

STANDARD_G = 9.80665            # m/s^2 per g (CONTRACTS: accelerometer is m/s^2)
PRESSURE_MIN_HPA, PRESSURE_MAX_HPA = 300.0, 1100.0  # plausible range  # ASSUMPTION
# ASSUMPTION: full-scale normalizers -> activity/thermal severity 1.0:
# jerk 200 m/s^3, gyro 2 rad/s (fast tumble), |g-1| 2 g, 5 C beyond band.
JERK_FULL_SCALE = 200.0
GYRO_FULL_SCALE = 2.0
ACCEL_DEV_FULL_SCALE = 2.0
THERMAL_FULL_SCALE_C = 5.0
MIN_CONFIDENCE = 0.3            # EMERGENCY also requires confidence >= this
FREEFALL_SCORE = 0.95           # freefall dominates just below impact
IMPACT_SCORE = 1.0              # impact dominates

_MISSING = object()


def _vec3(value: Any) -> Optional[List[float]]:
    """Return the value as 3 finite floats, or None if malformed."""
    if not isinstance(value, (list, tuple)) or len(value) != 3:
        return None
    try:
        out = [float(x) for x in value]
        return out if all(map(math.isfinite, out)) else None
    except (TypeError, ValueError):
        return None


class MotionDetector:
    """LSTM-based motion detector that identifies emergency motion patterns.

    Falls back to a deterministic heuristic when the model file is missing or
    its runtime is unavailable. All rolling state is per-instance only.
    """

    def __init__(self, model_path: str, config: dict):
        self._config: Dict[str, Any] = dict(config) if config else {}
        self._session: Any = None
        self._mode = "heuristic"
        self._load_model(model_path)
        seq_len = int(self._cfg(self._config, ("detectors", "motion", "sequence_length"), 16))
        # Rolling window of (timestamp, |a| m/s^2, |gyro| rad/s, jerk), per-instance.
        self._history: Deque[Tuple[float, float, float, float]] = deque(maxlen=seq_len)

    def _cfg(self, cfg: dict, path: Tuple[str, ...], default: Any) -> Any:
        """Nested key lookup: predict-time config first, then the init one."""
        for src in (cfg, self._config):
            node: Any = src
            for key in path:
                node = node.get(key, _MISSING) if isinstance(node, dict) else _MISSING
            if node is not _MISSING and node is not None:
                return node
        return default

    def _load_model(self, model_path: str) -> None:
        """Lazily load the LSTM model; any failure falls back to heuristic."""
        try:
            if not isinstance(model_path, str) or not os.path.isfile(model_path):
                return
            if model_path.lower().endswith(".onnx"):
                import onnxruntime as _ort  # type: ignore
                self._session = _ort.InferenceSession(model_path)
            elif model_path.lower().endswith((".tflite", ".lite")):
                try:
                    from tflite_runtime.interpreter import Interpreter  # type: ignore
                except ImportError:  # pragma: no cover - env dependent
                    from tensorflow.lite.python.interpreter import Interpreter  # type: ignore
                self._session = Interpreter(model_path=model_path)
                self._session.allocate_tensors()
            else:
                return
            self._mode = "model"
        except Exception:  # ASSUMPTION: heuristic fallback on any load error
            self._session = None
            self._mode = "heuristic"

    def _model_score(self) -> Optional[float]:
        """LSTM over the rolling window, or None if unusable.
        # ASSUMPTION: (1, seq, 3) float32 input [|a|/g, |gyro|, jerk], one score in [0, 1].
        """
        if self._session is None or len(self._history) < 2:
            return None
        try:
            import numpy as np
            window = np.array([[[a / STANDARD_G, w / GYRO_FULL_SCALE,
                                 min(1.0, j / JERK_FULL_SCALE)]
                                for _, a, w, j in self._history]], dtype=np.float32)
            if hasattr(self._session, "run"):  # onnxruntime session
                name = self._session.get_inputs()[0].name
                out = self._session.run(None, {name: window})
            else:  # tflite interpreter
                inp = self._session.get_input_details()[0]
                self._session.set_tensor(inp["index"], window)
                self._session.invoke()
                out = [self._session.get_tensor(self._session.get_output_details()[0]["index"])]
            value = float(np.asarray(out).ravel()[0])
            return value if math.isfinite(value) and 0.0 <= value <= 1.0 else None
        except Exception:  # any inference failure -> heuristic
            return None

    def predict(self, imu_sample: ImuSample, config: dict) -> ModalityScore:
        cfg = dict(config) if config else {}
        parsed = self._parse(imu_sample)
        if parsed is None:  # malformed input never touches the rolling history
            return ModalityScore(0.0, 0.0, INDETERMINATE,
                                 getattr(imu_sample, "timestamp", 0.0), MOTION,
                                 {"mode": self._mode, "error": "invalid imu sample"})
        ts, acc, gyro, temp, press = parsed
        freefall_g = float(self._cfg(cfg, ("detectors", "motion", "freefall_g"), 0.35))
        min_ff = int(self._cfg(cfg, ("detectors", "motion", "freefall_min_samples"), 3))
        impact_g = float(self._cfg(cfg, ("detectors", "motion", "impact_g"), 3.0))
        window_sec = float(self._cfg(cfg, ("detectors", "motion", "window_sec"), 1.0))
        seq_len = int(self._cfg(cfg, ("detectors", "motion", "sequence_length"), 16))
        t_low = float(self._cfg(cfg, ("emergency_thresholds", "temperature_low"), 10.0))
        t_high = float(self._cfg(cfg, ("emergency_thresholds", "temperature_high"), 40.0))
        accel_mag = math.sqrt(acc[0] ** 2 + acc[1] ** 2 + acc[2] ** 2)
        gyro_mag = math.sqrt(gyro[0] ** 2 + gyro[1] ** 2 + gyro[2] ** 2)
        jerk = 0.0
        if self._history and ts > self._history[-1][0]:
            jerk = abs(accel_mag - self._history[-1][1]) / (ts - self._history[-1][0])
        self._history.append((ts, accel_mag, gyro_mag, jerk))
        while len(self._history) > 1 and ts - self._history[0][0] > window_sec:
            self._history.popleft()  # keep only the recent window
        streak = 0  # trailing samples below the freefall threshold
        for _, prev_a, _, _ in reversed(self._history):
            if prev_a / STANDARD_G >= freefall_g:
                break
            streak += 1
        freefall = streak >= min_ff
        impact = accel_mag / STANDARD_G >= impact_g

        # Activity: deviation from 1 g, jerk, orientation change (gyro rate).
        activity = max(min(1.0, abs(accel_mag / STANDARD_G - 1.0) / ACCEL_DEV_FULL_SCALE),
                       min(1.0, jerk / JERK_FULL_SCALE),
                       min(1.0, gyro_mag / GYRO_FULL_SCALE))
        excursion = max(temp - t_high, t_low - temp, 0.0)  # thermal excursion
        thermal = (0.8 + 0.2 * min(1.0, excursion / THERMAL_FULL_SCALE_C)
                   if excursion > 0.0 else 0.0)

        score = max(activity, thermal, FREEFALL_SCORE if freefall else 0.0,
                    IMPACT_SCORE if impact else 0.0)
        model_score = self._model_score()
        if model_score is not None:  # safety floor: hard evidence is never
            # masked by the model; the model replaces the activity path
            score = max(model_score, thermal, FREEFALL_SCORE if freefall else 0.0,
                        IMPACT_SCORE if impact else 0.0)

        confidence = 0.5 + (0.2 if impact else 0.1 if freefall else 0.0)
        confidence += 0.2 * min(1.0, len(self._history) / max(1, seq_len))
        if not PRESSURE_MIN_HPA <= press <= PRESSURE_MAX_HPA:
            confidence *= 0.5  # implausible barometer -> suspect reading
        confidence = min(1.0, confidence)

        threshold = float(self._cfg(cfg, ("detectors", "motion", "threshold"), 0.80))
        if score >= threshold and confidence >= MIN_CONFIDENCE:
            decision = EMERGENCY
        elif score >= threshold:
            decision = INDETERMINATE  # high score, but not enough trust
        else:
            decision = NORMAL

        raw = {"accel_magnitude": accel_mag, "gyro_magnitude": gyro_mag, "jerk": jerk,
               "freefall": freefall, "impact": impact, "temperature_c": temp,
               "pressure_hpa": press, "activity": activity, "mode": self._mode}
        return ModalityScore(score, confidence, decision, ts, MOTION, raw)

    @staticmethod
    def _parse(imu_sample: Any) -> Optional[Tuple[float, List[float], List[float], float, float]]:
        """Validate an ImuSample; return None when any field is malformed."""
        if imu_sample is None:
            return None
        acc = _vec3(getattr(imu_sample, "accelerometer", None))
        gyro = _vec3(getattr(imu_sample, "gyroscope", None))
        mag = _vec3(getattr(imu_sample, "magnetometer", None))
        if acc is None or gyro is None or mag is None:
            return None
        try:
            ts, temp, press = (float(getattr(imu_sample, k, None))
                               for k in ("timestamp", "temperature", "pressure"))
        except (TypeError, ValueError):
            return None
        if not all(map(math.isfinite, (ts, temp, press))):
            return None
        return ts, acc, gyro, temp, press
