"""Encrypted, per-session evidence storage for Human BlackBox.

Raw media is kept on-device (see docs/CONTRACTS.md, Safety Non-Negotiables).
Each session is stored in one file: ``<storage_path>/<session_id>.evidence``.

File layout::

    MAGIC (4) | nonce (16) | HMAC-SHA256 (32) | ciphertext (variable)

The ciphertext is the UTF-8 JSON encoding of the evidence XORed with a
SHA-256-based counter keystream derived from ``encryption_key`` and the
per-save nonce. The HMAC covers MAGIC + nonce + ciphertext and is verified
before any decryption, so tampering raises ``ValueError``.

# ASSUMPTION: stand-in crypto -- production uses platform AES-256-GCM.
# ASSUMPTION: evidence values are JSON-serialisable; anything else is
#             stringified via ``json.dumps(..., default=str)``.
"""

from __future__ import annotations

import hashlib
import hmac
import json
import os
import re
import secrets
import threading

MAGIC = b"HBX1"
_NONCE_LEN = 16
_TAG_LEN = hashlib.sha256().digest_size
_SUFFIX = ".evidence"
_MAX_SESSION_ID = 128
_SAFE_SESSION_ID = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]*$")


def _keystream(key: bytes, nonce: bytes, length: int) -> bytes:
    """Expand ``sha256(key + nonce + counter)`` into ``length`` bytes."""
    out = bytearray()
    counter = 0
    while len(out) < length:
        out += hashlib.sha256(key + nonce + counter.to_bytes(8, "big")).digest()
        counter += 1
    return bytes(out[:length])


def _xor(data: bytes, stream: bytes) -> bytes:
    return bytes(a ^ b for a, b in zip(data, stream))


def _validate_session_id(session_id: str) -> str:
    """Reject anything that could escape ``storage_path``."""
    if not isinstance(session_id, str):
        raise ValueError("session_id must be a string")
    if not session_id:
        raise ValueError("session_id must not be empty")
    if len(session_id) > _MAX_SESSION_ID:
        raise ValueError("session_id is longer than 128 characters")
    if ".." in session_id or "/" in session_id or "\\" in session_id:
        raise ValueError(f"unsafe session_id: {session_id!r}")
    if not _SAFE_SESSION_ID.match(session_id):
        raise ValueError(f"unsafe session_id: {session_id!r}")
    return session_id


class EvidenceStore:
    """Store and load encrypted evidence tied to a user session."""

    def __init__(self, storage_path: str, encryption_key: str):
        if not isinstance(storage_path, str) or not storage_path:
            raise ValueError("storage_path must be a non-empty string")
        if not isinstance(encryption_key, str) or not encryption_key:
            raise ValueError("encryption_key must be a non-empty string")
        self._storage_path = os.path.abspath(storage_path)
        self._key = encryption_key.encode("utf-8")
        self._lock = threading.Lock()

    def _path(self, session_id: str) -> str:
        return os.path.join(self._storage_path, _validate_session_id(session_id) + _SUFFIX)

    def save(self, evidence: dict, session_id: str) -> bool:
        """Encrypt and atomically write evidence for ``session_id``."""
        if not isinstance(evidence, dict):
            raise TypeError("evidence must be a dict")
        path = self._path(session_id)

        plaintext = json.dumps(evidence, sort_keys=True, default=str).encode("utf-8")
        nonce = secrets.token_bytes(_NONCE_LEN)
        ciphertext = _xor(plaintext, _keystream(self._key, nonce, len(plaintext)))
        tag = hmac.new(
            self._key, MAGIC + nonce + ciphertext, hashlib.sha256
        ).digest()
        blob = MAGIC + nonce + tag + ciphertext

        with self._lock:
            os.makedirs(self._storage_path, exist_ok=True)
            tmp = os.path.join(
                self._storage_path,
                f".{secrets.token_hex(8)}.tmp",
            )
            try:
                fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
                with os.fdopen(fd, "wb") as handle:
                    handle.write(blob)
                    handle.flush()
                    os.fsync(handle.fileno())
                os.replace(tmp, path)
            except BaseException:
                try:
                    os.unlink(tmp)
                except FileNotFoundError:
                    pass
                raise
        return True

    def load(self, session_id: str) -> dict:
        """Read, verify and decrypt evidence for ``session_id``.

        Raises ``FileNotFoundError`` when no evidence exists and ``ValueError``
        when the file is malformed or has been tampered with.
        """
        path = self._path(session_id)
        with open(path, "rb") as handle:
            blob = handle.read()

        if len(blob) < len(MAGIC) + _NONCE_LEN + _TAG_LEN:
            raise ValueError("evidence file is truncated")
        if blob[: len(MAGIC)] != MAGIC:
            raise ValueError("evidence file has an invalid magic header")

        offset = len(MAGIC)
        nonce = blob[offset: offset + _NONCE_LEN]
        offset += _NONCE_LEN
        tag = blob[offset: offset + _TAG_LEN]
        offset += _TAG_LEN
        ciphertext = blob[offset:]

        expected = hmac.new(
            self._key, MAGIC + nonce + ciphertext, hashlib.sha256
        ).digest()
        if not hmac.compare_digest(tag, expected):
            raise ValueError("evidence file failed integrity check (tampered)")

        plaintext = _xor(ciphertext, _keystream(self._key, nonce, len(ciphertext)))
        try:
            evidence = json.loads(plaintext.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError) as exc:
            raise ValueError("evidence payload is not valid JSON") from exc
        if not isinstance(evidence, dict):
            raise ValueError("evidence payload is not a JSON object")
        return evidence

    def list_sessions(self) -> list[str]:
        """Return the stored session ids in sorted order."""
        try:
            names = os.listdir(self._storage_path)
        except FileNotFoundError:
            return []
        sessions = [
            name[: -len(_SUFFIX)]
            for name in names
            if name.endswith(_SUFFIX) and not name.startswith(".")
        ]
        return sorted(sessions)
