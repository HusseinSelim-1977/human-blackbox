"""Encrypted rolling buffer for Human BlackBox sensor data.

Keeps the most recent ``buffer_size_sec`` seconds of AudioChunk /
ImuSample / Frame entries together with their ModalityScore, expiring
older data automatically on append. Raw media never leaves this object
except through ``get_recent`` (decrypted, in memory) or the encrypted
at-rest file written by ``save_to_storage``.

At-rest encryption (# ASSUMPTION: stand-in crypto, because the sandbox
has no AES library): the pickled payload is XOR-masked with a keystream
derived via ``hashlib.sha256(key + nonce)`` (extended per 32-byte block
with a big-endian counter for longer payloads) and authenticated with
HMAC-SHA256. Production uses the platform's AES-256-GCM (config key
``buffer.encryption``); this class keeps the same file layout contract.
"""

from __future__ import annotations

import hashlib
import hmac
import os
import pickle
import threading
from typing import Dict, List, Tuple, Union

from modules.types import (
    AudioChunk,
    Frame,
    ImuSample,
    ModalityScore,
    SensorChunk,
)

# One in-memory entry: (chunk timestamp, chunk, paired score).
_Entry = Tuple[float, SensorChunk, ModalityScore]

# Contract bucket keys for get_recent(), by chunk type.
_BUCKETS: Dict[type, str] = {
    AudioChunk: "audio",
    ImuSample: "motion",
    Frame: "vision",
}

_NONCE_LEN = 16
_TAG_LEN = 32  # HMAC-SHA256 digest size


class RollingBuffer:
    """Thread-safe rolling window of sensor chunks and their scores.

    Entries older than ``buffer_size_sec`` behind the newest entry are
    expired on every append and after every load. ``get_recent`` returns
    per-modality buckets of ``(chunk, score)`` tuples ordered oldest to
    newest.
    """

    def __init__(self, buffer_size_sec: int, encryption_key: str, *,
                 storage_path: str = "buffer.enc") -> None:
        """Create an empty buffer.

        ``storage_path`` is keyword-only (# ASSUMPTION: compatible
        extension of the CONTRACTS.md signature, which names only
        ``buffer_size_sec`` and ``encryption_key``).
        """
        if buffer_size_sec <= 0:
            raise ValueError("buffer_size_sec must be positive")
        if not isinstance(encryption_key, str) or not encryption_key:
            raise ValueError("encryption_key must be a non-empty string")
        self.buffer_size_sec = buffer_size_sec
        self._key = encryption_key.encode("utf-8")
        self.storage_path = storage_path
        self._entries: List[_Entry] = []
        self._lock = threading.Lock()

    # ------------------------------------------------------------------
    # Contract methods
    # ------------------------------------------------------------------

    def append(self, data_chunk: Union[AudioChunk, ImuSample, Frame],
               modality_score: ModalityScore) -> None:
        """Append a chunk with its score, then expire stale entries."""
        if not isinstance(data_chunk, (AudioChunk, ImuSample, Frame)):
            raise TypeError(f"data_chunk must be an AudioChunk, ImuSample or "
                            f"Frame, got {type(data_chunk).__name__}")
        if not isinstance(modality_score, ModalityScore):
            raise TypeError(f"modality_score must be a ModalityScore, got "
                            f"{type(modality_score).__name__}")
        with self._lock:
            self._entries.append(
                (float(data_chunk.timestamp), data_chunk, modality_score)
            )
            self._expire_locked()

    def get_recent(self, seconds: int) -> dict:
        """Return the last ``seconds`` of data, capped to the window.

        The result maps the contract keys ``"audio"``, ``"motion"`` and
        ``"vision"`` to lists of ``(chunk, score)`` tuples ordered oldest
        to newest, filtered relative to the newest stored entry.
        """
        if isinstance(seconds, bool) or not isinstance(seconds, (int, float)):
            raise TypeError("seconds must be a number")
        with self._lock:
            snapshot = list(self._entries)
        buckets: Dict[str, list] = {"audio": [], "motion": [], "vision": []}
        if not snapshot:
            return buckets
        window = min(float(seconds), float(self.buffer_size_sec))
        if window < 0:
            raise ValueError("seconds must be non-negative")
        cutoff = max(ts for ts, _, _ in snapshot) - window
        for ts, chunk, score in sorted(snapshot, key=lambda e: e[0]):
            if ts >= cutoff:
                buckets[_BUCKETS[type(chunk)]].append((chunk, score))
        return buckets

    def save_to_storage(self) -> None:
        """Atomically write the encrypted buffer to ``storage_path``.

        Idempotent: repeated calls simply overwrite the file.
        """
        with self._lock:
            payload = pickle.dumps(list(self._entries),
                                   protocol=pickle.HIGHEST_PROTOCOL)
        nonce = os.urandom(_NONCE_LEN)
        ciphertext = self._xor(payload, nonce)
        tag = hmac.new(self._key, nonce + ciphertext, hashlib.sha256).digest()
        tmp_path = self.storage_path + ".tmp"
        with open(tmp_path, "wb") as fh:
            fh.write(nonce + ciphertext + tag)
        os.replace(tmp_path, self.storage_path)

    def load_from_storage(self) -> None:
        """Replace in-memory entries with the decrypted stored buffer.

        Raises ``FileNotFoundError`` if the file is missing and
        ``ValueError`` if the file is truncated or fails authentication.
        """
        with open(self.storage_path, "rb") as fh:
            blob = fh.read()
        if len(blob) < _NONCE_LEN + _TAG_LEN:
            raise ValueError("storage file is truncated or corrupt")
        nonce = blob[:_NONCE_LEN]
        ciphertext = blob[_NONCE_LEN:-_TAG_LEN]
        tag = blob[-_TAG_LEN:]
        expected = hmac.new(self._key, nonce + ciphertext,
                            hashlib.sha256).digest()
        if not hmac.compare_digest(tag, expected):
            raise ValueError("storage file failed authentication (tampered)")
        payload = self._xor(ciphertext, nonce)
        entries = self._decode(payload)
        with self._lock:
            self._entries = entries
            self._expire_locked()

    # ------------------------------------------------------------------
    # Internal helpers
    # ------------------------------------------------------------------

    def _expire_locked(self) -> None:
        """Drop entries older than the window behind the newest entry."""
        if not self._entries:
            return
        cutoff = max(ts for ts, _, _ in self._entries) - self.buffer_size_sec
        self._entries = [e for e in self._entries if e[0] >= cutoff]

    def _xor(self, payload: bytes, nonce: bytes) -> bytes:
        """Mask ``payload`` with the SHA-256 derived keystream."""
        out = bytearray(len(payload))
        for pos in range(0, len(payload), 32):
            block = hashlib.sha256(
                self._key + nonce + (pos // 32).to_bytes(4, "big")
            ).digest()
            chunk = payload[pos : pos + 32]
            out[pos : pos + len(chunk)] = bytes(
                b ^ k for b, k in zip(chunk, block)
            )
        return bytes(out)

    def _decode(self, payload: bytes) -> List[_Entry]:
        """Unpickle and validate stored entries as plain contract objects."""
        try:
            entries = pickle.loads(payload)
        except Exception as exc:
            raise ValueError(f"stored payload is not decodable: {exc}") from exc
        if not isinstance(entries, list):
            raise ValueError("stored payload is not an entry list")
        for entry in entries:
            if (
                not isinstance(entry, tuple)
                or len(entry) != 3
                or not isinstance(entry[1], (AudioChunk, ImuSample, Frame))
                or not isinstance(entry[2], ModalityScore)
            ):
                raise ValueError("stored payload holds a malformed entry")
        return [(float(ts), chunk, score) for ts, chunk, score in entries]
