"""Unit tests for AlertSender and UserConsentManager.

No 15-second waits: every countdown uses tick=0 or a fake sleep function.
"""

import time

import pytest

from modules.types import EMERGENCY, NORMAL, FUSION, ModalityScore
from modules.alert_sender import AlertSender
from modules.user_consent import UserConsentManager, ConsentRequiredError


def emergency(score=0.9, confidence=0.9):
    return ModalityScore(score, confidence, EMERGENCY, time.time(), FUSION, {})


def normal():
    return ModalityScore(0.1, 0.9, NORMAL, time.time(), FUSION, {})


def consented_manager(sensor="microphone"):
    manager = UserConsentManager()
    manager.request_consent(sensor)
    return manager


def silent_sender(**kwargs):
    kwargs.setdefault("countdown_sec", 15)
    kwargs.setdefault("tick", 0)
    kwargs.setdefault("sleep_fn", lambda seconds: None)
    kwargs.setdefault("notifier", lambda remaining: None)
    return AlertSender("dev-1", "secret-key", consented_manager(), **kwargs)


class FakeSleep:
    """Fake sleep that can trigger cancel_user_countdown mid-countdown."""

    def __init__(self, cancel_after=None, sender=None):
        self.calls = []
        self.cancel_after = cancel_after
        self.sender = sender

    def __call__(self, seconds):
        self.calls.append(seconds)
        if self.cancel_after is not None and len(self.calls) == self.cancel_after:
            self.sender.cancel_user_countdown()


class FakeBuffer:
    def __init__(self, recent):
        self._recent = recent

    def get_recent(self, seconds):
        return self._recent


# --- countdown behaviour ---------------------------------------------------

def test_full_countdown_returns_true():
    sender = silent_sender()
    assert sender.send(emergency(), None, "user-1") is True
    assert sender.last_payload is not None
    assert sender.last_payload["metadata"]["device_id"] == "dev-1"


def test_countdown_notifier_receives_15_to_1():
    seen = []
    sender = silent_sender(notifier=seen.append)
    assert sender.send(emergency(), None, "user-1") is True
    assert seen == list(range(15, 0, -1))


def test_cancel_during_countdown_returns_false():
    sender = silent_sender()
    fake = FakeSleep(cancel_after=3, sender=sender)
    sender.sleep_fn = fake
    assert sender.send(emergency(), None, "user-1") is False
    assert sender.last_payload is None
    assert len(fake.calls) == 3  # stopped immediately after cancel


def test_cancel_before_any_tick_returns_false():
    sender = silent_sender()
    sender.cancel_user_countdown()  # no active send: must be a safe no-op
    sender.sleep_fn = FakeSleep(cancel_after=1, sender=sender)
    assert sender.send(emergency(), None, "user-1") is False


def test_two_sequential_sends_are_independent():
    sender = silent_sender()
    sender.sleep_fn = FakeSleep(cancel_after=1, sender=sender)
    assert sender.send(emergency(), None, "user-1") is False  # cancelled

    sender.sleep_fn = lambda seconds: None
    assert sender.send(emergency(), None, "user-1") is True  # fresh flag
    assert len(sender.sent_alerts) == 1


def test_cancel_resets_between_sends():
    sender = silent_sender()
    sender.send(emergency(), None, "user-1")
    sender.cancel_user_countdown()  # stale cancel after send
    assert sender.send(emergency(), None, "user-1") is True


# --- gating: decision + consent -------------------------------------------

def test_non_emergency_returns_false():
    sender = silent_sender()
    assert sender.send(normal(), None, "user-1") is False
    assert sender.last_payload is None


def test_missing_consent_returns_false():
    sender = AlertSender("dev-1", "key", UserConsentManager(), tick=0,
                         sleep_fn=lambda s: None, notifier=lambda r: None)
    assert sender.send(emergency(), None, "user-1") is False


def test_revoked_consent_returns_false():
    manager = consented_manager()
    manager.revoke_consent("microphone")
    sender = AlertSender("dev-1", "key", manager, tick=0,
                         sleep_fn=lambda s: None, notifier=lambda r: None)
    assert sender.send(emergency(), None, "user-1") is False


# --- payload safety --------------------------------------------------------

def test_payload_has_no_raw_media_and_is_hashed():
    raw_audio = [123456, -987654, 424242]
    buffer = FakeBuffer({"audio": [{"audio_data": raw_audio}],
                         "motion": [], "vision": []})
    sender = silent_sender()
    assert sender.send(emergency(), buffer, "user-1") is True

    text = repr(sender.last_payload)
    assert str(raw_audio) not in text  # raw sample list never in payload
    assert "audio_data" not in text
    assert sender.last_payload["metadata"]["raw_media_uploaded"] is False
    audio_summary = sender.last_payload["metadata"]["evidence"]["modalities"]["audio"]
    assert audio_summary["chunks"] == 1
    assert len(audio_summary["sha256"]) == 64


def test_payload_signature_is_verifiable_hmac():
    import hashlib
    import hmac
    import json

    sender = silent_sender()
    sender.send(emergency(), None, "user-1")
    payload = sender.last_payload
    expected = hmac.new(b"secret-key",
                        json.dumps(payload["metadata"], sort_keys=True,
                                   default=str).encode("utf-8"),
                        hashlib.sha256).hexdigest()
    assert payload["signature"] == expected


# --- consent manager / indicator ------------------------------------------

def test_start_recording_requires_consent_and_toggles_indicator():
    manager = UserConsentManager()
    assert manager.recording_indicator_visible is False
    assert manager.start_recording("microphone") is False  # no consent yet
    assert manager.recording_indicator_visible is False

    assert manager.request_consent("microphone") is True
    assert manager.has_consent("microphone") is True
    assert manager.start_recording("microphone") is True
    assert manager.recording_indicator_visible is True

    manager.stop_recording("microphone")
    assert manager.recording_indicator_visible is False


def test_revoke_after_grant_blocks_recording():
    manager = UserConsentManager()
    manager.request_consent("camera")
    manager.revoke_consent("camera")
    assert manager.has_consent("camera") is False
    assert manager.start_recording("camera") is False
    assert manager.recording_indicator_visible is False


def test_revoke_stops_active_recording():
    manager = UserConsentManager()
    manager.request_consent("camera")
    manager.start_recording("camera")
    assert manager.recording_indicator_visible is True
    manager.revoke_consent("camera")
    assert manager.recording_indicator_visible is False


def test_indicator_stays_on_while_any_sensor_records():
    manager = UserConsentManager()
    manager.request_consent("microphone")
    manager.request_consent("camera")
    manager.start_recording("microphone")
    manager.start_recording("camera")
    manager.stop_recording("microphone")
    assert manager.recording_indicator_visible is True  # camera still on
    manager.stop_recording("camera")
    assert manager.recording_indicator_visible is False


def test_consent_required_error_raised():
    manager = UserConsentManager()
    with pytest.raises(ConsentRequiredError):
        manager.require_consent("microphone")
