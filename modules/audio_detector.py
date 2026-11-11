"""AudioDetector: YAMNet-style emergency sound detection for Human BlackBox.

Implements the AudioDetector.predict contract from docs/CONTRACTS.md with
settings from config/default.yaml (detectors.audio,
emergency_thresholds.audio_noise). TensorFlow / tflite_runtime are not
installed and no model ships with this repo, so detection runs on a
deterministic numpy heuristic (# ASSUMPTION: heuristic fallback); a loadable
TFLite model at ``model_path`` is used instead, with silent fallback to the
heuristic on any import, load or inference failure. Privacy: on-device only
-- no network, no filesystem writes, no global state; output is
deterministic for a given (chunk, config) pair.
"""

from __future__ import annotations

import math
import os

import numpy as np

from modules.types import (
    AUDIO,
    EMERGENCY,
    INDETERMINATE,
    NORMAL,
    AudioChunk,
    ModalityScore,
)

# ASSUMPTION: heuristic fallback (brief): 0 dBFS == 94 dB SPL.
_DBFS_TO_SPL = 94.0
# dB above the configured audio_noise threshold at which loudness saturates.
_LOUDNESS_HEADROOM_DB = 12.0
# Finite floor for digital silence so raw features stay numeric.
_SILENCE_DBFS = -120.0
# Confidence gate for an EMERGENCY decision (matches fusion min_confidence).
_MIN_CONFIDENCE = 0.3
# Zero-crossing rate of broadband noise (~0.5); normalises the ZCR feature.
_ZCR_NORM = 0.5


class AudioDetector:
    """YAMNet-based audio detector that identifies emergency sounds."""

    def __init__(self, model_path: str, config: dict):
        """Store config; lazily load a TFLite model if one exists."""
        self.model_path = model_path
        self._config = dict(config or {})
        self._interpreter = None
        self._mode = "heuristic"
        if model_path and os.path.exists(model_path):
            try:
                self._interpreter = self._load_tflite(model_path)
                self._mode = "tflite"
            except Exception:  # noqa: BLE001 - silent fallback is contractual
                self._interpreter = None
                self._mode = "heuristic"

    @staticmethod
    def _load_tflite(path: str):
        """Lazily import and load a TFLite interpreter for ``path``."""
        try:
            from tflite_runtime.interpreter import Interpreter
        except ImportError:
            from tensorflow.lite import Interpreter  # type: ignore
        interpreter = Interpreter(model_path=path)
        interpreter.allocate_tensors()
        return interpreter

    def predict(self, audio_chunk: AudioChunk, config: dict) -> ModalityScore:
        """Run audio detection on a single audio chunk.

        Returns a ModalityScore (fields per docs/CONTRACTS.md).
        """
        cfg = dict(config) if config else dict(self._config)
        audio_cfg = cfg.get("detectors", {}).get("audio", {}) or {}
        threshold = float(audio_cfg.get("threshold", 0.5))
        sample_rate = float(audio_cfg.get("sample_rate_hz", 16000.0))
        window_sec = float(audio_cfg.get("window_sec", 0.975))
        threshold_db = float(
            cfg.get("emergency_thresholds", {}).get("audio_noise", 80.0)
        )

        samples = None
        if audio_chunk.audio_data:
            try:
                arr = np.asarray(audio_chunk.audio_data, dtype=np.float64)
                if arr.size and bool(np.isfinite(arr).all()):
                    samples = arr
            except (TypeError, ValueError):
                samples = None  # non-numeric payload -> indeterminate
        if samples is None:
            # Empty or non-finite audio -> INDETERMINATE, confidence 0.0.
            raw = {
                "rms_db_spl": None,
                "peak": None,
                "zcr": None,
                "spectral_centroid_hz": None,
                "spectral_flatness": None,
                "threshold_db": threshold_db,
                "mode": self._mode,
                "source_id": audio_chunk.source_id,
                "reason": "empty_or_non_finite_audio",
            }
            return ModalityScore(0.0, 0.0, INDETERMINATE,
                                 audio_chunk.timestamp, AUDIO, raw)

        feats = self._features(samples, sample_rate)
        mode = self._mode
        if self._interpreter is not None:
            try:
                score, confidence = self._predict_model(
                    samples, sample_rate, window_sec
                )
            except Exception:  # noqa: BLE001 - inference failure -> heuristic
                score, confidence = self._heuristic(feats, threshold_db)
                mode = "heuristic"
        else:
            score, confidence = self._heuristic(feats, threshold_db)
        score = float(min(max(score, 0.0), 1.0))
        confidence = float(min(max(confidence, 0.0), 1.0))
        decision = (EMERGENCY if score >= threshold
                    and confidence >= _MIN_CONFIDENCE else NORMAL)
        raw = dict(feats, threshold_db=threshold_db, mode=mode,
                   source_id=audio_chunk.source_id)
        return ModalityScore(score, confidence, decision,
                             audio_chunk.timestamp, AUDIO, raw)

    @staticmethod
    def _features(samples: np.ndarray, sample_rate: float) -> dict:
        """Compute deterministic time- and frequency-domain features."""
        n = samples.size
        peak = float(np.max(np.abs(samples))) if n else 0.0
        rms = math.sqrt(float(np.mean(samples * samples))) if n else 0.0
        dbfs = 20.0 * math.log10(rms) if rms > 0.0 else _SILENCE_DBFS
        zcr = (float(np.count_nonzero(np.diff(np.sign(samples)))) / (n - 1)
               if n > 1 else 0.0)
        spectrum = np.abs(np.fft.rfft(samples))
        power = spectrum * spectrum
        freqs = np.fft.rfftfreq(n, d=1.0 / sample_rate)
        total = float(np.sum(power))
        if total > 0.0:
            centroid = float(np.sum(freqs * power) / total)
            eps = 1e-12
            geo = math.exp(float(np.mean(np.log(power + eps))))
            arith = float(np.mean(power + eps))
            flatness = geo / arith if arith > 0.0 else 0.0
        else:
            centroid = 0.0
            flatness = 0.0
        return {
            "rms_db_spl": dbfs + _DBFS_TO_SPL,
            "peak": peak,
            "zcr": zcr,
            "spectral_centroid_hz": centroid,
            "spectral_flatness": min(max(flatness, 0.0), 1.0),
        }

    @staticmethod
    def _heuristic(feats: dict, threshold_db: float) -> tuple[float, float]:
        """Blend loudness above the noise threshold with flatness/ZCR.

        Loudness gates the score; broadband spectral evidence (flatness,
        ZCR) scales it, so quiet, tonal or DC inputs stay NORMAL.
        """
        loudness = min(max(
            (feats["rms_db_spl"] - threshold_db) / _LOUDNESS_HEADROOM_DB,
            0.0), 1.0)
        broadband = 0.5 * (feats["spectral_flatness"]
                           + min(feats["zcr"] / _ZCR_NORM, 1.0))
        score = loudness * (0.6 + 0.4 * broadband)
        confidence = 0.25 + 0.75 * (0.7 * loudness + 0.3 * broadband)
        return score, confidence

    def _predict_model(self, samples: np.ndarray, sample_rate: float,
                       window_sec: float) -> tuple[float, float]:
        """Run the TFLite interpreter on the chunk.

        ASSUMPTION: tflite output layout -- the output tensor is a per-class
        score vector whose max is the emergency-class evidence; mapping to
        config detectors.audio.emergency_classes is deferred to integration.
        """
        interp = self._interpreter
        inp = interp.get_input_details()[0]
        want = int(round(window_sec * sample_rate))
        if want > 0 and samples.size != want:
            if samples.size < want:
                samples = np.pad(samples, (0, want - samples.size))
            else:
                samples = samples[:want]
        waveform = np.asarray(samples, dtype=inp["dtype"]).reshape(inp["shape"])
        interp.set_tensor(inp["index"], waveform)
        interp.invoke()
        out = interp.get_tensor(interp.get_output_details()[0]["index"])
        top = float(np.max(out))
        return top, top
