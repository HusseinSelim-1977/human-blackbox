"""Synthetic test for fusion temporal coherence with updated config."""

from modules.fusion import FusionStrategy
from modules.types import ModalityScore, EMERGENCY, NORMAL


def test_motion_leads_audio():
    """Test motion precedes audio within temporal window."""
    config = {
        "weights": {"AUDIO": 0.40, "MOTION": 0.35, "VISION": 0.25},
        "threshold": 0.55,
        "min_confidence": 0.30,
        "require_two_modalities": True,
        "temporal_window": 0.35,
        "temporal_precedence": 0.18,
        "event_duration_window": 0.35,
    }

    # Motion spike at t=0.0, audio spike at t=0.15 (within 0.35s window)
    motion_score = ModalityScore(
        score=0.75,
        confidence=0.85,
        decision=EMERGENCY,
        timestamp=0.0,
        modality_type="motion",
        raw_features={"accel_magnitude": 0.8},
    )
    audio_score = ModalityScore(
        score=0.70,
        confidence=0.80,
        decision=EMERGENCY,
        timestamp=0.15,
        modality_type="audio",
        raw_features={"energy": 0.75},
    )

    fusion = FusionStrategy(config)
    result = fusion.decide([motion_score, audio_score], config)

    assert result.decision == EMERGENCY, f"Expected EMERGENCY, got {result.decision}"
    assert result.raw_features["temporal_coherence"] > 0.5, \
        f"Temporal coherence too low: {result.raw_features['temporal_coherence']}"
    assert result.raw_features["duration_span"] > 0.0, \
        f"Duration span too low: {result.raw_features['duration_span']}"

    print(f"✓ Motion→Audio temporal coherence test passed")
    print(f"  Score: {result.score:.3f}, Confidence: {result.confidence:.3f}")
    print(f"  Temporal Coherence: {result.raw_features['temporal_coherence']:.3f}")
    print(f"  Event Duration Span: {result.raw_features['duration_span']:.3f}s")


def test_audio_standalone():
    """Test audio alone (no temporal boost) with longer window."""
    config = {
        "weights": {"AUDIO": 0.40, "MOTION": 0.35, "VISION": 0.25},
        "threshold": 0.55,
        "min_confidence": 0.30,
        "require_two_modalities": False,
        "temporal_window": 0.35,
        "temporal_precedence": 0.18,
        "event_duration_window": 0.35,
    }

    audio_score = ModalityScore(
        score=0.65,
        confidence=0.75,
        decision=EMERGENCY,
        timestamp=1.5,
        modality_type="audio",
        raw_features={"energy": 0.65},
    )

    fusion = FusionStrategy(config)
    result = fusion.decide([audio_score], config)

    assert result.decision == EMERGENCY, f"Expected EMERGENCY, got {result.decision}"
    print(f"✓ Audio standalone test passed")
    print(f"  Score: {result.score:.3f}, Confidence: {result.confidence:.3f}")


def test_long_duration_capture():
    """Test that longer event_duration_window captures extended events."""
    config = {
        "weights": {"AUDIO": 0.40, "MOTION": 0.35, "VISION": 0.25},
        "threshold": 0.55,
        "min_confidence": 0.30,
        "require_two_modalities": True,
        "temporal_window": 0.35,
        "temporal_precedence": 0.18,
        "event_duration_window": 0.35,
    }

    # Motion at t=0.0, audio at t=0.30 (near the edge of 0.35s window)
    motion_score = ModalityScore(
        score=0.70,
        confidence=0.78,
        decision=EMERGENCY,
        timestamp=0.0,
        modality_type="motion",
        raw_features={"accel_magnitude": 0.72},
    )
    audio_score = ModalityScore(
        score=0.68,
        confidence=0.76,
        decision=EMERGENCY,
        timestamp=0.30,
        modality_type="audio",
        raw_features={"energy": 0.70},
    )

    fusion = FusionStrategy(config)
    result = fusion.decide([motion_score, audio_score], config)

    assert result.decision == EMERGENCY, f"Expected EMERGENCY, got {result.decision}"
    print(f"✓ Long duration capture test passed")
    print(f"  Score: {result.score:.3f}, Confidence: {result.confidence:.3f}")
    print(f"  Event Duration Span: {result.raw_features['duration_span']:.3f}s")


if __name__ == "__main__":
    test_motion_leads_audio()
    print()
    test_audio_standalone()
    print()
    test_long_duration_capture()
