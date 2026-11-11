"""Tests for modules/vision_detector.py (role 3, vision-detector)."""

import time

from modules.types import EMERGENCY, INDETERMINATE, NORMAL, VISION, Frame
from modules.vision_detector import VisionDetector

MODEL_PATH = "modules/models/yolov8n.pt"  # absent -> heuristic mode
CONFIG = {"threshold": 0.5}
RAW_KEYS = {
    "mean_rgb",
    "brightness",
    "red_dominance",
    "fire_ratio",
    "dark_ratio",
    "edge_density",
    "mode",
}


def make_frame(width, height, rgb, timestamp=1000.0, source_id="cam-1"):
    r, g, b = rgb
    return Frame([r, g, b] * (width * height), timestamp, width, height, source_id)


def partial_fire_frame():
    # 7 of 20 pixels fire-coloured -> fire_ratio 0.35, score 0.56, conf 0.35.
    data = [220, 80, 20] * 7 + [128, 128, 128] * 13
    return Frame(data, 42.0, 10, 2, "cam-1")


def test_uniform_gray_frame_is_normal():
    detector = VisionDetector(MODEL_PATH, CONFIG)
    result = detector.predict(make_frame(32, 24, (128, 128, 128)), CONFIG)
    assert result.decision == NORMAL
    assert result.modality_type == VISION
    assert 0.0 <= result.score <= 1.0
    assert 0.0 <= result.confidence <= 1.0
    assert set(result.raw_features) == RAW_KEYS
    assert result.raw_features["brightness"] == 128.0


def test_fire_coloured_frame_is_emergency():
    detector = VisionDetector(MODEL_PATH, CONFIG)
    result = detector.predict(make_frame(32, 24, (220, 80, 20)), CONFIG)
    assert result.decision == EMERGENCY
    assert result.score >= 0.5
    assert result.confidence >= 0.3
    assert result.raw_features["fire_ratio"] == 1.0
    assert result.raw_features["mode"] == "heuristic"
    assert detector.mode == "heuristic"


def test_black_frame_is_normal_low_confidence():
    detector = VisionDetector(MODEL_PATH, CONFIG)
    result = detector.predict(make_frame(32, 24, (0, 0, 0)), CONFIG)
    assert result.decision == NORMAL
    assert result.confidence < 0.3
    assert result.raw_features["dark_ratio"] == 1.0


def test_wrong_pixel_count_is_indeterminate():
    detector = VisionDetector(MODEL_PATH, CONFIG)
    frame = Frame([10, 20, 30], 1234.5, 4, 4, "cam-1")  # expects 48 values
    result = detector.predict(frame, CONFIG)
    assert result.decision == INDETERMINATE
    assert result.confidence == 0.0
    assert result.score == 0.0
    assert set(result.raw_features) == RAW_KEYS
    assert result.raw_features["mode"] == "invalid"


def test_non_finite_pixels_are_indeterminate():
    detector = VisionDetector(MODEL_PATH, CONFIG)
    data = [128.0, float("nan"), 128.0] * 16
    frame = Frame(data, 5.0, 4, 4, "cam-1")
    assert detector.predict(frame, CONFIG).decision == INDETERMINATE


def test_zero_size_frame_is_indeterminate():
    detector = VisionDetector(MODEL_PATH, CONFIG)
    assert detector.predict(Frame([], 7.0, 0, 0, "cam-1"), CONFIG).decision == INDETERMINATE


def test_full_frame_latency_under_one_second():
    detector = VisionDetector(MODEL_PATH, CONFIG)
    frame = make_frame(640, 480, (128, 128, 128))
    start = time.perf_counter()
    result = detector.predict(frame, CONFIG)
    elapsed = time.perf_counter() - start
    assert elapsed < 1.0
    assert result.decision == NORMAL


def test_deterministic_output():
    detector = VisionDetector(MODEL_PATH, CONFIG)
    frame = make_frame(32, 24, (220, 80, 20))
    first = detector.predict(frame, CONFIG)
    second = detector.predict(frame, CONFIG)
    fresh = VisionDetector(MODEL_PATH, CONFIG).predict(frame, CONFIG)
    assert first.as_dict() == second.as_dict() == fresh.as_dict()


def test_timestamp_passthrough():
    detector = VisionDetector(MODEL_PATH, CONFIG)
    frame = make_frame(32, 24, (128, 128, 128), timestamp=12345.678)
    assert detector.predict(frame, CONFIG).timestamp == 12345.678


def test_threshold_comes_from_config():
    frame = partial_fire_frame()
    detector = VisionDetector(MODEL_PATH, CONFIG)
    assert detector.predict(frame, {"threshold": 0.5}).decision == EMERGENCY
    assert detector.predict(frame, {"threshold": 0.6}).decision == NORMAL


def test_confidence_gate_blocks_emergency():
    frame = partial_fire_frame()
    detector = VisionDetector(MODEL_PATH, CONFIG)
    result = detector.predict(frame, {"threshold": 0.5, "min_confidence": 0.5})
    assert result.decision == NORMAL


def test_full_config_dict_is_accepted():
    frame = partial_fire_frame()
    detector = VisionDetector(MODEL_PATH, {"detectors": {"vision": {"threshold": 0.5}}})
    cfg = {"detectors": {"vision": {"threshold": 0.5}}}
    assert detector.predict(frame, cfg).decision == EMERGENCY
    detector2 = VisionDetector(MODEL_PATH, {"detectors": {"vision": {"threshold": 0.6}}})
    cfg2 = {"detectors": {"vision": {"threshold": 0.6}}}
    assert detector2.predict(frame, cfg2).decision == NORMAL
