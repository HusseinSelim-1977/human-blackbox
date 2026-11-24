# Fusion Strategy Analysis Report

## Executive Summary

Human BlackBox leverages **`fusion_enhanced.py`** as its primary fusion engine, implementing a factory pattern via the `FusionStrategy` class. This design supports four distinct fusion modes that directly address the four key focus areas:

1. **Adaptive weights**: Confidence-driven weight adjustment
2. **Dominant modality**: Single-sensor dominance detection
3. **Confidence gating**: Multi-level confidence thresholds
4. **Simple baseline**: Classic weighted average for stability

The system currently runs in **"adaptive"** mode by default, with custom weights favoring audio input (`AUDIO=0.35`, `MOTION=0.40`, `VISION=0.25`).

---

## Current Fusion Modes

### 1. `confidence_adaptive` (Adaptive Weights)
**Purpose**: Adjusts weights dynamically based on input confidence levels.

**Logic**:
- High-confidence modalities retain default weights
- Low-confidence modalities are down-weighted by their own confidence
- Dominant modality detection identifies which modality should lead

**Best For**: Scenarios where sensor reliability varies (e.g., audio in noisy room vs. clear vision)

**Key Metadata**:
- `agreement`: How many modalities agree with the final decision
- `dominant_modality`: The modality with highest effective weight
- `per_modality`: Breakdown of each modality's effective score

### 2. `dominant_modality` (Dominant Modality Detection)
**Purpose**: Lets a single dominant modality drive the decision.

**Logic**:
- Identifies the modality with weight ≥ 0.35 (default threshold)
- Uses that modality's score as the primary decision signal
- Falls back to weighted average if no clear dominant exists

**Best For**: Edge cases where one sensor clearly outperforms others (e.g., close-range vision)

**Key Metadata**:
- `dominant_modality`: Explicitly tagged dominant modality
- `weighted_score`: Score from the dominant modality
- `fusion_mode`: Set to `"dominant_modality"` for downstream filtering

### 3. `confidence_gated` (Confidence-Gated Fusion)
**Purpose**: Multi-level gating based on average confidence levels.

**Logic**:
- **HIGH_CONFIDENCE** (≥0.70): Single modality can decide; top score wins
- **BALANCED** (0.40–0.70): Standard weighted average
- **LOW_CONFIDENCE** (≤0.40): Requires multiple modalities or entropy weighting

**Best For**: Adaptive behavior where confidence levels shift (e.g., transitioning from clear to ambiguous)

**Key Metadata**:
- `fusion_mode`: Distinguishes between high/balanced/low modes
- `modalities_count`: Tracks how many modalities contributed
- `per_modality`: Complete breakdown for each contributor

### 4. `simple` (Classic Weighted Average)
**Purpose**: Baseline behavior for backward compatibility.

**Logic**:
- Normalized weights across active modalities
- Weighted average of scores and confidences
- Optional threshold gate for decision making

**Best For**: Stable, predictable environments where defaults work well

---

## Configuration Options

### Mode Switching
Enable dynamic mode switching via the `fusion.mode` field in `raw_features`:

```python
# Example: Configure FusionStrategy with mode="adaptive"
fusion_strategy = FusionStrategy(mode="adaptive")
```

### Weight Customization
Default weights: `AUDIO=0.35`, `MOTION=0.40`, `VISION=0.25`

```python
weights = {
    "AUDIO": 0.35,
    "MOTION": 0.40,
    "VISION": 0.25,
}
```

### Thresholds
- **Decision threshold**: 0.65 (score ≥ threshold = EMERGENCY)
- **Dominant threshold**: 0.35 (weight ≥ threshold = dominant modality)
- **High confidence**: 0.70 (for gated mode)
- **Low confidence**: 0.40 (for gated mode)

---

## Metadata Enrichment

All fusion outputs include rich metadata in `raw_features`:

| Field | Description |
|-------|-------------|
| `per_modality` | Breakdown of each modality's score, confidence, decision, and weight |
| `weights_used` | The normalized weights applied in this fusion |
| `weighted_score` | Final fused score before threshold gate |
| `threshold` | The threshold used for decision making |
| `modalities_count` | How many modalities contributed |
| `agreement` | Fraction of modalities agreeing with the decision |
| `dominant_modality` | The modality with highest effective weight |
| `fusion_mode` | Which fusion mode was active (`adaptive`, `dominant`, `gated`, `simple`) |

---

## Recommendations

### 1. Enable Dynamic Mode Switching
Use the `fusion.mode` configuration to select the right strategy per scenario:

```python
# For stable conditions
fusion_strategy = FusionStrategy(mode="simple")

# For variable sensor reliability
fusion_strategy = FusionStrategy(mode="adaptive")

# For edge cases with strong signals
fusion_strategy = FusionStrategy(mode="dominant")
```

### 2. Leverage `agreement` for Uncertainty Quantification
The `agreement` metric reveals consensus among modalities:
- **High agreement** (>0.8): Confident decision
- **Medium agreement** (0.5–0.8): Moderate confidence
- **Low agreement** (<0.5): Consider secondary signals or higher threshold

### 3. Use `dominant_modality` for Edge Case Handling
When one modality clearly outperforms others (e.g., close-range vision), the `dominant_modality` mode ensures that modality leads the decision.

### 4. Implement Confidence-Gated Behavior
The `confidence_gated` mode provides three behavioral modes in one:
- **High confidence**: Let the best sensor win
- **Balanced**: Standard weighted average
- **Low confidence**: Require multiple modalities or use entropy weighting

---

## Implementation Notes

### Factory Pattern via `FusionStrategy`
The unified `FusionStrategy` class accepts a `mode` parameter and instantiates the appropriate sub-strategy:

```python
class FusionStrategy:
    def __init__(self, mode: Literal["adaptive", "dominant", "gated", "simple"] = "simple"):
        self._adaptive = ConfidenceAdaptiveFusion(...)
        self._dominant = DominantModalityFusion(...)
        self._gated = ConfidenceGatedFusion(...)
```

### Utility Functions
- **`_normalize_weights`**: Scales weights to sum to 1.0 across active modalities
- **`_find_dominant_modality`**: Identifies the modality with highest effective weight
- **`_compute_confidence_weighted_score`**: Blends scores with confidence gating
- **`_clamp01`**: Safe coercion to [0.0, 1.0] range

---

## Next Steps

1. **Review current pipeline wiring** in `modules/pipeline.py` to see which fusion mode is currently active
2. **Test edge cases** where confidence levels shift (e.g., audio in noisy room)
3. **Consider mode-switching logic** that adapts based on runtime conditions
4. **Leverage metadata fields** for downstream ML models or visualization layers

---

*Generated for Human BlackBox fusion strategy exploration*
