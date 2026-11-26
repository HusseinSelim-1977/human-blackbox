"""Shared data types for Human BlackBox.

Implemented exactly as specified in docs/CONTRACTS.md.
This file is COORDINATOR-OWNED and read-only for all senior specialists.
"""

from __future__ import annotations

from typing import Any, Dict, List, Union

EMERGENCY = "EMERGENCY"
NORMAL = "NORMAL"
INDETERMINATE = "INDETERMINATE"

AUDIO = "AUDIO"
MOTION = "MOTION"
VISION = "VISION"
FUSION = "FUSION"


class AudioChunk:
    """A single audio segment for detection."""

    def __init__(self, audio_data: List[float], timestamp: float,
                 duration_sec: float, source_id: str):
        self.audio_data = audio_data
        self.timestamp = timestamp
        self.duration_sec = duration_sec
        self.source_id = source_id

    def __repr__(self) -> str:
        return (f"AudioChunk(samples={len(self.audio_data)}, "
                f"ts={self.timestamp:.3f}, dur={self.duration_sec}, "
                f"src={self.source_id!r})")


class ImuSample:
    """A single IMU measurement."""

    def __init__(self, timestamp: float, accelerometer: List[float],
                 gyroscope: List[float], magnetometer: List[float],
                 temperature: float, pressure: float, source_id: str):
        self.timestamp = timestamp
        self.accelerometer = accelerometer
        self.gyroscope = gyroscope
        self.magnetometer = magnetometer
        self.temperature = temperature
        self.pressure = pressure
        self.source_id = source_id

    def __repr__(self) -> str:
        return (f"ImuSample(ts={self.timestamp:.3f}, acc={self.accelerometer}, "
                f"src={self.source_id!r})")


class Frame:
    """A single camera frame for detection."""

    def __init__(self, image_data: List[int], timestamp: float,
                 width: int, height: int, source_id: str):
        self.image_data = image_data
        self.timestamp = timestamp
        self.width = width
        self.height = height
        self.source_id = source_id

    def __repr__(self) -> str:
        return (f"Frame({self.width}x{self.height}, ts={self.timestamp:.3f}, "
                f"src={self.source_id!r})")


class ModalityScore:
    """Score from a single modality detector (or from fusion).

    Extends the base contract with:
      - ``normalized_score``: score relative to ambient baseline (0-1 range)
      - ``baseline_mean`` and ``baseline_std``: rolling window stats for
        this modality's raw features
      - ``context_tags``: metadata describing environmental conditions
    """

    def __init__(self, score: float, confidence: float, decision: str = None,
                 timestamp: float = 0.0, modality_type: str = "UNKNOWN",
                 raw_features: Dict[str, Any] = None,
                 normalized_score: float = 1.0,
                 baseline_mean: Dict[str, float] = None,
                 baseline_std: Dict[str, float] = None,
                 context_tags: Dict[str, str] = None):
        self.score = score
        self.confidence = confidence
        self.decision = decision or "UNKNOWN"
        self.timestamp = timestamp
        self.modality_type = modality_type
        self.raw_features = raw_features
        self.normalized_score = normalized_score
        self.baseline_mean = baseline_mean or {}
        self.baseline_std = baseline_std or {}
        self.context_tags = context_tags or {}

    def as_dict(self) -> Dict[str, Any]:
        d = {
            "score": self.score,
            "confidence": self.confidence,
            "decision": self.decision,
            "timestamp": self.timestamp,
            "modality_type": self.modality_type,
            "raw_features": self.raw_features,
        }
        if self.normalized_score != 1.0:
            d["normalized_score"] = self.normalized_score
        if self.baseline_mean:
            d["baseline_mean"] = self.baseline_mean
        if self.baseline_std:
            d["baseline_std"] = self.baseline_std
        if self.context_tags:
            d["context_tags"] = self.context_tags
        return d

    def __repr__(self) -> str:
        return (f"ModalityScore({self.modality_type}: {self.decision} "
                f"score={self.score:.3f} conf={self.confidence:.3f})")


SensorChunk = Union[AudioChunk, ImuSample, Frame]
