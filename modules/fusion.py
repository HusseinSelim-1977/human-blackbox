"""Weighted, confidence-gated fusion of per-modality detector scores.

Implements the ``FusionStrategy`` contract from ``docs/CONTRACTS.md``.
"""

from __future__ import annotations

import math
from typing import Dict, List, Tuple

from modules.types import (
    EMERGENCY,
    FUSION,
    INDETERMINATE,
    NORMAL,
    ModalityScore,
)

DEFAULT_WEIGHTS = {"AUDIO": 0.35, "MOTION": 0.40, "VISION": 0.25}
DEFAULT_THRESHOLD = 0.65
DEFAULT_MIN_CONFIDENCE = 0.30
DEFAULT_REQUIRE_TWO = True


def _fusion_section(config: object) -> dict:
    """Accept either a full system config or a bare fusion sub-config."""
    if not isinstance(config, dict):
        return {}
    section = config.get("fusion")
    return section if isinstance(section, dict) else config


def _clamp01(value: object, default: float = 0.0) -> float:
    """Coerce to float and clamp into [0.0, 1.0]."""
    try:
        number = float(value)
    except (TypeError, ValueError):
        return default
    return max(0.0, min(1.0, number))


def _positive_float(value: object, default: float) -> float:
    try:
        number = float(value)
    except (TypeError, ValueError):
        return default
    return max(0.0, number)


class FusionStrategy:
    """Combine modality scores using normalised weights and a confidence gate."""

    def __init__(self, config: dict):
        self.config = config if isinstance(config, dict) else {}

    def decide(self,
               modality_scores: List[ModalityScore],
               config: dict) -> ModalityScore:
        """Fuse modality scores into one FUSION decision (never mutates input)."""
        source = config if isinstance(config, dict) and config else self.config
        cfg = _fusion_section(source)

        raw_weights = cfg.get("weights")
        if not isinstance(raw_weights, dict):
            raw_weights = {}
        weights = {
            modality: _positive_float(raw_weights.get(modality, default), default)
            for modality, default in DEFAULT_WEIGHTS.items()
        }
        threshold = _positive_float(cfg.get("threshold"), DEFAULT_THRESHOLD)
        min_confidence = _positive_float(
            cfg.get("min_confidence"), DEFAULT_MIN_CONFIDENCE)
        require_two = bool(
            cfg.get("require_two_modalities", DEFAULT_REQUIRE_TWO))

        latest_timestamp = 0.0
        selected: Dict[str, Tuple[ModalityScore, float]] = {}
        for item in modality_scores:
            if not isinstance(item, ModalityScore):
                continue
            latest_timestamp = max(latest_timestamp, float(item.timestamp))
            modality = str(item.modality_type).upper()
            if modality not in weights:
                continue
            score = _clamp01(item.score)
            confidence = _clamp01(item.confidence)
            # ASSUMPTION: scores below min_confidence are down-weighted by
            # their own confidence (effective = score * confidence); trusted
            # scores keep their score unchanged.
            effective = (score if confidence >= min_confidence
                         else score * confidence)
            previous = selected.get(modality)
            # ASSUMPTION: duplicate modality entries are deduplicated by
            # keeping the highest effective score (first one on a tie).
            if previous is None or effective > previous[1]:
                selected[modality] = (item, effective)

        if not selected:
            # ASSUMPTION: input that contains no usable known modality is
            # treated like empty input and reported as INDETERMINATE.
            return self._result(
                0.0, 0.0, INDETERMINATE, latest_timestamp, threshold,
                {}, {}, 0)

        total_weight = sum(weights[modality] for modality in selected)
        if total_weight <= 0.0:
            # ASSUMPTION: all-zero configured weights fall back to equal
            # weights across the modalities that are present.
            weights_used = {
                modality: 1.0 / len(selected) for modality in selected}
        else:
            weights_used = {
                modality: weights[modality] / total_weight
                for modality in selected}

        # Round to 12 places so an exact threshold boundary is not lost to
        # floating-point summation error before the >= comparison.
        weighted_score = round(math.fsum(
            weights_used[modality] * effective
            for modality, (_, effective) in selected.items()), 12)
        confidence = round(math.fsum(
            weights_used[modality] * _clamp01(item.confidence)
            for modality, (item, _) in selected.items()), 12)

        if require_two and len(selected) < 2:
            decision = NORMAL
        else:
            decision = EMERGENCY if weighted_score >= threshold else NORMAL

        agreement = sum(
            1 for item, _ in selected.values() if item.decision == decision
        ) / len(selected)

        per_modality = {
            modality: {
                "score": _clamp01(item.score),
                "confidence": _clamp01(item.confidence),
                "decision": item.decision,
                "effective_score": effective,
                "weight": weights_used[modality],
            }
            for modality, (item, effective) in selected.items()
        }

        return self._result(
            _clamp01(weighted_score), _clamp01(confidence), decision,
            latest_timestamp, threshold, per_modality, weights_used,
            len(selected), agreement)

    @staticmethod
    def _result(score: float, confidence: float, decision: str,
                timestamp: float, threshold: float, per_modality: dict,
                weights_used: dict, modalities_count: int,
                agreement: float = 0.0) -> ModalityScore:
        return ModalityScore(
            score=score,
            confidence=confidence,
            decision=decision,
            timestamp=timestamp,
            modality_type=FUSION,
            raw_features={
                "per_modality": per_modality,
                "weights_used": weights_used,
                "weighted_score": score,
                "threshold": threshold,
                "modalities_count": modalities_count,
                "agreement": agreement,
            },
        )
