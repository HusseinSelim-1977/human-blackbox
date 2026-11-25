"""Tests for the ambient Z-score boost in :class:`FusionStrategy`."""

import pytest

from modules.fusion import FusionStrategy
from modules.modality_types import (
    AUDIO,
    EMERGENCY,
    INDETERMINATE,
    MOTION,
    ModalityScore,
)

BASE_CONFIG = {
    "fusion": {
        "weights": {"AUDIO": 0.5, "MOTION": 0.5, "VISION": 0.0},
        "threshold": 0.65,
        "min_confidence": 0.0,
        "require_two_modalities": False,
        "ambient_features": {"AUDIO": ["energy"]},
    }
}


def make_score(score, modality, raw_features=None, timestamp=100.0):
    return ModalityScore(
        score=score,
        confidence=1.0,
        decision=EMERGENCY,
        timestamp=timestamp,
        modality_type=modality,
        raw_features=raw_features or {},
    )


def decide(scores, config):
    return FusionStrategy(config).decide(scores, config)


def test_no_runtime_stats_leaves_score_unchanged():
    result = decide([make_score(0.40, AUDIO, {"energy": 9.0})], BASE_CONFIG)
    assert result.raw_features["weighted_score"] == pytest.approx(0.40)
    assert "ambient_boosts" not in result.raw_features


def test_deviating_feature_boosts_effective_score():
    config = {
        "fusion": {
            **BASE_CONFIG["fusion"],
            "ambient_mean": {"AUDIO": {"energy": {"mean": 0.0, "std": 2.0}}},
            "ambient_std": {"AUDIO": {"energy": {"mean": 0.0, "std": 2.0}}},
        }
    }
    result = decide([make_score(0.40, AUDIO, {"energy": 4.0})], config)
    # z = 2.0 -> factor 1 + 0.25 * 2 = 1.5 -> 0.40 * 1.5 = 0.60
    assert result.raw_features["weighted_score"] == pytest.approx(0.60)
    assert result.raw_features["ambient_boosts"]["AUDIO"] == pytest.approx(1.5)


def test_boost_is_capped_and_score_stays_bounded():
    config = {
        "fusion": {
            **BASE_CONFIG["fusion"],
            "ambient_mean": {"AUDIO": {"energy": {"mean": 0.0, "std": 0.1}}},
            "ambient_std": {"AUDIO": {"energy": {"mean": 0.0, "std": 0.1}}},
        }
    }
    result = decide([make_score(0.90, AUDIO, {"energy": 100.0})], config)
    # |z| = 1000 is capped at 3 -> factor <= 1.75 -> score clamped at 1.0
    assert result.raw_features["ambient_boosts"]["AUDIO"] <= 1.75
    assert 0.0 <= result.score <= 1.0


def test_zero_std_is_skipped():
    config = {
        "fusion": {
            **BASE_CONFIG["fusion"],
            "ambient_mean": {"AUDIO": {"energy": {"mean": 1.0, "std": 0.0}}},
            "ambient_std": {"AUDIO": {"energy": {"mean": 1.0, "std": 0.0}}},
        }
    }
    result = decide([make_score(0.40, AUDIO, {"energy": 5.0})], config)
    assert result.raw_features["weighted_score"] == pytest.approx(0.40)
    assert "ambient_boosts" not in result.raw_features


def test_boost_only_applies_to_selected_modalities():
    config = {
        "fusion": {
            **BASE_CONFIG["fusion"],
            "ambient_features": {"AUDIO": ["energy"], "MOTION": ["accel_magnitude"]},
            "ambient_mean": {"MOTION": {"accel_magnitude": {"mean": 0.0, "std": 1.0}}},
            "ambient_std": {"MOTION": {"accel_magnitude": {"mean": 0.0, "std": 1.0}}},
        }
    }
    result = decide(
        [make_score(0.40, AUDIO, {"energy": 9.0}),
         make_score(0.20, MOTION, {"accel_magnitude": 2.0})],
        config,
    )
    assert set(result.raw_features["ambient_boosts"]) == {MOTION}


def test_empty_input_remains_indeterminate_with_stats():
    config = {
        "fusion": {
            **BASE_CONFIG["fusion"],
            "ambient_mean": {"AUDIO": {"energy": {"mean": 0.0, "std": 1.0}}},
            "ambient_std": {"AUDIO": {"energy": {"mean": 0.0, "std": 1.0}}},
        }
    }
    result = decide([], config)
    assert result.decision == INDETERMINATE
    assert result.score == 0.0
    assert "ambient_boosts" not in result.raw_features
