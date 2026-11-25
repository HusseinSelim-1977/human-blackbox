"""Human BlackBox edge AI modules.

Shared contract types live in modules.modality_types and are read-only.
Each implementation module is owned by one senior specialist.
"""

from modules.modality_types import (
    AudioChunk,
    Frame,
    ImuSample,
    ModalityScore,
    AUDIO,
    MOTION,
    VISION,
    FUSION,
    EMERGENCY,
    NORMAL,
    INDETERMINATE,
)

__all__ = [
    "AudioChunk",
    "Frame",
    "ImuSample",
    "ModalityScore",
    "AUDIO",
    "MOTION",
    "VISION",
    "FUSION",
    "EMERGENCY",
    "NORMAL",
    "INDETERMINATE",
]
