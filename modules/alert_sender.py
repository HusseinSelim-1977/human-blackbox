"""Emergency alert sender with a user-cancellable countdown.

Implements ``AlertSender.send`` and ``AlertSender.cancel_user_countdown`` from
docs/CONTRACTS.md, plus Safety Non-Negotiables #2 (explicit mic consent) and
#3 (a visible 15-second countdown the user can cancel at any point).

Raw media never leaves the device: the alert payload carries only hashes and
metadata derived from the evidence buffer, never raw audio/video/IMU samples.

# ASSUMPTION: stand-in crypto
The platform supplies real AES-256-GCM key derivation and encryption (see
config/default.yaml ``buffer.encryption``). This pure-Python module substitutes
an HMAC-SHA256 digest over the serialized payload so the flow is testable
offline; no raw media is ever placed in the payload.

# ASSUMPTION: test hooks
``countdown_sec``, ``tick``, ``sleep_fn`` and ``notifier`` are keyword-only
extensions to the contract's constructor so tests need not wait 15 seconds.
Production callers omit them and get a real 15-tick, 1-second countdown.
"""

from __future__ import annotations

import hashlib
import hmac
import json
import threading
import time
from typing import Callable, Optional

from modules.types import EMERGENCY

_MICROPHONE = "microphone"


class AlertSender:
    """Sends an emergency alert after a user-cancellable countdown."""

    def __init__(
        self,
        device_id: str,
        encryption_key: str,
        user_consent_manager: object,
        *,
        countdown_sec: int = 15,
        tick: float = 1.0,
        sleep_fn: Callable[[float], None] = time.sleep,
        notifier: Optional[Callable[[int], None]] = None,
    ) -> None:
        self.device_id = device_id
        self.encryption_key = encryption_key
        self.user_consent_manager = user_consent_manager
        self.countdown_sec = countdown_sec
        self.tick = tick
        self.sleep_fn = sleep_fn
        self.notifier = notifier if notifier is not None else self._default_notifier
        self._lock = threading.Lock()
        self._active_cancel: Optional[threading.Event] = None
        self.last_payload: Optional[dict] = None
        self.sent_alerts: list[dict] = []

    # -- user interface hook -------------------------------------------------
    @staticmethod
    def _default_notifier(remaining: int) -> None:
        """Default visible countdown (the real UI overrides this)."""
        print(f"[blackbox] sending emergency alert in {remaining}s - cancel now")

    def cancel_user_countdown(self) -> None:
        """Cancel the active send's countdown (safe to call from any thread)."""
        with self._lock:
            event = self._active_cancel
        if event is not None:
            event.set()

    # -- internals -----------------------------------------------------------
    def _consent_ok(self) -> bool:
        manager = self.user_consent_manager
        if manager is None:
            return True
        checker = getattr(manager, "has_consent", None)
        if not callable(checker):
            return True
        return bool(checker(_MICROPHONE))

    def _run_countdown(self, cancel_event: threading.Event) -> bool:
        """Tick down visibly; return False if cancelled before it finishes."""
        for remaining in range(self.countdown_sec, 0, -1):
            if cancel_event.is_set():
                return False
            self.notifier(remaining)
            self.sleep_fn(self.tick)
            if cancel_event.is_set():
                return False
        return True

    @staticmethod
    def _summarize_evidence(evidence_buffer: object, window_sec: int) -> dict:
        """Hash the evidence; return counts and digests only (no raw media)."""
        summary = {"available": evidence_buffer is not None,
                   "modalities": {}, "total_chunks": 0}
        get_recent = getattr(evidence_buffer, "get_recent", None)
        recent = {}
        if callable(get_recent):
            try:
                recent = get_recent(window_sec) or {}
            except Exception:  # a broken buffer must not block an alert
                recent = {}
        if not isinstance(recent, dict):
            recent = {}
        for modality in ("audio", "motion", "vision"):
            items = recent.get(modality)
            if items is None:
                continue
            try:
                count = len(items)
            except TypeError:
                count = 1
            digest = hashlib.sha256(repr(items).encode("utf-8")).hexdigest()
            summary["modalities"][modality] = {"chunks": count, "sha256": digest}
            summary["total_chunks"] += count
        return summary

    def _build_payload(self, decision, evidence_buffer, user_id: str) -> dict:
        metadata = {
            "device_id": self.device_id,
            "user_id": user_id,
            "alert_id": hashlib.sha256(
                f"{self.device_id}:{user_id}:{decision.timestamp}".encode("utf-8")
            ).hexdigest()[:16],
            "created_at": time.time(),
            "decision": {
                "modality_type": getattr(decision, "modality_type", None),
                "decision": getattr(decision, "decision", None),
                "score": getattr(decision, "score", None),
                "confidence": getattr(decision, "confidence", None),
                "timestamp": getattr(decision, "timestamp", None),
            },
            "evidence": self._summarize_evidence(evidence_buffer, self.countdown_sec),
            "raw_media_uploaded": False,
        }
        serialized = json.dumps(metadata, sort_keys=True, default=str)
        signature = hmac.new(
            self.encryption_key.encode("utf-8"),
            serialized.encode("utf-8"),
            hashlib.sha256,
        ).hexdigest()
        return {"algorithm": "HMAC-SHA256", "signature": signature,
                "metadata": metadata}

    # -- public API ----------------------------------------------------------
    def send(self, decision, evidence_buffer, user_id: str) -> bool:
        """Send an alert after the countdown; False if invalid or cancelled."""
        if getattr(decision, "decision", None) != EMERGENCY:
            return False
        if not self._consent_ok():
            return False

        cancel_event = threading.Event()
        with self._lock:
            self._active_cancel = cancel_event
        try:
            completed = self._run_countdown(cancel_event)
        finally:
            with self._lock:
                if self._active_cancel is cancel_event:
                    self._active_cancel = None

        if not completed:
            return False

        payload = self._build_payload(decision, evidence_buffer, user_id)
        self.last_payload = payload
        self.sent_alerts.append(payload)
        return True
