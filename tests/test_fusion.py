"""Unit tests for the weighted, confidence-gated FusionStrategy."""

import copy

import pytest

from config import load_config
from modules.fusion import FusionStrategy
from modules.types import (
    AUDIO,
    EMERGENCY,
    FUSION,
    INDETERMINATE,
    MOTION,
    NORMAL,
    VISION,
    ModalityScore,
)

DEFAULT_CONFIG = {
    "fusion": {
        "weights": {"AUDIO": 0.35, "MOTION": 0.40, "VISION": 0.25},
        "threshold": 0.65,
        "min_confidence": 0.30,
        "require_two_modalities": True,
    }
}


def make_score(score, confidence, decision, modality, timestamp=100.0):
    return ModalityScore(
        score=score,
        confidence=confidence,
        decision=decision,
        timestamp=timestamp,
        modality_type=modality,
        raw_features={"source": "test"},
    )


def fusion(config=None):
    return FusionStrategy(config if config is not None else DEFAULT_CONFIG)


def test_empty_input_is_indeterminate():
    result = fusion().decide([], DEFAULT_CONFIG)
    assert result.decision == INDETERMINATE
    assert result.score == 0.0
    assert result.confidence == 0.0
    assert result.timestamp == 0.0
    assert result.modality_type == FUSION
    assert result.raw_features["modalities_count"] == 0
    assert result.raw_features["agreement"] == 0.0
    assert set(result.raw_features) == {
        "per_modality", "weights_used", "weighted_score",
        "threshold", "modalities_count", "agreement",
    }


def test_single_high_audio_is_normal_due_to_two_modality_rule():
    scores = [make_score(0.95, 0.90, EMERGENCY, AUDIO)]
    result = fusion().decide(scores, DEFAULT_CONFIG)
    assert result.decision == NORMAL
    assert result.raw_features["weighted_score"] == pytest.approx(0.95)
    assert result.raw_features["modalities_count"] == 1


def test_audio_and_motion_high_is_emergency():
    scores = [
        make_score(0.90, 0.90, EMERGENCY, AUDIO),
        make_score(0.90, 0.90, EMERGENCY, MOTION),
    ]
    result = fusion().decide(scores, DEFAULT_CONFIG)
    assert result.decision == EMERGENCY
    assert result.raw_features["weighted_score"] == pytest.approx(0.90)
    assert result.raw_features["agreement"] == pytest.approx(1.0)
    assert result.confidence == pytest.approx(0.90)


def test_contradictory_high_and_low_is_normal():
    scores = [
        make_score(0.90, 0.90, EMERGENCY, AUDIO),
        make_score(0.10, 0.90, NORMAL, MOTION),
    ]
    result = fusion().decide(scores, DEFAULT_CONFIG)
    # (0.35 * 0.90 + 0.40 * 0.10) / 0.75 == 0.4733...
    assert result.decision == NORMAL
    assert result.raw_features["weighted_score"] == pytest.approx(0.473333, abs=1e-5)
    assert result.raw_features["agreement"] == pytest.approx(0.5)


def test_low_confidence_score_is_down_weighted():
    scores = [
        make_score(0.95, 0.10, EMERGENCY, AUDIO),
        make_score(0.85, 0.90, EMERGENCY, MOTION),
    ]
    result = fusion().decide(scores, DEFAULT_CONFIG)
    audio = result.raw_features["per_modality"][AUDIO]
    assert audio["effective_score"] == pytest.approx(0.95 * 0.10)
    assert result.raw_features["weighted_score"] == pytest.approx(0.497667, abs=1e-5)
    assert result.decision == NORMAL


def test_weights_normalise_when_a_modality_is_missing():
    scores = [
        make_score(0.90, 0.90, EMERGENCY, AUDIO),
        make_score(0.90, 0.90, EMERGENCY, VISION),
    ]
    result = fusion().decide(scores, DEFAULT_CONFIG)
    weights = result.raw_features["weights_used"]
    assert sum(weights.values()) == pytest.approx(1.0)
    assert weights[AUDIO] == pytest.approx(0.35 / 0.60)
    assert weights[VISION] == pytest.approx(0.25 / 0.60)
    assert result.decision == EMERGENCY


def test_unknown_modality_is_ignored():
    with_unknown = [
        make_score(0.90, 0.90, EMERGENCY, AUDIO),
        make_score(0.90, 0.90, EMERGENCY, MOTION),
        make_score(0.99, 0.99, EMERGENCY, "TEMPERATURE"),
    ]
    result = fusion().decide(with_unknown, DEFAULT_CONFIG)
    assert result.raw_features["modalities_count"] == 2
    assert "TEMPERATURE" not in result.raw_features["per_modality"]
    assert result.decision == EMERGENCY
    assert result.raw_features["weighted_score"] == pytest.approx(0.90)


def test_only_unknown_modalities_is_indeterminate():
    scores = [make_score(0.99, 0.99, EMERGENCY, "TEMPERATURE")]
    result = fusion().decide(scores, DEFAULT_CONFIG)
    assert result.decision == INDETERMINATE
    assert result.raw_features["modalities_count"] == 0


def test_duplicate_modality_keeps_highest_effective_score():
    scores = [
        make_score(0.20, 0.90, NORMAL, AUDIO, timestamp=10.0),
        make_score(0.90, 0.90, EMERGENCY, AUDIO, timestamp=20.0),
    ]
    result = fusion().decide(scores, DEFAULT_CONFIG)
    assert result.raw_features["modalities_count"] == 1
    assert result.raw_features["per_modality"][AUDIO]["effective_score"] == pytest.approx(0.90)
    assert result.timestamp == 20.0


def test_threshold_boundary_is_emergency():
    scores = [
        make_score(0.65, 0.90, EMERGENCY, AUDIO),
        make_score(0.65, 0.90, EMERGENCY, MOTION),
    ]
    result = fusion().decide(scores, DEFAULT_CONFIG)
    assert result.raw_features["weighted_score"] == pytest.approx(0.65)
    assert result.decision == EMERGENCY


def test_two_modality_rule_can_be_disabled():
    config = copy.deepcopy(DEFAULT_CONFIG)
    config["fusion"]["require_two_modalities"] = False
    scores = [make_score(0.90, 0.90, EMERGENCY, AUDIO)]
    assert fusion(config).decide(scores, config).decision == EMERGENCY


def test_timestamp_is_newest_input():
    scores = [
        make_score(0.90, 0.90, EMERGENCY, AUDIO, timestamp=5.0),
        make_score(0.90, 0.90, EMERGENCY, MOTION, timestamp=42.5),
        make_score(0.10, 0.90, NORMAL, VISION, timestamp=7.0),
    ]
    assert fusion().decide(scores, DEFAULT_CONFIG).timestamp == 42.5


def test_input_list_is_not_mutated():
    scores = [
        make_score(0.90, 0.90, EMERGENCY, AUDIO, timestamp=1.0),
        make_score(0.10, 0.20, NORMAL, MOTION, timestamp=2.0),
    ]
    original = copy.deepcopy([s.as_dict() for s in scores])
    original_order = list(scores)
    fusion().decide(scores, DEFAULT_CONFIG)
    assert scores == original_order
    assert [s.as_dict() for s in scores] == original


def test_decide_is_deterministic():
    scores = [
        make_score(0.80, 0.50, EMERGENCY, AUDIO),
        make_score(0.70, 0.90, EMERGENCY, MOTION),
        make_score(0.60, 0.10, NORMAL, VISION),
    ]
    first = fusion().decide(scores, DEFAULT_CONFIG).as_dict()
    second = fusion().decide(scores, DEFAULT_CONFIG).as_dict()
    assert first == second


def test_uses_repository_default_config():
    config = load_config()
    scores = [
        make_score(0.90, 0.90, EMERGENCY, AUDIO),
        make_score(0.90, 0.90, EMERGENCY, MOTION),
    ]
    result = FusionStrategy(config).decide(scores, config)
    assert result.decision == EMERGENCY
    assert result.raw_features["threshold"] == pytest.approx(0.65)
    assert result.raw_features["weights_used"][AUDIO] == pytest.approx(0.35 / 0.75)
