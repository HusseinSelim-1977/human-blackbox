"""Experimental enhanced fusion strategies for Human BlackBox.

Status: **experimental extension**, not wired into
:class:`modules.pipeline.EmergencyPipeline` by default. The canonical fusion
implementation is :class:`modules.fusion.FusionStrategy`; this module keeps
alternative adaptive/dominant/gated modes available for experiments without
changing the shipped pipeline.

Moves beyond simple weighted averaging to implement:
1. Adaptive weights based on confidence
2. Dominant modality detection
3. Confidence-gated fusion logic
4. Multiple fusion modes for different scenarios

Implements the ``FusionStrategy`` contract from ``docs/CONTRACTS.md``.
"""

from __future__ import annotations

import math
from typing import Dict, List, Literal, Optional, Tuple, Union

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

# ----------------------------------------------------------------------
# Configuration Constants
# ----------------------------------------------------------------------
DEFAULT_WEIGHTS = {
    "AUDIO": 0.35,
    "MOTION": 0.40,
    "VISION": 0.25,
}

DEFAULT_THRESHOLDS = {
    "AUDIO": 0.65,
    "MOTION": 0.60,
    "VISION": 0.70,
}

DEFAULT_CONFIDENCE = 0.30  # Threshold for "high confidence"
DOMINANT_THRESHOLD = 0.35  # Weight threshold for dominant modality
MIN_MODALITIES = 1  # Minimum modalities for EMERGENCY (0 = any count)

# ----------------------------------------------------------------------
# Utility Functions
# ----------------------------------------------------------------------
def _clamp01(value: object, default: float = 0.0) -> float:
    """Coerce to float and clamp into [0.0, 1.0]."""
    try:
        number = float(value)
    except (TypeError, ValueError):
        return default
    return max(0.0, min(1.0, number))


def _positive_float(value: object, default: float) -> float:
    """Coerce to positive float."""
    try:
        number = float(value)
    except (TypeError, ValueError):
        return default
    return max(0.0, number)


def _normalize_weights(
    weights: Dict[str, float],
    modalities: List[str],
) -> Dict[str, float]:
    """Normalize weights to sum to 1.0 across active modalities."""
    total = sum(weights.get(m, 0.0) for m in modalities)
    if total <= 0.0:
        return {m: 1.0 / len(modalities) for m in modalities}
    return {m: weights[m] / total for m in modalities}


def _find_dominant_modality(
    scores: List[ModalityScore],
    weight_threshold: float = DOMINANT_THRESHOLD,
) -> Optional[str]:
    """
    Find the modality with the highest effective weight.
    
    Returns the modality name if it has weight >= threshold, else None.
    """
    weights = _normalize_weights(
        {m: DEFAULT_WEIGHTS.get(m, 0.3) for m in set(s.modality_type for s in scores)},
        [s.modality_type for s in scores],
    )
    dominant = max(weights, key=lambda m: weights[m])
    return dominant if weights[dominant] >= weight_threshold else None


def _compute_confidence_weighted_score(
    scores: List[ModalityScore],
    weights: Dict[str, float],
    min_confidence: float,
) -> Tuple[float, float]:
    """
    Compute weighted score with confidence gating.
    
    Scores below min_confidence are down-weighted by their own confidence.
    Returns (weighted_score, blended_confidence).
    """
    effective_scores = {}
    blended_confidence = {}
    
    for score in scores:
        modality = str(score.modality_type).upper()
        score_val = _clamp01(score.score)
        conf = _clamp01(score.confidence)
        
        # Confidence-gated effective score
        effective = score_val if conf >= min_confidence else score_val * conf
        effective_scores[modality] = effective
        
        # Blend confidence: high-confidence scores contribute more
        conf_contribution = conf if conf >= min_confidence else conf * conf
        blended_confidence[modality] = conf_contribution
    
    total_weight = sum(weights[m] for m in effective_scores)
    if total_weight <= 0.0:
        return (0.0, 0.0)
    
    weighted_score = sum(
        weights[m] * effective_scores[m] for m in effective_scores
    ) / total_weight
    
    blended_conf = sum(
        weights[m] * blended_confidence[m] for m in blended_confidence
    ) / total_weight
    
    return (weighted_score, blended_conf)


def _compute_entropy_weighted_score(
    scores: List[ModalityScore],
    weights: Dict[str, float],
) -> Tuple[float, float]:
    """
    Compute entropy-weighted score for uncertainty-aware fusion.
    
    Modalities with more "information" (higher score variance) get more weight.
    """
    if not scores:
        return (0.0, 1.0)
    
    # Normalize scores to [0, 1]
    norm_scores = [_clamp01(s.score) for s in scores]
    max_score = max(norm_scores) if norm_scores else 1.0
    
    # Entropy: -sum(p * log(p)) where p is normalized score
    entropy = 0.0
    for s in norm_scores:
        if s > 0.01:  # Avoid log(0)
            entropy -= (s / max_score) * math.log(s / max_score + 0.01)
    
    # Higher entropy = more information = higher weight
    entropy_weight = 1.0 + (entropy / math.log(len(scores) + 1)) * 0.5
    
    weighted = sum(weights[m] * _clamp01(s.score) for s, m in zip(scores, [s.modality_type for s in scores]))
    
    return (weighted * entropy_weight, entropy_weight)


# ----------------------------------------------------------------------
# Fusion Strategy Classes
# ----------------------------------------------------------------------
class ConfidenceAdaptiveFusion:
    """
    Adaptive fusion that adjusts weights based on input confidence.
    
    When modalities have high confidence, weights stay near defaults.
    When confidence is low, weights shift toward the highest-scoring modality.
    """
    
    def __init__(
        self,
        weights: Optional[Dict[str, float]] = None,
        threshold: float = 0.65,
        min_confidence: float = DEFAULT_CONFIDENCE,
        require_two_modalities: bool = False,
    ):
        self.config = weights if isinstance(weights, dict) else {}
        self.weights = weights or DEFAULT_WEIGHTS.copy()
        self.threshold = threshold
        self.min_confidence = min_confidence
        self.require_two = require_two_modalities
    
    def decide(
        self,
        modality_scores: List[ModalityScore],
        config: Optional[Dict] = None,
    ) -> ModalityScore:
        """Fuse modality scores using confidence-adaptive weights."""
        source = config if isinstance(config, dict) and config else self.config
        cfg = source.get("confidence_adaptive", {})
        
        # Override defaults with config
        weights = _normalize_weights(
            {
                m: _positive_float(cfg.get(m, self.weights.get(m, 0.3)), 0.3)
                for m in self.weights
            },
            [s.modality_type for s in modality_scores],
        )
        threshold = _positive_float(cfg.get("threshold"), self.threshold)
        min_conf = _positive_float(cfg.get("min_confidence"), self.min_confidence)
        
        if not modality_scores:
            return self._result(
                0.0, 0.0, INDETERMINATE, 0.0, threshold, {}, weights, 0,
            )
        
        # Compute confidence-weighted score
        weighted_score, confidence = _compute_confidence_weighted_score(
            modality_scores, weights, min_conf,
        )
        
        # Find dominant modality
        dominant = _find_dominant_modality(modality_scores, DOMINANT_THRESHOLD)
        
        # Decision: use threshold or dominant modality's threshold
        decision = EMERGENCY if weighted_score >= threshold else NORMAL
        
        # Agreement: how many modalities agree with the decision
        agreement = sum(
            1 for s in modality_scores if s.decision == decision
        ) / len(modality_scores)
        
        per_modality = {
            modality: {
                "score": _clamp01(s.score),
                "confidence": _clamp01(s.confidence),
                "decision": s.decision,
                "effective_score": _compute_confidence_weighted_score(
                    [s], {modality: weights[modality]}, min_conf,
                )[0],
                "weight": weights.get(modality, 0.0),
            }
            for s, modality in zip(modality_scores, [s.modality_type for s in modality_scores])
        }
        
        return self._result(
            _clamp01(weighted_score), _clamp01(confidence), decision,
            modality_scores[-1].timestamp if modality_scores else 0.0,
            threshold, per_modality, weights, len(modality_scores),
            agreement, dominant,
        )
    
    @staticmethod
    def _result(
        score: float, confidence: float, decision: str,
        timestamp: float, threshold: float, per_modality: dict,
        weights: dict, modalities_count: int, agreement: float,
        dominant: Optional[str] = None,
    ) -> ModalityScore:
        return ModalityScore(
            score=score,
            confidence=confidence,
            decision=decision,
            timestamp=timestamp,
            modality_type=FUSION,
            raw_features={
                "per_modality": per_modality,
                "weights_used": weights,
                "weighted_score": score,
                "threshold": threshold,
                "modalities_count": modalities_count,
                "agreement": agreement,
                "dominant_modality": dominant,
                "fusion_mode": "confidence_adaptive",
            },
        )


class DominantModalityFusion:
    """
    Fusion that lets a single dominant modality drive the decision.
    
    When one modality clearly dominates (weight > threshold), its score
    becomes the primary decision signal.
    """
    
    def __init__(
        self,
        weights: Optional[Dict[str, float]] = None,
        threshold: float = 0.65,
        dominant_threshold: float = DOMINANT_THRESHOLD,
        min_confidence: float = DEFAULT_CONFIDENCE,
        require_two_modalities: bool = False,
    ):
        self.config = weights if isinstance(weights, dict) else {}
        self.weights = weights or DEFAULT_WEIGHTS.copy()
        self.threshold = threshold
        self.dominant_threshold = dominant_threshold
        self.min_confidence = min_confidence
        self.require_two = require_two_modalities
    
    def decide(
        self,
        modality_scores: List[ModalityScore],
        config: Optional[Dict] = None,
    ) -> ModalityScore:
        """Fuse using dominant modality logic."""
        source = config if isinstance(config, dict) and config else self.config
        cfg = source.get("dominant_modality", {})
        
        weights = _normalize_weights(
            {
                m: _positive_float(cfg.get(m, self.weights.get(m, 0.3)), 0.3)
                for m in self.weights
            },
            [s.modality_type for s in modality_scores],
        )
        threshold = _positive_float(cfg.get("threshold"), self.threshold)
        dominant_thresh = _positive_float(
            cfg.get("dominant_threshold"), self.dominant_threshold
        )
        min_conf = _positive_float(cfg.get("min_confidence"), self.min_confidence)
        
        if not modality_scores:
            return self._result(
                0.0, 0.0, INDETERMINATE, 0.0, threshold, {}, weights, 0,
            )
        
        # Find dominant modality
        dominant = _find_dominant_modality(modality_scores, dominant_thresh)
        
        if dominant:
            # Dominant mode: use dominant modality's score primarily
            dominant_score = _clamp01(
                next(s.score for s in modality_scores if s.modality_type == dominant)
            )
            dominant_conf = _clamp01(
                next(s.confidence for s in modality_scores if s.modality_type == dominant)
            )
            decision = EMERGENCY if dominant_score >= threshold else NORMAL
        else:
            # Fallback to weighted average
            weighted_score, confidence = _compute_confidence_weighted_score(
                modality_scores, weights, min_conf,
            )
            decision = EMERGENCY if weighted_score >= threshold else NORMAL
        
        agreement = sum(
            1 for s in modality_scores if s.decision == decision
        ) / len(modality_scores)
        
        per_modality = {
            modality: {
                "score": _clamp01(s.score),
                "confidence": _clamp01(s.confidence),
                "decision": s.decision,
                "weight": weights.get(modality, 0.0),
            }
            for s, modality in zip(modality_scores, [s.modality_type for s in modality_scores])
        }
        
        return self._result(
            _clamp01(weighted_score if not dominant else dominant_score),
            _clamp01(confidence if not dominant else dominant_conf),
            decision,
            modality_scores[-1].timestamp if modality_scores else 0.0,
            threshold, per_modality, weights, len(modality_scores),
            agreement, dominant,
        )
    
    @staticmethod
    def _result(
        score: float, confidence: float, decision: str,
        timestamp: float, threshold: float, per_modality: dict,
        weights: dict, modalities_count: int, agreement: float,
        dominant: Optional[str] = None,
    ) -> ModalityScore:
        return ModalityScore(
            score=score,
            confidence=confidence,
            decision=decision,
            timestamp=timestamp,
            modality_type=FUSION,
            raw_features={
                "per_modality": per_modality,
                "weights_used": weights,
                "weighted_score": score,
                "threshold": threshold,
                "modalities_count": modalities_count,
                "agreement": agreement,
                "dominant_modality": dominant,
                "fusion_mode": "dominant_modality",
            },
        )


class ConfidenceGatedFusion:
    """
    Fusion with multiple gating modes based on confidence levels.
    
    - HIGH_CONFIDENCE: Single modality can decide (if conf > 0.7)
    - BALANCED: Standard weighted average (0.4 - 0.7)
    - LOW_CONFIDENCE: Require multiple modalities or use entropy weighting
    """
    
    def __init__(
        self,
        weights: Optional[Dict[str, float]] = None,
        threshold: float = 0.65,
        high_confidence: float = 0.70,
        low_confidence: float = 0.40,
        require_two_modalities: bool = False,
    ):
        self.config = weights if isinstance(weights, dict) else {}
        self.weights = weights or DEFAULT_WEIGHTS.copy()
        self.threshold = threshold
        self.high_confidence = high_confidence
        self.low_confidence = low_confidence
        self.require_two = require_two_modalities
    
    def decide(
        self,
        modality_scores: List[ModalityScore],
        config: Optional[Dict] = None,
    ) -> ModalityScore:
        """Fuse using confidence-gated logic."""
        source = config if isinstance(config, dict) and config else self.config
        cfg = source.get("confidence_gated", {})
        
        weights = _normalize_weights(
            {
                m: _positive_float(cfg.get(m, self.weights.get(m, 0.3)), 0.3)
                for m in self.weights
            },
            [s.modality_type for s in modality_scores],
        )
        threshold = _positive_float(cfg.get("threshold"), self.threshold)
        high_conf = _positive_float(cfg.get("high_confidence"), self.high_confidence)
        low_conf = _positive_float(cfg.get("low_confidence"), self.low_confidence)
        
        if not modality_scores:
            return self._result(
                0.0, 0.0, INDETERMINATE, 0.0, threshold, {}, weights, 0,
            )
        
        # Compute average confidence
        avg_conf = sum(_clamp01(s.confidence) for s in modality_scores) / len(modality_scores)
        
        # Determine gating mode
        if avg_conf >= high_conf:
            # HIGH_CONFIDENCE: Let the highest score win
            sorted_scores = sorted(modality_scores, key=lambda s: s.score, reverse=True)
            top_score = _clamp01(sorted_scores[0].score)
            top_conf = _clamp01(sorted_scores[0].confidence)
            decision = EMERGENCY if top_score >= threshold else NORMAL
            mode = "high_confidence"
        elif avg_conf <= low_conf:
            # LOW_CONFIDENCE: Require multiple modalities or use entropy
            weighted_score, confidence = _compute_confidence_weighted_score(
                modality_scores, weights, low_conf,
            )
            if self.require_two and len(modality_scores) < 2:
                decision = NORMAL  # Fallback to weighted
                mode = "low_confidence_gated"
            else:
                decision = EMERGENCY if weighted_score >= threshold else NORMAL
                mode = "low_confidence"
        else:
            # BALANCED: Standard weighted average
            weighted_score, confidence = _compute_confidence_weighted_score(
                modality_scores, weights, low_conf,
            )
            decision = EMERGENCY if weighted_score >= threshold else NORMAL
            mode = "balanced"
        
        agreement = sum(
            1 for s in modality_scores if s.decision == decision
        ) / len(modality_scores)
        
        per_modality = {
            modality: {
                "score": _clamp01(s.score),
                "confidence": _clamp01(s.confidence),
                "decision": s.decision,
                "weight": weights.get(modality, 0.0),
            }
            for s, modality in zip(modality_scores, [s.modality_type for s in modality_scores])
        }
        
        return self._result(
            _clamp01(weighted_score if mode != "high_confidence" else top_score),
            _clamp01(confidence if mode != "high_confidence" else top_conf),
            decision,
            modality_scores[-1].timestamp if modality_scores else 0.0,
            threshold, per_modality, weights, len(modality_scores),
            agreement, mode,
        )
    
    @staticmethod
    def _result(
        score: float, confidence: float, decision: str,
        timestamp: float, threshold: float, per_modality: dict,
        weights: dict, modalities_count: int, agreement: float,
        mode: str = "balanced",
    ) -> ModalityScore:
        return ModalityScore(
            score=score,
            confidence=confidence,
            decision=decision,
            timestamp=timestamp,
            modality_type=FUSION,
            raw_features={
                "per_modality": per_modality,
                "weights_used": weights,
                "weighted_score": score,
                "threshold": threshold,
                "modalities_count": modalities_count,
                "agreement": agreement,
                "fusion_mode": mode,
            },
        )


# ----------------------------------------------------------------------
# Factory for Easy Selection
# ----------------------------------------------------------------------
def _fusion_section(config: object) -> dict:
    """Accept either a full system config or a bare fusion sub-config."""
    if not isinstance(config, dict):
        return {}
    section = config.get("fusion")
    return section if isinstance(section, dict) else config


class FusionStrategy:
    """
    Unified fusion strategy that adapts based on configuration.
    
    Supports multiple fusion modes:
    - ``"adaptive"``: Confidence-adaptive weights
    - ``"dominant"``: Single modality dominance
    - ``"gated"``: Multi-level confidence gating
    - ``"simple"``: Classic weighted average (default)
    """
    
    def __init__(
        self,
        mode: Literal["adaptive", "dominant", "gated", "simple"] = "simple",
        config: Optional[Dict] = None,
    ):
        self.mode = mode
        self.config = config if isinstance(config, dict) else {}
        
        fusion_weights = _fusion_section(self.config)
        self._adaptive = ConfidenceAdaptiveFusion(weights=fusion_weights)
        self._dominant = DominantModalityFusion(weights=fusion_weights)
        self._gated = ConfidenceGatedFusion(weights=fusion_weights)
    
    def decide(
        self,
        modality_scores: List[ModalityScore],
        config: Optional[Dict] = None,
    ) -> ModalityScore:
        """Fuse modality scores using the configured strategy."""
        source = config if isinstance(config, dict) and config else self.config
        cfg = _fusion_section(source)
        
        # Determine mode from config or default
        fusion_mode = cfg.get("mode", self.mode)
        
        # Instantiate the right strategy
        if fusion_mode == "adaptive":
            return self._adaptive.decide(modality_scores, source)
        elif fusion_mode == "dominant":
            return self._dominant.decide(modality_scores, source)
        elif fusion_mode == "gated":
            return self._gated.decide(modality_scores, source)
        else:
            # "simple" mode: basic weighted average
            return self._simple_decide(modality_scores, source)
    
    def _simple_decide(
        self,
        modality_scores: List[ModalityScore],
        config: Dict,
    ) -> ModalityScore:
        """Classic weighted average with optional threshold."""
        config = _fusion_section(config)
        weights = _normalize_weights(
            {
                m: _positive_float(
                    config.get("weights", {}).get(m, DEFAULT_WEIGHTS.get(m, 0.3)),
                    0.3,
                )
                for m in DEFAULT_WEIGHTS
            },
            [s.modality_type for s in modality_scores],
        )
        threshold = _positive_float(
            config.get("threshold", DEFAULT_THRESHOLDS.get("MOTION", 0.65)), 0.65
        )
        
        if not modality_scores:
            return self._result(
                0.0, 0.0, INDETERMINATE, 0.0, threshold, {}, weights, 0,
            )
        
        # Simple weighted average
        weighted_score = sum(
            weights[m] * _clamp01(s.score)
            for s, m in zip(modality_scores, [s.modality_type for s in modality_scores])
        )
        confidence = sum(
            weights[m] * _clamp01(s.confidence)
            for s, m in zip(modality_scores, [s.modality_type for s in modality_scores])
        )
        
        decision = EMERGENCY if weighted_score >= threshold else NORMAL
        
        agreement = sum(
            1 for s in modality_scores if s.decision == decision
        ) / len(modality_scores)
        
        per_modality = {
            modality: {
                "score": _clamp01(s.score),
                "confidence": _clamp01(s.confidence),
                "decision": s.decision,
                "weight": weights.get(modality, 0.0),
            }
            for s, modality in zip(modality_scores, [s.modality_type for s in modality_scores])
        }
        
        return self._result(
            _clamp01(weighted_score), _clamp01(confidence), decision,
            modality_scores[-1].timestamp if modality_scores else 0.0,
            threshold, per_modality, weights, len(modality_scores),
            agreement,
        )
    
    @staticmethod
    def _result(
        score: float, confidence: float, decision: str,
        timestamp: float, threshold: float, per_modality: dict,
        weights: dict, modalities_count: int, agreement: float,
    ) -> ModalityScore:
        return ModalityScore(
            score=score,
            confidence=confidence,
            decision=decision,
            timestamp=timestamp,
            modality_type=FUSION,
            raw_features={
                "per_modality": per_modality,
                "weights_used": weights,
                "weighted_score": score,
                "threshold": threshold,
                "modalities_count": modalities_count,
                "agreement": agreement,
                "fusion_mode": "simple",
            },
        )
