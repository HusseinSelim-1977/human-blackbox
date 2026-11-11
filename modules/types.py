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
    """Score from a single modality detector (or from fusion)."""

    def __init__(self, score: float, confidence: float, decision: str,
                 timestamp: float, modality_type: str,
                 raw_features: Dict[str, Any]):
        self.score = score
        self.confidence = confidence
        self.decision = decision
        self.timestamp = timestamp
        self.modality_type = modality_type
        self.raw_features = raw_features

    def as_dict(self) -> Dict[str, Any]:
        return {
            "score": self.score,
            "confidence": self.confidence,
            "decision": self.decision,
            "timestamp": self.timestamp,
            "modality_type": self.modality_type,
            "raw_features": self.raw_features,
        }

    def __repr__(self) -> str:
        return (f"ModalityScore({self.modality_type}: {self.decision} "
                f"score={self.score:.3f} conf={self.confidence:.3f})")


SensorChunk = Union[AudioChunk, ImuSample, Frame]
