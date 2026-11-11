"""Tests for the motion/IMU emergency detector (modules/motion_detector.py)."""

import pytest

from modules.motion_detector import MotionDetector
from modules.types import (EMERGENCY, INDETERMINATE, MOTION, NORMAL, ImuSample,
                           ModalityScore)

# Mirrors config/default.yaml (hermetic: no dependency on the config package).
CONFIG = {
    "detectors": {
        "motion": {
            "model_path": "modules/models/motion_lstm.onnx",
            "threshold": 0.80,
            "sequence_length": 16,
            "freefall_g": 0.35,
            "freefall_min_samples": 3,
            "impact_g": 3.0,
            "window_sec": 1.0,
        }
    },
    "emergency_thresholds": {
        "temperature_high": 40.0,
        "temperature_low": 10.0,
        "motion_activity": 0.8,
    },
}
MISSING_MODEL = "modules/models/motion_lstm.onnx"  # not shipped with the repo


def mk(ts, acc=(0.0, 0.0, 9.81), gyro=(0.0, 0.0, 0.0), temp=25.0, press=1013.25):
    return ImuSample(timestamp=ts, accelerometer=list(acc), gyroscope=list(gyro),
                     magnetometer=[20.0, 0.0, 45.0], temperature=temp,
                     pressure=press, source_id="imu-1")


def fresh():
    return MotionDetector(MISSING_MODEL, CONFIG)


def same(result, other):
    return (result.score == other.score and result.confidence == other.confidence
            and result.decision == other.decision
            and result.raw_features == other.raw_features)


# --- contract shape ---------------------------------------------------------


def test_returns_modality_score_with_motion_type():
    result = fresh().predict(mk(0.0), CONFIG)
    assert isinstance(result, ModalityScore)
    assert result.modality_type == MOTION
    assert result.decision in (EMERGENCY, NORMAL, INDETERMINATE)
    assert 0.0 <= result.score <= 1.0
    assert 0.0 <= result.confidence <= 1.0


def test_raw_features_has_exactly_required_keys():
    raw = fresh().predict(mk(0.0), CONFIG).raw_features
    assert set(raw) == {"accel_magnitude", "gyro_magnitude", "jerk", "freefall",
                        "impact", "temperature_c", "pressure_hpa", "activity",
                        "mode"}


# --- heuristic fallback -----------------------------------------------------


def test_missing_model_falls_back_to_heuristic():
    det = fresh()
    assert det._mode == "heuristic"
    assert det.predict(mk(0.0), CONFIG).raw_features["mode"] == "heuristic"


def test_corrupt_model_file_falls_back_to_heuristic(tmp_path):
    path = tmp_path / "broken.onnx"
    path.write_bytes(b"not really an onnx model")
    det = MotionDetector(str(path), CONFIG)
    assert det._mode == "heuristic"
    assert det.predict(mk(0.0), CONFIG).decision == NORMAL


# --- normal motion ----------------------------------------------------------


def test_resting_is_normal():
    det = fresh()
    for ts in (0.0, 0.05, 0.10):
        result = det.predict(mk(ts), CONFIG)
        assert result.decision == NORMAL
        assert result.score < 0.8


def test_temperature_inside_band_is_normal():
    for temp in (25.0, 40.0, 10.0):  # band edges are inclusive
        assert fresh().predict(mk(0.0, temp=temp), CONFIG).decision == NORMAL


def test_predict_config_none_uses_defaults():
    assert fresh().predict(mk(0.0, temp=25.0), None).decision == NORMAL
    assert fresh().predict(mk(0.0, temp=45.0), None).decision == EMERGENCY


# --- emergencies ------------------------------------------------------------


def test_freefall_then_impact_is_emergency():
    det = fresh()
    results = [det.predict(mk(t, acc=(0.1, 0.2, 0.4)), CONFIG)
               for t in (0.0, 0.05, 0.10)]
    assert [r.decision for r in results] == [NORMAL, NORMAL, EMERGENCY]
    assert results[2].raw_features["freefall"] is True
    assert results[2].raw_features["impact"] is False
    impact = det.predict(mk(0.15, acc=(0.0, 0.0, 35.0)), CONFIG)
    assert impact.decision == EMERGENCY
    assert impact.score == 1.0
    assert impact.raw_features["impact"] is True
    assert impact.raw_features["freefall"] is False


def test_impact_alone_is_emergency():
    result = fresh().predict(mk(0.0, acc=(0.0, 0.0, 35.0)), CONFIG)
    assert result.decision == EMERGENCY
    assert result.raw_features["impact"] is True
    assert result.score == 1.0


def test_high_temperature_is_emergency():
    result = fresh().predict(mk(0.0, temp=45.0), CONFIG)
    assert result.decision == EMERGENCY
    assert result.raw_features["temperature_c"] == 45.0


def test_low_temperature_is_emergency():
    assert fresh().predict(mk(0.0, temp=5.0), CONFIG).decision == EMERGENCY


def test_high_gyro_activity_is_emergency():
    result = fresh().predict(mk(0.0, gyro=(3.0, 0.0, 0.0)), CONFIG)
    assert result.decision == EMERGENCY
    assert result.raw_features["activity"] >= 0.8


def test_high_jerk_is_emergency():
    det = fresh()
    det.predict(mk(0.0, acc=(0.0, 0.0, 9.81)), CONFIG)
    result = det.predict(mk(0.05, acc=(0.0, 0.0, 25.0)), CONFIG)
    assert result.decision == EMERGENCY
    assert result.raw_features["jerk"] == pytest.approx((25.0 - 9.81) / 0.05)


def test_implausible_pressure_halves_confidence():
    ok = fresh().predict(mk(0.0), CONFIG).confidence
    suspect = fresh().predict(mk(0.0, press=2000.0), CONFIG).confidence
    assert suspect == pytest.approx(ok * 0.5)


def test_high_score_low_confidence_is_indeterminate():
    # 41 C scores 0.84, but an implausible barometer halves confidence
    # below the 0.3 trust floor, so the decision is INDETERMINATE.
    result = fresh().predict(mk(0.0, temp=41.0, press=2000.0), CONFIG)
    assert result.score >= 0.8
    assert result.confidence < 0.3
    assert result.decision == INDETERMINATE


# --- malformed input --------------------------------------------------------


def test_short_vector_is_indeterminate():
    result = fresh().predict(mk(0.0, acc=(0.0, 9.81)), CONFIG)
    assert result.decision == INDETERMINATE
    assert result.confidence == 0.0
    assert result.score == 0.0


def test_nan_accelerometer_is_indeterminate():
    result = fresh().predict(mk(0.0, acc=(0.0, 0.0, float("nan"))), CONFIG)
    assert result.decision == INDETERMINATE
    assert result.confidence == 0.0


def test_none_sample_is_indeterminate():
    result = fresh().predict(None, CONFIG)
    assert result.decision == INDETERMINATE
    assert result.confidence == 0.0


def test_non_finite_temperature_is_indeterminate():
    result = fresh().predict(mk(0.0, temp=float("inf")), CONFIG)
    assert result.decision == INDETERMINATE


def test_non_numeric_pressure_is_indeterminate():
    result = fresh().predict(mk(0.0, press="abc"), CONFIG)
    assert result.decision == INDETERMINATE


def test_malformed_sample_keeps_timestamp_and_type():
    result = fresh().predict(mk(12.5, acc=(0.0, 0.0)), CONFIG)
    assert result.timestamp == 12.5
    assert result.modality_type == MOTION


def test_malformed_sample_does_not_touch_history():
    det = fresh()
    det.predict(mk(0.0, acc=(0.0, 0.0)), CONFIG)  # malformed, must be ignored
    assert same(det.predict(mk(0.0), CONFIG), fresh().predict(mk(0.0), CONFIG))


# --- state isolation and determinism ---------------------------------------


def test_instances_do_not_share_history():
    a, b = fresh(), fresh()
    for t in (0.0, 0.05, 0.10, 0.15):
        a.predict(mk(t, acc=(0.1, 0.2, 0.4)), CONFIG)
    assert a.predict(mk(0.20, acc=(0.0, 0.0, 35.0)), CONFIG).decision == EMERGENCY
    b_result = b.predict(mk(0.0), CONFIG)  # fresh history, still resting
    assert b_result.decision == NORMAL
    assert b_result.score < 0.8


def test_detector_recovers_after_emergency():
    det = fresh()
    for t in (0.0, 0.05, 0.10):
        det.predict(mk(t, acc=(0.1, 0.2, 0.4)), CONFIG)
    assert det.predict(mk(0.15, acc=(0.0, 0.0, 35.0)), CONFIG).decision == EMERGENCY
    # First resting sample still sees a huge jerk right after the impact.
    assert det.predict(mk(0.20), CONFIG).decision == EMERGENCY
    assert det.predict(mk(0.25), CONFIG).decision == NORMAL


def test_window_expires_old_samples():
    det = fresh()
    for t in (0.0, 0.05, 0.10, 0.15):
        det.predict(mk(t, acc=(0.1, 0.2, 0.4)), CONFIG)
    # 2 s later the pre-impact streak fell out of the 1 s window.
    late = det.predict(mk(2.0, acc=(0.1, 0.2, 0.4)), CONFIG)
    assert late.decision == NORMAL
    assert late.raw_features["freefall"] is False


def test_predict_time_config_overrides_thresholds():
    cfg = {"emergency_thresholds": {"temperature_high": 30.0}}
    assert fresh().predict(mk(0.0, temp=35.0), cfg).decision == EMERGENCY
    assert fresh().predict(mk(0.0, temp=35.0), CONFIG).decision == NORMAL


def test_deterministic_across_instances():
    sequence = [mk(t, acc=(0.1, 0.2, 0.4)) for t in (0.0, 0.05, 0.10)]
    sequence.append(mk(0.15, acc=(0.0, 0.0, 35.0)))
    runs = []
    for _ in range(2):
        det = fresh()
        runs.append([det.predict(sample, CONFIG) for sample in sequence])
    for first, second in zip(*runs):
        assert same(first, second)
