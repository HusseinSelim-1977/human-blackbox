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
DEFAULT_TEMPORAL_WINDOW = 0.30  # seconds to consider for temporal coherence
DEFAULT_TEMPORAL_PRECEDENCE = 0.15  # motion must precede audio by this much for strong temporal signal
DEFAULT_EVENT_DURATION_WINDOW = 0.25  # seconds to track event duration (first-to-last timestamp)
DEFAULT_TEMPORAL_COHERENCE_THRESHOLD = 0.70  # weighted score must exceed this when temporal signal is strong
DEFAULT_TEMPORAL_COHERENCE_WEIGHT = 0.25  # how much to boost score when temporal coherence is strong
DEFAULT_TEMPORAL_WINDOW_EXPANSION = 1.5  # expand the temporal window for multi-scale temporal context


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
        temporal_window = _positive_float(
            cfg.get("temporal_window"), DEFAULT_TEMPORAL_WINDOW)
        temporal_precedence = _positive_float(
            cfg.get("temporal_precedence"), DEFAULT_TEMPORAL_PRECEDENCE)

        latest_timestamp = 0.0
        selected: Dict[str, Tuple[ModalityScore, float]] = {}
        event_starts: Dict[str, float] = {}  # track first timestamp per modality for duration
        event_ends: Dict[str, float] = {}   # track last timestamp per modality for duration
        temporal_context: Dict[str, List[float]] = {}  # store timestamps per modality for multi-scale analysis
        for item in modality_scores:
            if not isinstance(item, ModalityScore):
                continue
            ts = float(item.timestamp)
            latest_timestamp = max(latest_timestamp, ts)
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
                # Track event duration: update start on first entry, end on each new entry
                if modality not in event_starts or ts < event_starts[modality]:
                    event_starts[modality] = ts
                event_ends[modality] = ts
                # Build temporal context: collect timestamps for multi-scale analysis
                if modality not in temporal_context:
                    temporal_context[modality] = []
                temporal_context[modality].append(ts)

        # Compute ambient baseline adjustment: normalize against rolling buffer's
        # ambient stats if available. This boosts scores that stand out from
        # typical activity levels.
        ambient_adjustment = 1.0
        ambient_features = cfg.get("ambient_features", {})
        ambient_window = _positive_float(cfg.get("ambient_window"), 30)
        ambient_mean = cfg.get("ambient_mean", {})
        ambient_std = cfg.get("ambient_std", {})

        if ambient_features and ambient_mean and ambient_std:
            # Compute Z-score normalization for each modality's raw features
            # We compute a composite Z-score per modality, then apply boost once
            for modality in selected:
                if modality not in ambient_features:
                    continue
                mod_features = ambient_features[modality]
                mod_mean = ambient_mean.get(modality, {})
                mod_std = ambient_std.get(modality, {})
                z_sum = 0.0
                feat_count = 0
                for feat in mod_features:
                    if feat in mod_mean and feat in mod_std:
                        mean_val = mod_mean[feat]
                        std_val = mod_std[feat]
                        raw_val = selected[modality][0].raw_features.get(feat, mean_val)
                        # Z-score: how many standard deviations from the ambient mean?
                        z = (raw_val - mean_val["mean"]) / std_val["std"] if std_val["std"] > 0 else 0.0
                        # Accumulate Z-scores across features
                        z_sum += abs(z)
                        feat_count += 1

        # Apply Z-score boost: amplify the modality with strongest deviation from ambient
        z_score_boost = cfg.get("z_score_boost", 1.0)
        if z_sum > 0 and feat_count > 0:
            avg_z = z_sum / feat_count
            # Apply boost to the modality with strongest Z-score deviation
            for modality in selected:
                if modality in ambient_features and avg_z > 0:
                    z = (selected[modality][0].raw_features.get("brightness", 0) -
                         ambient_mean["VISION"]["brightness"]["mean"]) / ambient_std["VISION"]["brightness"]["std"]
                    if z > 0:
                        selected[modality] = (selected[modality][0], selected[modality][1] * z_score_boost)

        # Compute weighted average across selected modalities
        weighted_score = 0.0
        weight_sum = 0.0
        for modality, (item, effective) in selected.items():
            mod_weight = weights.get(modality, 1.0)
            weighted_score += mod_weight * effective
            weight_sum += mod_weight

        if weight_sum > 0:
            weighted_score /= weight_sum

        # Apply ambient adjustment if configured
        if ambient_adjustment != 1.0:
            weighted_score = weighted_score * ambient_adjustment

        # Determine the final decision based on threshold and confidence
        decision = INDETERMINATE
        if weighted_score >= threshold:
            decision = EMERGENCY
        elif weighted_score < threshold:
            decision = NORMAL

        # Build the result object
        result_dict = {
            "score": weighted_score,
            "confidence": weighted_score,  # Use score as proxy for confidence
            "timestamp": latest_timestamp,
            "modality_type": FUSION,
            "raw_features": {},  # Could aggregate raw features from selected modalities
            "decision": decision,
            "modalities": list(selected.keys()),
            "weights": weights,
            "threshold": threshold,
            "min_confidence": min_confidence,
            "ambient_features": ambient_features,
            "ambient_mean": ambient_mean,
            "ambient_std": ambient_std,
            "z_sum": z_sum,
            "z_score_boost": z_score_boost,
        }
        result = ModalityScore(
            score=weighted_score,
            confidence=weighted_score,
            timestamp=latest_timestamp,
            modality_type=FUSION,
            raw_features={},
            decision=decision,
        )
        # Attach metadata as dict attributes
        result.metadata = result_dict
        return result

