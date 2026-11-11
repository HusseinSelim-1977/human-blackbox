"""Tests for the emergency pipeline wiring and safety gates.

All collaborators are in-file stubs, so these tests are deterministic and do
not need models, sensors, or network access.
"""

from pathlib import Path

import pytest
import yaml

from modules.pipeline import EmergencyPipeline
from modules.types import (
    AUDIO,
    EMERGENCY,
    INDETERMINATE,
    MOTION,
    NORMAL,
    VISION,
    AudioChunk,
    Frame,
    ImuSample,
    ModalityScore,
)

CONFIG_PATH = Path(__file__).resolve().parents[1] / "config" / "default.yaml"


def load_shipped_config():
    with open(CONFIG_PATH, "r", encoding="utf-8") as handle:
        return yaml.safe_load(handle)


def audio_chunk():
    return AudioChunk([0.0] * 8, timestamp=1.0, duration_sec=0.5,
                      source_id="mic-1")


def imu_sample():
    return ImuSample(timestamp=1.0, accelerometer=[0.0, 0.0, 9.8],
                     gyroscope=[0.0, 0.0, 0.0],
                     magnetometer=[0.0, 0.0, 1.0], temperature=21.0,
                     pressure=1013.0, source_id="imu-1")


def frame():
    return Frame([0] * 16, timestamp=1.0, width=4, height=4,
                 source_id="cam-1")


class StubDetector:
    def __init__(self, modality, score):
        self.modality = modality
        self.score = score
        self.calls = []

    def predict(self, chunk, config):
        self.calls.append(chunk)
        decision = EMERGENCY if self.score >= 0.65 else NORMAL
        return ModalityScore(self.score, 0.9, decision,
                             getattr(chunk, "timestamp", 0.0),
                             self.modality, {"stub": True})


class StubFusion:
    def __init__(self):
        self.calls = []

    def decide(self, modality_scores, config):
        self.calls.append(list(modality_scores))
        emergency_votes = sum(
            1 for score in modality_scores if score.decision == EMERGENCY
        )
        decision = EMERGENCY if emergency_votes >= 2 else NORMAL
        return ModalityScore(
            score=max((s.score for s in modality_scores), default=0.0),
            confidence=0.9,
            decision=decision,
            timestamp=1.0,
            modality_type="FUSION",
            raw_features={"votes": emergency_votes},
        )


class StubBuffer:
    def __init__(self):
        self.entries = []

    def append(self, data_chunk, modality_score):
        self.entries.append((data_chunk, modality_score))

    def get_recent(self, seconds):
        return {"audio": [], "motion": [], "vision": []}


class StubAlertSender:
    def __init__(self, result=True):
        self.result = result
        self.calls = []

    def send(self, decision, evidence_buffer, user_id):
        self.calls.append((decision, evidence_buffer, user_id))
        return self.result

    def cancel_user_countdown(self):
        pass


class StubConsentManager:
    def __init__(self, granted=()):
        self.granted = set(granted)
        self.recording = set()

    def request_consent(self, sensor):
        self.granted.add(sensor)
        return True

    def revoke_consent(self, sensor):
        self.granted.discard(sensor)

    def has_consent(self, sensor):
        return sensor in self.granted

    def start_recording(self, sensor):
        if sensor not in self.granted:
            return False
        self.recording.add(sensor)
        return True

    def stop_recording(self, sensor):
        self.recording.discard(sensor)

    @property
    def recording_indicator_visible(self):
        return bool(self.recording)


def build_pipeline(audio_score=0.0, motion_score=0.0, vision_score=0.0,
                   alert_result=True, granted=("microphone", "camera")):
    audio = StubDetector(AUDIO, audio_score)
    motion = StubDetector(MOTION, motion_score)
    vision = StubDetector(VISION, vision_score)
    buffer = StubBuffer()
    alert = StubAlertSender(result=alert_result)
    consent = StubConsentManager(granted=granted)
    pipeline = EmergencyPipeline(
        audio_detector=audio,
        motion_detector=motion,
        vision_detector=vision,
        fusion=StubFusion(),
        buffer=buffer,
        alert_sender=alert,
        consent_manager=consent,
        config=load_shipped_config(),
    )
    return pipeline, audio, motion, vision, buffer, alert, consent


def test_two_high_scores_trigger_emergency_and_alert_once():
    pipeline, _, _, _, buffer, alert, _ = build_pipeline(
        audio_score=0.9, motion_score=0.95
    )

    result = pipeline.process(audio_chunk=audio_chunk(), imu_sample=imu_sample())

    assert result.decision == EMERGENCY
    assert pipeline.last_decision is result
    assert len(alert.calls) == 1
    assert alert.calls[0][0] is result
    assert alert.calls[0][1] is buffer
    assert len(buffer.entries) == 2


def test_one_high_score_is_normal_and_sends_no_alert():
    pipeline, _, _, _, _, alert, _ = build_pipeline(audio_score=0.95)

    result = pipeline.process(audio_chunk=audio_chunk())

    assert result.decision == NORMAL
    assert alert.calls == []
    assert pipeline.last_decision is result


def test_alert_failure_still_returns_the_decision():
    pipeline, _, _, _, _, alert, _ = build_pipeline(
        audio_score=0.9, motion_score=0.95, alert_result=False
    )

    result = pipeline.process(audio_chunk=audio_chunk(), imu_sample=imu_sample())

    assert len(alert.calls) == 1
    assert result.decision == EMERGENCY
    assert pipeline.last_decision is result


def test_only_provided_inputs_are_evaluated():
    pipeline, audio, motion, vision, buffer, _, _ = build_pipeline(
        vision_score=0.9
    )

    pipeline.process(frame=frame())

    assert audio.calls == []
    assert motion.calls == []
    assert len(vision.calls) == 1
    assert len(buffer.entries) == 1


def test_no_input_reports_indeterminate():
    pipeline, audio, motion, vision, buffer, alert, _ = build_pipeline()

    result = pipeline.process()

    assert result.decision == INDETERMINATE
    assert audio.calls == motion.calls == vision.calls == []
    assert buffer.entries == []
    assert alert.calls == []


def test_safety_audit_all_true_for_shipped_config():
    pipeline, _, _, _, _, _, _ = build_pipeline()

    audit = pipeline.safety_audit()

    assert audit == {
        "recording_indicator": True,
        "mic_consent": True,
        "camera_consent": True,
        "countdown_cancellable": True,
        "raw_media_on_device": True,
    }
    assert all(isinstance(value, bool) for value in audit.values())


def test_safety_audit_detects_missing_consent():
    pipeline, _, _, _, _, _, consent = build_pipeline(granted=())
    consent.revoke_consent("camera")

    audit = pipeline.safety_audit()

    assert audit["mic_consent"] is False
    assert audit["camera_consent"] is False


def test_capture_requires_consent_and_indicator_toggles():
    pipeline, _, _, _, _, _, consent = build_pipeline(granted=())

    assert pipeline.start_capture("microphone") is False
    assert consent.recording_indicator_visible is False
    assert pipeline.safety_audit()["recording_indicator"] is True

    consent.request_consent("microphone")
    assert pipeline.start_capture("microphone") is True
    assert consent.recording_indicator_visible is True

    pipeline.stop_capture("microphone")
    assert consent.recording_indicator_visible is False
