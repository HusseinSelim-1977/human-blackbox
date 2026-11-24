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
            for modality in selected:
                if modality not in ambient_features:
                    continue
                mod_features = ambient_features[modality]
                mod_mean = ambient_mean.get(modality, {})
                mod_std = ambient_std.get(modality, {})
                for feat in mod_features:
                    if feat in mod_mean and feat in mod_std:
                        mean_val = mod_mean[feat]
                        std_val = mod_std[feat]
                        raw_val = selected[modality][0].raw_features.get(feat, mean_val)
                        # Z-score: how many standard deviations from the ambient mean?
                        z = (raw_val - mean_val) / std_val if std_val > 0 else 0.0
                        # Boost effective score by 10-20% when Z-score is strong
                        boost = 1.0 + 0.15 * abs(z)
                        selected[modality] = (selected[modality][0], effective * boost)
# Only relevant when we have both motion and audio in the selection
temporal_coherence = 0.0
if "MOTION" in selected and "AUDIO" in selected:
    motion_start = event_starts.get("MOTION", latest_timestamp)
    audio_end = event_ends.get("AUDIO", latest_timestamp)
    motion_end = event_ends.get("MOTION", latest_timestamp)
    audio_start = event_starts.get("AUDIO", latest_timestamp)
    # Temporal window: how much time spans between motion start and audio end?
temporal_span = max(audio_end, motion_end) - min(motion_start, audio_start)
		# Normalize to [0, 1] using the configured temporal window
	    normalized_span = _clamp01(temporal_span / temporal_window, 0.0)
	    # Temporal precedence: did motion lead audio? (positive if motion_start < audio_end)
	    motion_leads_audio = (motion_start < audio_end)
	    precedence_signal = 1.0 if motion_leads_audio else 0.0
	    # Temporal coherence score: blend normalized span with precedence signal
	    temporal_coherence = 0.5 * normalized_span + 0.5 * precedence_signal

        if not selected:
            # ASSUMPTION: input that contains no usable known modality is
            # treated like empty input and reported as INDETERMINATE.
            return self._result(
                0.0, 0.0, INDETERMINATE, latest_timestamp, threshold,
                {}, {}, 0, 0.0, event_starts, event_ends)

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
            len(selected), agreement, event_starts, event_ends,
            temporal_coherence, ambient_features, ambient_mean, ambient_std)

    @staticmethod
    def _result(score: float, confidence: float, decision: str,
                timestamp: float, threshold: float, per_modality: dict,
                weights_used: dict, modalities_count: int,
                agreement: float = 0.0,
                event_starts: Dict[str, float] = None,
                event_ends: Dict[str, float] = None,
                temporal_coherence: float = 0.0,
                ambient_features: dict = None,
                ambient_mean: dict = None,
                ambient_std: dict = None) -> ModalityScore:
        # Normalize event duration values to [0, 1] range for raw_features
        # Compute event duration as the span from first to last timestamp
        if event_starts and event_ends and len(event_starts) > 0:
            duration_span = max(event_ends.values()) - min(event_starts.values())
            # Use the first modality's timestamp as reference (handles any modality)
            first_modality = next(iter(event_starts.keys()), "MOTION")
            event_start_norm = _clamp01(event_starts[first_modality], 0.0)
            event_end_norm = _clamp01(event_ends[first_modality], 1.0)
        else:
            event_start_norm = _clamp01(event_starts, 0.0) if event_starts else 0.0
            event_end_norm = _clamp01(event_ends, 0.0) if event_ends else 0.0
        # Only include temporal_coherence in raw_features when it has a meaningful value
        temporal_coherence_feature = _clamp01(temporal_coherence, 0.0)
        # Build ambient baseline context: include mean and std for each tracked feature
        ambient_context = {}
        if ambient_features and ambient_mean and ambient_std:
            ambient_context["ambient_features"] = ambient_features
            ambient_context["ambient_mean"] = ambient_mean
            ambient_context["ambient_std"] = ambient_std
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
                "event_start": event_start_norm,
                "event_end": event_end_norm,
                "duration_span": duration_span if event_starts and event_ends else 0.0,
                "temporal_coherence": temporal_coherence_feature,
                **ambient_context,
            },
        )
