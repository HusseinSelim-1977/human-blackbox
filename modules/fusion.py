"""Weighted, confidence-gated fusion of per-modality detector scores.

Implements the ``FusionStrategy`` contract from ``docs/CONTRACTS.md``.
"""

from __future__ import annotations

from typing import Any, Dict, List, Tuple

from modules.modality_types import (
    AUDIO,
    EMERGENCY,
    FUSION,
    INDETERMINATE,
    MOTION,
    NORMAL,
    ModalityScore,
)

DEFAULT_WEIGHTS = {"AUDIO": 0.35, "MOTION": 0.40, "VISION": 0.25}
DEFAULT_THRESHOLD = 0.65
DEFAULT_MIN_CONFIDENCE = 0.30
DEFAULT_REQUIRE_TWO = True
DEFAULT_TEMPORAL_WINDOW = 0.30  # seconds to consider for temporal coherence
DEFAULT_TEMPORAL_PRECEDENCE = 0.15  # motion must precede audio by this much
DEFAULT_Z_SCORE_BOOST = 0.25  # max relative boost per 1 sigma of ambient deviation
DEFAULT_Z_SCORE_CAP = 3.0  # cap on the averaged |z| used for the boost


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


def _stat(value: Any, key: str) -> float:
    """Read ``key`` from a stat dict, or treat the value as the number itself."""
    if isinstance(value, dict):
        value = value.get(key, 0.0)
    try:
        return float(value)
    except (TypeError, ValueError):
        return 0.0


def _compute_agreement(effective_scores: List[float]) -> float:
    """1.0 when the top two scores agree, 0.5 when they contradict."""
    if len(effective_scores) == 0:
        return 0.0
    if len(effective_scores) == 1:
        return 1.0
    ordered = sorted(effective_scores, reverse=True)
    return 1.0 if abs(ordered[0] - ordered[1]) < 0.15 else 0.5


def _lookup_modality(table: object, modality: str) -> dict:
    """Case-insensitive lookup of a modality entry in an ambient stats table."""
    if not isinstance(table, dict):
        return {}
    entry = table.get(modality)
    if entry is None:
        entry = table.get(modality.lower())
    return entry if isinstance(entry, dict) else {}


def _ambient_features(cfg: dict) -> Dict[str, List[str]]:
    """Resolve per-modality ambient feature lists from either config shape."""
    explicit = cfg.get("ambient_features")
    if isinstance(explicit, dict) and explicit:
        return explicit
    baselines = cfg.get("ambient_baselines")
    if not isinstance(baselines, dict):
        return {}
    per_modality = baselines.get("per_modality")
    if not isinstance(per_modality, dict):
        return {}
    resolved: Dict[str, List[str]] = {}
    for modality, spec in per_modality.items():
        features = spec.get("features") if isinstance(spec, dict) else None
        if isinstance(features, list) and features:
            resolved[str(modality).upper()] = [str(f) for f in features]
    return resolved


def _apply_ambient_boost(
    selected: Dict[str, Tuple[ModalityScore, float]],
    cfg: dict,
) -> Dict[str, float]:
    """Boost effective scores that deviate strongly from ambient baselines.

    Returns the applied per-modality boost factors (empty when inactive).
    The boost is a no-op unless runtime ``ambient_mean``/``ambient_std`` stats
    are supplied; the repository config only declares which features to track.
    """
    features = _ambient_features(cfg)
    mean_table = cfg.get("ambient_mean")
    std_table = cfg.get("ambient_std")
    if not features or not isinstance(mean_table, dict) or not isinstance(std_table, dict):
        return {}

    strength = _positive_float(cfg.get("z_score_boost"), DEFAULT_Z_SCORE_BOOST)
    cap = _positive_float(cfg.get("z_score_cap"), DEFAULT_Z_SCORE_CAP)
    boosts: Dict[str, float] = {}

    for modality, (item, effective) in selected.items():
        mod_features = features.get(modality, [])
        mod_mean = _lookup_modality(mean_table, modality)
        mod_std = _lookup_modality(std_table, modality)
        z_values: List[float] = []
        for feat in mod_features:
            if feat not in mod_mean or feat not in mod_std:
                continue
            raw = item.raw_features.get(feat)
            if raw is None:
                continue
            try:
                raw_val = float(raw)
            except (TypeError, ValueError):
                continue
            std = _stat(mod_std[feat], "std")
            if std <= 0:
                continue
            z_values.append(abs((raw_val - _stat(mod_mean[feat], "mean")) / std))
        if not z_values:
            continue
        avg_z = min(sum(z_values) / len(z_values), cap)
        factor = 1.0 + strength * avg_z
        selected[modality] = (item, _clamp01(effective * factor))
        boosts[modality] = factor
    return boosts


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
        event_start: Dict[str, float] = {}
        event_end: Dict[str, float] = {}

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
                if modality not in event_start or ts < event_start[modality]:
                    event_start[modality] = ts
                event_end[modality] = ts

        # Ambient normalization: boost scores that stand out from the rolling
        # baseline. Inactive unless runtime mean/std stats are configured.
        ambient_boosts = _apply_ambient_boost(selected, cfg)

        weighted_score = 0.0
        weight_sum = 0.0
        for modality, (_item, effective) in selected.items():
            mod_weight = weights.get(modality, 1.0)
            weighted_score += mod_weight * effective
            weight_sum += mod_weight
        if weight_sum > 0:
            weighted_score /= weight_sum

        # Temporal coherence: all modalities within one window and motion
        # leading audio by at least the precedence interval is a strong signal.
        temporal_coherence = 0.0
        if len(selected) > 1:
            span = max(event_end.values()) - min(event_start.values())
            if span <= temporal_window:
                temporal_coherence = 1.0
                if (MOTION in selected and AUDIO in selected
                        and temporal_precedence > 0):
                    lead = event_start.get(AUDIO, 0.0) - event_start.get(MOTION, 0.0)
                    # Audio leading motion by a clear margin contradicts the
                    # expected impact-then-scream ordering.
                    if lead < -temporal_precedence:
                        temporal_coherence = 0.5
            else:
                temporal_coherence = 0.5

        if len(selected) == 0:
            decision = INDETERMINATE
        elif weighted_score >= threshold:
            decision = EMERGENCY
        else:
            decision = NORMAL
        if require_two and len(selected) == 1 and decision == EMERGENCY:
            decision = NORMAL

        weights_used = {
            modality: (weights.get(modality, 1.0) / weight_sum if weight_sum > 0 else 0.0)
            for modality in selected
        }
        duration_span = (
            max(event_end.values()) - min(event_start.values())
            if event_start and event_end else 0.0
        )

        result_dict = {
            "per_modality": {
                mod: {"effective_score": effective}
                for mod, (_item, effective) in selected.items()
            },
            "weights_used": weights_used,
            "weighted_score": weighted_score,
            "threshold": threshold,
            "modalities_count": len(selected),
            "agreement": _compute_agreement([e for _i, e in selected.values()]),
            "event_start": event_start,
            "event_end": event_end,
            "duration_span": duration_span,
            "temporal_coherence": temporal_coherence,
        }
        if ambient_boosts:
            result_dict["ambient_boosts"] = ambient_boosts

        return ModalityScore(
            score=weighted_score,
            confidence=weighted_score,
            timestamp=latest_timestamp,
            modality_type=FUSION,
            raw_features=result_dict,
            decision=decision,
        )
