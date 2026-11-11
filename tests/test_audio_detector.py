"""Unit tests for modules/audio_detector.py (AudioDetector contract).

Heuristic path is exercised directly: TensorFlow / tflite_runtime are not
installed and modules/models/yamnet.tflite does not exist in this repo, so
AudioDetector silently falls back to the deterministic numpy heuristic
(# ASSUMPTION: heuristic fallback).
"""

import numpy as np
import pytest

from modules import (
    AUDIO,
    EMERGENCY,
    INDETERMINATE,
    NORMAL,
    AudioChunk,
    ModalityScore,
)
from modules.audio_detector import AudioDetector

SAMPLE_RATE = 16000
MODEL_PATH = "modules/models/yamnet.tflite"  # does not exist -> heuristic


def make_config(threshold=0.5, audio_noise_db=80.0):
    """Mirror of config/default.yaml detectors.audio + noise threshold."""
    return {
        "detectors": {
            "audio": {
                "model_path": MODEL_PATH,
                "threshold": threshold,
                "sample_rate_hz": SAMPLE_RATE,
                "window_sec": 0.975,
                "emergency_classes": ["Screaming", "Siren"],
            }
        },
        "emergency_thresholds": {"audio_noise": audio_noise_db},
    }


def make_chunk(audio_data, timestamp=1234.5, source_id="mic-1", duration=0.5):
    return AudioChunk(audio_data=list(audio_data), timestamp=timestamp,
                      duration_sec=duration, source_id=source_id)


def silence(n=SAMPLE_RATE // 2):
    return [0.0] * n


def loud_broadband_burst(rms=0.5, n=SAMPLE_RATE // 2):
    """Deterministic seeded pseudo-noise scaled to the requested RMS."""
    rng = np.random.default_rng(42)
    x = rng.uniform(-1.0, 1.0, n)
    x = x * (rms / float(np.sqrt(np.mean(x * x))))
    return x.tolist()


def loud_tone(freq_hz=1000.0, rms=0.5, n=SAMPLE_RATE // 2):
    """Deterministic pure tone scaled to the requested RMS."""
    t = np.arange(n) / SAMPLE_RATE
    x = np.sin(2.0 * np.pi * freq_hz * t)
    return (x * (rms / float(np.sqrt(np.mean(x * x))))).tolist()


def predict(wave, config=None, timestamp=1234.5, source_id="mic-1"):
    det = AudioDetector(MODEL_PATH, make_config())
    score = det.predict(make_chunk(wave, timestamp=timestamp,
                                   source_id=source_id),
                        config if config is not None else make_config())
    assert isinstance(score, ModalityScore)
    return score


def test_silence_is_normal():
    score = predict(silence())
    assert score.decision == NORMAL
    assert score.score < 0.5


def test_loud_broadband_burst_is_emergency():
    score = predict(loud_broadband_burst(rms=0.5))
    assert score.decision == EMERGENCY
    assert score.score >= 0.5
    assert score.confidence >= 0.3


def test_quiet_broadband_noise_is_normal():
    # RMS ~0.05 -> ~68 dB SPL, below the 80 dB audio_noise threshold.
    score = predict(loud_broadband_burst(rms=0.05))
    assert score.decision == NORMAL


def test_loud_tone_is_normal():
    # Loud but tonal: broadband evidence (flatness/ZCR) stays low.
    score = predict(loud_tone())
    assert score.decision == NORMAL


def test_constant_dc_is_normal():
    score = predict([0.5] * (SAMPLE_RATE // 2))
    assert score.decision == NORMAL


@pytest.mark.parametrize("wave", [
    silence(),
    loud_broadband_burst(rms=0.5),
    loud_broadband_burst(rms=0.05),
    loud_tone(),
    [0.5] * 64,
    [0.1, -0.2, 0.3],
])
def test_score_and_confidence_in_unit_range(wave):
    score = predict(wave)
    assert 0.0 <= score.score <= 1.0
    assert 0.0 <= score.confidence <= 1.0


@pytest.mark.parametrize("wave", [
    silence(),
    loud_broadband_burst(rms=0.5),
    loud_tone(),
    [],
    [0.1, float("nan"), 0.3],
])
def test_decision_in_allowed_set(wave):
    score = predict(wave)
    assert score.decision in {EMERGENCY, NORMAL, INDETERMINATE}


def test_empty_chunk_is_indeterminate():
    score = predict([])
    assert score.decision == INDETERMINATE
    assert score.confidence == 0.0
    assert score.score == 0.0


@pytest.mark.parametrize("wave", [
    [float("nan")],
    [0.1, float("nan"), 0.3],
    [float("inf")] * 8,
    [0.1, float("-inf"), 0.2],
])
def test_non_finite_audio_is_indeterminate(wave):
    score = predict(wave)
    assert score.decision == INDETERMINATE
    assert score.confidence == 0.0


def test_timestamp_passthrough():
    score = predict(loud_broadband_burst(rms=0.2), timestamp=9876.5)
    assert score.timestamp == 9876.5


def test_source_id_passthrough():
    score = predict(loud_broadband_burst(rms=0.2), source_id="mic-9")
    assert score.raw_features["source_id"] == "mic-9"


def test_modality_type_is_audio():
    assert predict(silence()).modality_type == AUDIO


def test_raw_features_keys():
    required = {"rms_db_spl", "peak", "zcr", "spectral_centroid_hz",
                "spectral_flatness", "threshold_db", "mode"}
    score = predict(loud_broadband_burst(rms=0.5))
    assert required <= set(score.raw_features)
    feats = score.raw_features
    assert feats["rms_db_spl"] == pytest.approx(20 * np.log10(0.5) + 94,
                                                abs=0.5)
    assert feats["threshold_db"] == 80.0


def test_missing_model_falls_back_to_heuristic():
    det = AudioDetector("modules/models/does-not-exist.tflite", make_config())
    score = det.predict(make_chunk(loud_broadband_burst()), make_config())
    assert score.raw_features["mode"] == "heuristic"


def test_config_threshold_is_respected():
    wave = loud_broadband_burst(rms=0.5)
    strict = make_config(threshold=0.99)
    lenient = make_config(threshold=0.10)
    assert predict(wave, config=strict).decision == NORMAL
    assert predict(wave, config=lenient).decision == EMERGENCY
    assert predict(silence(), config=lenient).decision == NORMAL


def test_audio_noise_db_threshold_is_respected():
    wave = loud_broadband_burst(rms=0.5)  # ~88 dB SPL
    high_db = make_config(audio_noise_db=120.0)  # needs >= 120 dB SPL
    low_db = make_config(audio_noise_db=60.0)   # needs >= 60 dB SPL
    assert predict(wave, config=high_db).decision == NORMAL
    assert predict(wave, config=low_db).decision == EMERGENCY


def test_defaults_when_config_keys_missing():
    det = AudioDetector(MODEL_PATH, {})
    score = det.predict(make_chunk(loud_broadband_burst(rms=0.5)), {})
    assert score.decision == EMERGENCY  # defaults: threshold 0.5, 80 dB
    assert score.raw_features["threshold_db"] == 80.0


def test_deterministic_on_repeat_call():
    det = AudioDetector(MODEL_PATH, make_config())
    chunk = make_chunk(loud_broadband_burst(rms=0.5))
    first = det.predict(chunk, make_config())
    second = det.predict(chunk, make_config())
    assert first.score == second.score
    assert first.confidence == second.confidence
    assert first.decision == second.decision
    assert first.raw_features == second.raw_features


def test_two_detectors_agree():
    det_a = AudioDetector(MODEL_PATH, make_config())
    det_b = AudioDetector(MODEL_PATH, make_config())
    chunk = make_chunk(loud_broadband_burst(rms=0.5))
    a = det_a.predict(chunk, make_config())
    b = det_b.predict(chunk, make_config())
    assert (a.score, a.confidence, a.decision) == \
        (b.score, b.confidence, b.decision)


def test_with_repo_default_config():
    from config import load_config
    cfg = load_config()
    det = AudioDetector(cfg["detectors"]["audio"]["model_path"], cfg)
    assert det.predict(make_chunk(silence()), cfg).decision == NORMAL
    assert det.predict(
        make_chunk(loud_broadband_burst(rms=0.5)), cfg
    ).decision == EMERGENCY
