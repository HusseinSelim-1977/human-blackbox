import sys
sys.path.insert(0, "/home/MASTER-H/Documents/Bachelor Thesis/human-blackbox")

from modules.rolling_buffer import RollingBuffer
from modules.fusion import FusionStrategy
from modules.vision_detector import VisionDetector
from modules.audio_detector import AudioDetector
from modules.motion_detector import MotionDetector
from modules.types import VISION, AUDIO, MOTION, NORMAL, ModalityScore

# Initialize the rolling buffer with ambient stats
# Using a dummy encryption key since we're mostly interested in ambient stats
buffer = RollingBuffer(buffer_size_sec=50, encryption_key="ambient")

# Create a mock ambient stats dict
ambient_stats = {
    "vision": {"mean": {"brightness": 0.5, "contrast": 0.6}, "std": {"brightness": 0.2, "contrast": 0.15}},
    "audio": {"mean": {"amplitude": 0.4, "freq_energy": 0.35}, "std": {"amplitude": 0.15, "freq_energy": 0.12}},
    "motion": {"mean": {"accel": 0.3, "gyro": 0.25}, "std": {"accel": 0.12, "gyro": 0.1}}
}

# Create fusion layer with ambient features
fusion = FusionStrategy(
    config={
        "weights": {"vision": 0.5, "audio": 0.3, "motion": 0.2},
        "ambient_features": ambient_stats,
        "ambient_mean": ambient_stats,
        "ambient_std": ambient_stats,
        "z_score_boost": 0.15
    }
)

# Mock detector outputs with raw_features containing ambient mean/std
# First, create ModalityScore objects for each detector
from modules.types import VISION, AUDIO, MOTION, NORMAL

vision_score = ModalityScore(
    score=0.72,
    confidence=0.85,
    decision=NORMAL,
    timestamp=1.0,
    modality_type=VISION,
    raw_features={"brightness": 0.55, "contrast": 0.62, "saturation": 0.48}
)

print("=== Vision Score with Ambient Z-Score Boost ===")
print(f"Base score: 0.72")
print(f"Raw features: {{'brightness': 0.55, 'contrast': 0.62, 'saturation': 0.48}}")
print(f"Result: {vision_score}")

# Now test with a score that deviates more from ambient
vision_score2 = ModalityScore(
    score=0.85,
    confidence=0.90,
    decision=NORMAL,
    timestamp=1.0,
    modality_type=VISION,
    raw_features={"brightness": 0.75, "contrast": 0.80, "saturation": 0.65}
)

print("\n=== Vision Score 2 (Higher Deviation) ===")
print(f"Base score: 0.85")
print(f"Raw features: {{'brightness': 0.75, 'contrast': 0.80, 'saturation': 0.65}}")
print(f"Result: {vision_score2}")

# Test audio
audio_score = ModalityScore(
    score=0.68,
    confidence=0.80,
    decision=NORMAL,
    timestamp=1.0,
    modality_type=AUDIO,
    raw_features={"amplitude": 0.42, "freq_energy": 0.38, "pitch": 0.55}
)

print("\n=== Audio Score with Ambient Z-Score Boost ===")
print(f"Base score: 0.68")
print(f"Raw features: {{'amplitude': 0.42, 'freq_energy': 0.38, 'pitch': 0.55}}")
print(f"Result: {audio_score}")

# Test motion
motion_score = ModalityScore(
    score=0.55,
    confidence=0.75,
    decision=NORMAL,
    timestamp=1.0,
    modality_type=MOTION,
    raw_features={"accel": 0.32, "gyro": 0.28, "velocity": 0.45}
)

print("\n=== Motion Score with Ambient Z-Score Boost ===")
print(f"Base score: 0.55")
print(f"Raw features: {{'accel': 0.32, 'gyro': 0.28, 'velocity': 0.45}}")
print(f"Result: {motion_score}")

# Now run the fusion strategy to see how it combines them
fusion_result = fusion.decide(
    [vision_score, vision_score2, audio_score, motion_score],
    config={
        "weights": {"vision": 0.5, "audio": 0.3, "motion": 0.2},
        "ambient_features": ambient_stats,
        "ambient_mean": ambient_stats,
        "ambient_std": ambient_stats,
        "z_score_boost": 0.15
    }
)

print("\n=== Fusion Result ===")
print(f"Weighted score: {fusion_result.score}")
print(f"Confidence: {fusion_result.confidence}")
print(f"Decision: {fusion_result.decision}")
print(f"Raw features: {fusion_result.raw_features}")

# Calculate the Z-score boost effect
if fusion_result.raw_features.get("per_modality", {}).get("VISION", {}).get("effective_score"):
    effective = fusion_result.raw_features["per_modality"]["VISION"]["effective_score"]
    base = fusion_result.raw_features["per_modality"]["VISION"]["score"]
    print(f"\n=== Z-Score Boost Effect ===")
    print(f"Base VISION score: {base}")
    print(f"Effective VISION score: {effective}")
    print(f"Boost factor: {(effective / base - 1) * 100:.1f}%")
