"""Unit tests for modules.evidence_store.EvidenceStore."""

import json
import os

import pytest

from modules.evidence_store import EvidenceStore

KEY = "device-unique-secret-key"


def _store(tmp_path, key=KEY):
    return EvidenceStore(str(tmp_path / "evidence"), key)


def test_save_load_roundtrip_nested(tmp_path):
    store = _store(tmp_path)
    evidence = {
        "audio": {"samples": [0.1, -0.2, 0.3], "source_id": "mic-1"},
        "motion": {"accelerometer": [[0.0, 9.81, 0.0]], "labels": ["fall"]},
        "vision": {"width": 640, "height": 480, "frames": [1, 2, 3]},
        "meta": {"score": 0.97, "decision": "EMERGENCY", "ok": True, "note": None},
    }
    assert store.save(evidence, "sess-001") is True
    assert store.load("sess-001") == evidence


def test_file_does_not_contain_plaintext(tmp_path):
    store = _store(tmp_path)
    marker = "PLAINTEXT_MARKER_12345"
    store.save({"audio": {"transcript": marker}}, "sess-plain")
    raw = (tmp_path / "evidence" / "sess-plain.evidence").read_bytes()
    assert marker.encode("utf-8") not in raw
    assert b"transcript" not in raw


def test_tampered_ciphertext_raises_value_error(tmp_path):
    store = _store(tmp_path)
    store.save({"audio": {"samples": [1.0, 2.0]}}, "sess-tamper")
    path = tmp_path / "evidence" / "sess-tamper.evidence"
    blob = bytearray(path.read_bytes())
    blob[-1] ^= 0xFF
    path.write_bytes(bytes(blob))
    with pytest.raises(ValueError):
        store.load("sess-tamper")


def test_tampered_hmac_tag_raises_value_error(tmp_path):
    store = _store(tmp_path)
    store.save({"motion": {"x": 1}}, "sess-tag")
    path = tmp_path / "evidence" / "sess-tag.evidence"
    blob = bytearray(path.read_bytes())
    blob[4 + 16] ^= 0x01  # first byte of the HMAC tag
    path.write_bytes(bytes(blob))
    with pytest.raises(ValueError):
        store.load("sess-tag")


def test_malformed_magic_raises_value_error(tmp_path):
    store = _store(tmp_path)
    store.save({"vision": {"frames": []}}, "sess-magic")
    path = tmp_path / "evidence" / "sess-magic.evidence"
    blob = bytearray(path.read_bytes())
    blob[:4] = b"XXXX"
    path.write_bytes(bytes(blob))
    with pytest.raises(ValueError):
        store.load("sess-magic")


def test_wrong_key_cannot_load(tmp_path):
    _store(tmp_path).save({"audio": {"samples": [1]}}, "sess-key")
    other = EvidenceStore(str(tmp_path / "evidence"), "different-key")
    with pytest.raises(ValueError):
        other.load("sess-key")


def test_missing_session_raises_file_not_found(tmp_path):
    store = _store(tmp_path)
    with pytest.raises(FileNotFoundError):
        store.load("never-saved")


def test_save_creates_directory(tmp_path):
    target = tmp_path / "deep" / "nested" / "evidence"
    store = EvidenceStore(str(target), KEY)
    assert not target.exists()
    assert store.save({"audio": {}}, "sess-dir") is True
    assert target.is_dir()
    assert (target / "sess-dir.evidence").is_file()


@pytest.mark.parametrize(
    "session_id",
    ["../escape", "a/b", "a\\b", "", "..", ".", "x" * 129, "bad id", "a\x00b"],
)
def test_unsafe_session_ids_rejected(tmp_path, session_id):
    store = _store(tmp_path)
    with pytest.raises(ValueError):
        store.save({"audio": {}}, session_id)
    with pytest.raises(ValueError):
        store.load(session_id)


def test_two_sessions_are_isolated(tmp_path):
    store = _store(tmp_path)
    store.save({"audio": {"samples": [1, 2, 3]}}, "session-a")
    store.save({"vision": {"frames": [9]}}, "session-b")
    assert store.load("session-a") == {"audio": {"samples": [1, 2, 3]}}
    assert store.load("session-b") == {"vision": {"frames": [9]}}
    store.save({"audio": {"samples": [4]}}, "session-a")
    assert store.load("session-a") == {"audio": {"samples": [4]}}
    assert store.load("session-b") == {"vision": {"frames": [9]}}


def test_list_sessions_sorted(tmp_path):
    store = _store(tmp_path)
    assert store.list_sessions() == []
    for sid in ("zeta", "alpha", "mike"):
        store.save({"audio": {"session": sid}}, sid)
    assert store.list_sessions() == ["alpha", "mike", "zeta"]


def test_save_uses_atomic_replace_and_leaves_no_temp_files(tmp_path):
    store = _store(tmp_path)
    store.save({"audio": {"samples": [1]}}, "sess-atomic")
    names = os.listdir(tmp_path / "evidence")
    assert names == ["sess-atomic.evidence"]


def test_non_serialisable_values_are_stringified(tmp_path):
    store = _store(tmp_path)
    store.save({"when": object()}, "sess-default")
    loaded = store.load("sess-default")
    assert isinstance(loaded["when"], str)
    json.dumps(loaded)  # still valid JSON after roundtrip
