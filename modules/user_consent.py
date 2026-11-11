"""User consent and recording-indicator management.

Implements Safety Non-Negotiables #1 (a visible recording indicator whenever
capture is active) and #2 (explicit microphone/camera consent that can be
revoked at any time) from docs/CONTRACTS.md.

Pure Python (threading only); no network and no media ever leaves the device.
"""

from __future__ import annotations

import threading

MICROPHONE = "microphone"
CAMERA = "camera"


class ConsentRequiredError(RuntimeError):
    """Raised when a recording is attempted without explicit consent."""


class UserConsentManager:
    """Tracks per-sensor consent and which sensors are actively recording.

    Consent defaults to *denied*: a sensor may only start recording after an
    explicit ``request_consent`` grant, and ``revoke_consent`` immediately
    stops any active capture for that sensor.
    """

    def __init__(self) -> None:
        self._consent: dict[str, bool] = {}
        self._recording: set[str] = set()
        self._lock = threading.RLock()

    def request_consent(self, sensor: str) -> bool:
        """Record an explicit consent grant for ``sensor`` (returns True)."""
        with self._lock:
            self._consent[sensor] = True
        return True

    def revoke_consent(self, sensor: str) -> None:
        """Revoke consent and stop recording for ``sensor`` immediately."""
        with self._lock:
            self._consent[sensor] = False
            self._recording.discard(sensor)

    def has_consent(self, sensor: str) -> bool:
        """Return True only if ``sensor`` currently has an active grant."""
        with self._lock:
            return bool(self._consent.get(sensor, False))

    def start_recording(self, sensor: str) -> bool:
        """Begin capture for ``sensor`` if consented; False otherwise.

        Turning capture on also turns the visible recording indicator on.
        """
        with self._lock:
            if not self._consent.get(sensor, False):
                return False
            self._recording.add(sensor)
        return True

    def stop_recording(self, sensor: str) -> None:
        """Stop capture for ``sensor``; indicator clears when none remain."""
        with self._lock:
            self._recording.discard(sensor)

    @property
    def recording_indicator_visible(self) -> bool:
        """True whenever any sensor is actively capturing."""
        with self._lock:
            return len(self._recording) > 0

    def require_consent(self, sensor: str) -> None:
        """Raise ConsentRequiredError unless ``sensor`` is consented."""
        if not self.has_consent(sensor):
            raise ConsentRequiredError(f"no consent granted for {sensor!r}")
