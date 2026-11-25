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

Ambient baseline tracking: maintains rolling mean/std for each modality's
raw features to enable normalized scoring against typical activity levels.
"""

from __future__ import annotations

import hashlib
import hmac
import os
import pickle
import threading
from typing import Any, Dict, List, Optional, Tuple, Union

from modules.types import (
    AudioChunk,
    Frame,
    ImuSample,
    ModalityScore,
    SensorChunk,
)

# One in-memory entry: (chunk timestamp, chunk, paired score).
_Entry = Tuple[float, SensorChunk, ModalityScore]

# Ambient baseline state: per-modality rolling stats (mean, std) for feature keys
_AmbientState = Dict[str, Dict[str, List[float]]]

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

    Ambient baseline tracking: maintains rolling mean/std for each
    modality's raw features to enable normalized scoring against typical
    activity levels.
    """

    def __init__(self, buffer_size_sec: int, encryption_key: str, *,
                 storage_path: str = "buffer.enc",
                 ambient_window_sec: int = 30,
                 ambient_features: Dict[str, List[str]] = None) -> None:
        """Create an empty buffer.

        ``storage_path`` is keyword-only (# ASSUMPTION: compatible
        extension of the CONTRACTS.md signature, which names only
        ``buffer_size_sec`` and ``encryption_key``).

        ``ambient_window_sec``: rolling window for baseline stats.
        ``ambient_features``: per-modality feature keys to track.
        """
        if buffer_size_sec <= 0:
            raise ValueError("buffer_size_sec must be positive")
        if not isinstance(encryption_key, str) or not encryption_key:
            raise ValueError("encryption_key must be a non-empty string")
        if isinstance(ambient_window_sec, bool) or not isinstance(ambient_window_sec, int):
            raise TypeError("ambient_window_sec must be an int")
        if ambient_window_sec < 1:
            raise ValueError("ambient_window_sec must be at least 1")
        self.buffer_size_sec = buffer_size_sec
        self._key = encryption_key.encode("utf-8")
        self.storage_path = storage_path
        self._entries: List[_Entry] = []
        self._lock = threading.Lock()

        # Ambient baseline state: {"audio": {"energy": [v1, v2, ...]}, ...}
        self._ambient_window = ambient_window_sec
        # Accept either the canonical lowercase keys or the config file's
        # uppercase modality keys (``ambient_baselines.per_modality``).
        defaults: Dict[str, List[str]] = {
            "audio": ["energy", "spectral_centroid", "spectral_rolloff"],
            "motion": ["accel_magnitude", "gyro_magnitude", "temp"],
            "vision": ["brightness", "edge_density", "red_dominance"],
        }
        supplied: Dict[str, List[str]] = {}
        for key, feats in (ambient_features or {}).items():
            if not isinstance(key, str) or not isinstance(feats, (list, tuple)):
                continue
            cleaned = [str(feat) for feat in feats]
            if cleaned:
                supplied[key.strip().lower()] = cleaned
        self._ambient_features: Dict[str, List[str]] = {
            modality: supplied.get(modality, defaults[modality])
            for modality in ("audio", "motion", "vision")
        }
        self._ambient_state: _AmbientState = {
            modality: {feat: [] for feat in feats}
            for modality, feats in self._ambient_features.items()
        }

    # ------------------------------------------------------------------
    # Contract methods
    # ------------------------------------------------------------------

    def append(self, data_chunk: Union[AudioChunk, ImuSample, Frame],
               modality_score: ModalityScore) -> None:
        """Append a chunk with its score, then expire stale entries.

        Also updates ambient baseline stats for the chunk's modality.
        """
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
            # Update ambient baselines for this modality
            self._update_ambient_baseline(
                type(data_chunk),
                modality_score.raw_features,
            )

    def get_recent(self, seconds: int) -> dict:
        """Return the last ``seconds`` of data, capped to the window.

        The result maps the contract keys ``"audio"``, ``"motion"`` and
        ``"vision"`` to lists of ``(chunk, score)`` tuples ordered oldest
        to newest, filtered relative to the newest stored entry.
        """
        if isinstance(seconds, bool) or not isinstance(seconds, (int, float)):
            raise TypeError("seconds must be a number")
        if float(seconds) < 0:
            raise ValueError("seconds must be non-negative")
        with self._lock:
            snapshot = list(self._entries)
        buckets: Dict[str, list] = {"audio": [], "motion": [], "vision": []}
        if not snapshot:
            return buckets
        window = min(float(seconds), float(self.buffer_size_sec))
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

    def _update_ambient_baseline(
        self, modality: type, features: Dict[str, Any]
    ) -> None:
        """Update rolling mean/std for a modality's features.

        Maintains a sliding window of feature values and computes
        running mean/std for each tracked feature.
        """
        modality_key = _BUCKETS[modality]
        for feat in self._ambient_features[modality_key]:
            if feat not in features:
                continue
            try:
                val = float(features[feat])
            except (TypeError, ValueError):
                # Non-numeric ambient values must never break capture.
                continue
            if val != val or val in (float("inf"), float("-inf")):
                continue  # skip NaN/inf so mean/std stay finite
            self._ambient_state[modality_key][feat].append(val)
            # Trim to window size
            self._ambient_state[modality_key][feat] = self._ambient_state[
                modality_key
            ][feat][-self._ambient_window :]

    def get_ambient_stats(self) -> Dict[str, Dict[str, Any]]:
        """Return current ambient baseline stats per modality.

        Maps lowercase modality -> feature -> ``{"mean", "std", "count"}``.
        A feature with no samples reports ``mean=0.0, std=0.0, count=0`` and a
        single sample reports ``std=0.0`` so downstream z-scoring stays stable.
        """
        with self._lock:
            snapshot = {
                modality: {feat: list(vals) for feat, vals in feats.items()}
                for modality, feats in self._ambient_state.items()
            }
        stats = {}
        for modality, features in snapshot.items():
            if not features:
                continue
            mod_stats = {}
            for feat in features:
                vals = features[feat]
                if not vals:
                    mod_stats[feat] = {"mean": 0.0, "std": 0.0, "count": 0}
                elif len(vals) < 2:
                    mod_stats[feat] = {"mean": vals[0], "std": 0.0, "count": 1}
                else:
                    mean = sum(vals) / len(vals)
                    variance = sum((x - mean) ** 2 for x in vals) / len(vals)
                    mod_stats[feat] = {"mean": mean, "std": variance ** 0.5, "count": len(vals)}
            stats[modality] = mod_stats
        return stats
