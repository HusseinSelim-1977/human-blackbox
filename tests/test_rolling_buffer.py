"""Tests for the encrypted rolling buffer (modules.rolling_buffer)."""

import pickle

import pytest

from modules.rolling_buffer import RollingBuffer
from modules.types import (
    AUDIO,
    EMERGENCY,
    Frame,
    ImuSample,
    ModalityScore,
    NORMAL,
    AudioChunk,
)


# ---------------------------------------------------------------- helpers

def audio(ts: float, source_id: str = "mic-1") -> AudioChunk:
    return AudioChunk(
        audio_data=[0.1, -0.2, 0.3], timestamp=ts, duration_sec=0.5,
        source_id=source_id,
    )


def imu(ts: float, source_id: str = "imu-1") -> ImuSample:
    return ImuSample(
        timestamp=ts, accelerometer=[0.0, 0.0, 9.8],
        gyroscope=[0.0, 0.0, 0.0], magnetometer=[1.0, 2.0, 3.0],
        temperature=20.0, pressure=1013.0, source_id=source_id,
    )


def frame(ts: float, source_id: str = "cam-1") -> Frame:
    return Frame(
        image_data=[10, 20, 30, 40], timestamp=ts, width=2, height=2,
        source_id=source_id,
    )


def score(ts: float, decision: str = NORMAL, modality_type: str = AUDIO,
          raw_features=None) -> ModalityScore:
    return ModalityScore(
        score=0.5, confidence=0.9, decision=decision, timestamp=ts,
        modality_type=modality_type,
        raw_features={} if raw_features is None else raw_features,
    )


def storage(tmp_path):
    return str(tmp_path / "buffer.enc")


# ------------------------------------------------------------------ tests

def test_append_and_get_recent_buckets():
    """Appended chunks land in the right get_recent buckets."""
    buf = RollingBuffer(60, "unit-test-key")
    chunks = [audio(10.0), imu(11.0), frame(12.0)]
    for chunk in chunks:
        buf.append(chunk, score(chunk.timestamp))
    result = buf.get_recent(60)
    assert set(result) == {"audio", "motion", "vision"}
    assert len(result["audio"]) == 1
    assert len(result["motion"]) == 1
    assert len(result["vision"]) == 1
    a_chunk, a_score = result["audio"][0]
    assert isinstance(a_chunk, AudioChunk) and a_chunk.timestamp == 10.0
    assert a_score.decision == NORMAL and a_score.modality_type == AUDIO


def test_get_recent_orders_oldest_to_newest():
    """Bucket lists are ordered oldest -> newest."""
    buf = RollingBuffer(60, "unit-test-key")
    for ts in (30.0, 10.0, 20.0):
        buf.append(audio(ts), score(ts))
    entries = buf.get_recent(60)["audio"]
    assert [chunk.timestamp for chunk, _ in entries] == [10.0, 20.0, 30.0]


def test_expiry_after_60_seconds():
    """Appending past the window expires entries older than newest - 60 s."""
    buf = RollingBuffer(60, "unit-test-key")
    for ts in (0.0, 30.0, 100.0):
        buf.append(audio(ts), score(ts))
    result = buf.get_recent(60)
    assert [c.timestamp for c, _ in result["audio"]] == [100.0]


def test_expiry_boundary_is_inclusive():
    """An entry exactly buffer_size_sec old is kept; one second older is not."""
    buf = RollingBuffer(60, "unit-test-key")
    buf.append(audio(40.0), score(40.0))
    buf.append(audio(100.0), score(100.0))  # cutoff = 100 - 60 = 40
    assert [c.timestamp for c, _ in buf.get_recent(60)["audio"]] == [40.0, 100.0]
    older = RollingBuffer(60, "unit-test-key")
    older.append(audio(39.0), score(39.0))
    older.append(audio(100.0), score(100.0))  # 39 < cutoff 40 -> expired
    assert [c.timestamp for c, _ in older.get_recent(60)["audio"]] == [100.0]


def test_get_recent_sub_window():
    """get_recent(seconds) filters relative to the newest entry."""
    buf = RollingBuffer(60, "unit-test-key")
    for ts in (0.0, 50.0, 59.0):
        buf.append(audio(ts), score(ts))
    entries = buf.get_recent(10)["audio"]  # cutoff = 59 - 10 = 49
    assert [c.timestamp for c, _ in entries] == [50.0, 59.0]


def test_get_recent_seconds_capped_to_window():
    """seconds larger than the buffer window returns the whole window."""
    buf = RollingBuffer(30, "unit-test-key")
    for ts in (0.0, 20.0):
        buf.append(audio(ts), score(ts))
    entries = buf.get_recent(5000)["audio"]
    assert [c.timestamp for c, _ in entries] == [0.0, 20.0]


def test_empty_buffer():
    """An empty buffer returns empty buckets, not an error."""
    buf = RollingBuffer(60, "unit-test-key")
    result = buf.get_recent(30)
    assert result == {"audio": [], "motion": [], "vision": []}


def test_append_rejects_plain_dict():
    """A plain dict is not a contract chunk and must be rejected."""
    buf = RollingBuffer(60, "unit-test-key")
    with pytest.raises(TypeError):
        buf.append({"timestamp": 1.0}, score(1.0))


def test_save_load_roundtrip(tmp_path):
    """Saved buckets survive a save -> new buffer -> load roundtrip."""
    path = storage(tmp_path)
    buf = RollingBuffer(60, "unit-test-key", storage_path=path)
    buf.append(audio(10.0), score(10.0))
    buf.append(imu(15.0), score(15.0))
    buf.append(frame(20.0), score(20.0))
    buf.save_to_storage()
    buf.save_to_storage()  # idempotent

    restored = RollingBuffer(60, "unit-test-key", storage_path=path)
    restored.load_from_storage()
    restored.load_from_storage()  # idempotent
    result = restored.get_recent(60)
    assert [c.timestamp for c, _ in result["audio"]] == [10.0]
    assert [c.timestamp for c, _ in result["motion"]] == [15.0]
    assert [c.timestamp for c, _ in result["vision"]] == [20.0]
    assert result["audio"][0][0].source_id == "mic-1"
    assert result["audio"][0][1].confidence == 0.9


def test_load_replaces_and_reexpires(tmp_path):
    """Loading replaces entries and re-expires against the new window."""
    path = storage(tmp_path)
    buf = RollingBuffer(60, "unit-test-key", storage_path=path)
    for ts in (0.0, 10.0, 20.0):
        buf.append(audio(ts), score(ts))
    buf.save_to_storage()

    tighter = RollingBuffer(5, "unit-test-key", storage_path=path)
    tighter.load_from_storage()  # only ts=20 is within 5 s of newest
    assert [c.timestamp for c, _ in tighter.get_recent(5)["audio"]] == [20.0]


def test_encrypted_file_hides_plaintext_marker(tmp_path):
    """A marker visible in the pickled payload is absent from the file."""
    path = storage(tmp_path)
    marker = "TOP-SECRET-BLACKBOX-MARKER-42"
    buf = RollingBuffer(60, "unit-test-key", storage_path=path)
    buf.append(audio(10.0), score(10.0, raw_features={"marker": marker}))
    buf.save_to_storage()

    plain = pickle.dumps(
        [(10.0, buf.get_recent(60)["audio"][0][0],
          buf.get_recent(60)["audio"][0][1])],
        protocol=pickle.HIGHEST_PROTOCOL,
    )
    assert marker.encode() in plain  # sanity: marker really is in plaintext
    with open(path, "rb") as fh:
        blob = fh.read()
    assert marker.encode() not in blob
    assert blob != plain  # file is actually transformed, not a copy


def test_tampered_file_raises_value_error(tmp_path):
    """Any byte flip in the stored file must fail authentication."""
    path = storage(tmp_path)
    buf = RollingBuffer(60, "unit-test-key", storage_path=path)
    buf.append(audio(10.0), score(10.0))
    buf.save_to_storage()

    with open(path, "rb") as fh:
        blob = bytearray(fh.read())
    blob[-1] ^= 0xFF  # flip a tag byte
    with open(path, "wb") as fh:
        fh.write(bytes(blob))
    with pytest.raises(ValueError):
        RollingBuffer(60, "unit-test-key", storage_path=path).load_from_storage()


def test_truncated_file_raises_value_error(tmp_path):
    """A truncated file is rejected rather than half-loaded."""
    path = storage(tmp_path)
    buf = RollingBuffer(60, "unit-test-key", storage_path=path)
    buf.append(audio(10.0), score(10.0))
    buf.save_to_storage()
    with open(path, "rb") as fh:
        blob = fh.read()
    with open(path, "wb") as fh:
        fh.write(blob[:10])
    with pytest.raises(ValueError):
        RollingBuffer(60, "unit-test-key", storage_path=path).load_from_storage()


def test_missing_file_raises_file_not_found(tmp_path):
    """Loading from a missing storage path raises FileNotFoundError."""
    buf = RollingBuffer(60, "unit-test-key", storage_path=storage(tmp_path))
    with pytest.raises(FileNotFoundError):
        buf.load_from_storage()


def test_wrong_key_raises_value_error(tmp_path):
    """A different key cannot authenticate the stored file."""
    path = storage(tmp_path)
    RollingBuffer(60, "unit-test-key", storage_path=path)  # not saved: empty
    buf = RollingBuffer(60, "right-key", storage_path=path)
    buf.append(audio(10.0), score(10.0))
    buf.save_to_storage()
    with pytest.raises(ValueError):
        RollingBuffer(60, "wrong-key", storage_path=path).load_from_storage()
