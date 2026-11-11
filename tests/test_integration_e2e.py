"""Coordinator-owned end-to-end smoke test.

Runs the real detectors, real fusion, real buffer, real consent manager and
real alert sender together through EmergencyPipeline. This is the barrier's
proof that the eight territories actually compose.
"""

from __future__ import annotations

import random

import pytest

from config import load_config
from modules.types import AudioChunk, Frame, ImuSample


def _cfg():
    return load_config()


def _audio(rms: float = 0.9, n: int = 1600, ts: float = 100.0) -> AudioChunk:
    rng = random.Random(7)
    samples = [rng.uniform(-1.0, 1.0) for _ in range(n)]
    peak = max(abs(s) for s in samples) or 1.0
    samples = [s / peak * rms for s in samples]
    return AudioChunk(samples, ts, n / 16000, "mic-1")


def _imu(a: list[float], ts: float = 100.0) -> ImuSample:
    return ImuSample(ts, a, [0.0, 0.0, 0.0], [0.0, 0.0, 25.0], 25.0, 1013.0, "imu-1")


def _frame(rgb: tuple[int, int, int], w: int = 64, h: int = 48, ts: float = 100.0) -> Frame:
    return Frame(list(rgb) * (w * h), ts, w, h, "cam-1")


def _build(tmp_path):
    from modules.alert_sender import AlertSender
    from modules.audio_detector import AudioDetector
    from modules.evidence_store import EvidenceStore
    from modules.fusion import FusionStrategy
    from modules.motion_detector import MotionDetector
    from modules.pipeline import EmergencyPipeline
    from modules.rolling_buffer import RollingBuffer
    from modules.user_consent import UserConsentManager
    from modules.vision_detector import VisionDetector

    cfg = _cfg()
    consent = UserConsentManager()
    assert consent.request_consent("microphone")
    assert consent.request_consent("camera")
    assert consent.start_recording("microphone")
    assert consent.start_recording("camera")
    sender = AlertSender("device-1", "secret", consent,
                         countdown_sec=cfg["alert"]["countdown_sec"], tick=0)
    pipeline = EmergencyPipeline(
        AudioDetector("missing.tflite", cfg),
        MotionDetector("missing.onnx", cfg),
        VisionDetector("missing.pt", cfg),
        FusionStrategy(cfg),
        RollingBuffer(cfg["buffer"]["size_sec"], "secret",
                      storage_path=str(tmp_path / "buffer.enc")),
        sender,
        consent,
        cfg,
    )
    sent: list[bool] = []
    original_send = sender.send

    def spy(decision, evidence_buffer, user_id):
        result = original_send(decision, evidence_buffer, user_id)
        sent.append(result)
        return result

    sender.send = spy
    pipeline.sent = sent
    return pipeline, consent, sender, cfg, EvidenceStore(str(tmp_path / "evidence"), "secret")


def test_end_to_end_emergency_alert(tmp_path):
    pipeline, consent, sender, cfg, store = _build(tmp_path)
    assert consent.recording_indicator_visible is True

    decision = None
    # freefall for three samples, then a 5 g impact; loud broadband audio and
    # a fire-coloured frame are present on every step.
    for i, accel in enumerate([[0, 0, 0.1], [0, 0, 0.1], [0, 0, 0.1],
                               [0, 0, 49.0], [0, 0, 49.0]]):
        decision = pipeline.process(
            audio_chunk=_audio(ts=100.0 + i * 0.1),
            imu_sample=_imu(accel, ts=100.0 + i * 0.1),
            frame=_frame((220, 80, 20), ts=100.0 + i * 0.1),
        )

    assert decision.decision == "EMERGENCY", decision
    assert decision.modality_type == "FUSION"
    assert decision.score >= cfg["fusion"]["threshold"]

    recent = pipeline.buffer.get_recent(cfg["buffer"]["size_sec"])
    assert sum(len(v) for v in recent.values()) >= 5

    evidence = {"decision": decision.as_dict(), "recent": recent}
    assert store.save(evidence, "session-1") is True
    loaded = store.load("session-1")
    assert loaded["decision"]["decision"] == "EMERGENCY"


def test_cancel_prevents_alert(tmp_path):
    pipeline, consent, sender, cfg, _ = _build(tmp_path)
    calls = {"n": 0}

    def notifier(remaining):
        calls["n"] += 1
        if remaining == 5:
            sender.cancel_user_countdown()

    sender.notifier = notifier
    decision = pipeline.process(audio_chunk=_audio(), frame=_frame((220, 80, 20)))
    assert decision.decision == "EMERGENCY"
    assert calls["n"] == 11  # 15..5, then the send aborts
    assert pipeline.sent == [False]


def test_consent_is_fail_closed(tmp_path):
    pipeline, consent, sender, cfg, _ = _build(tmp_path)
    consent.revoke_consent("microphone")
    assert consent.start_recording("microphone") is False
    assert consent.recording_indicator_visible is True  # camera still active
    consent.revoke_consent("camera")
    assert consent.recording_indicator_visible is False

    pipeline.process(audio_chunk=_audio(), frame=_frame((220, 80, 20)))
    assert pipeline.sent == [False]


def test_safety_audit(tmp_path):
    pipeline, *_ = _build(tmp_path)
    audit = pipeline.safety_audit()
    assert set(audit) == {
        "recording_indicator", "mic_consent", "camera_consent",
        "countdown_cancellable", "raw_media_on_device",
    }
    assert all(audit.values()), audit
