"""Emergency detection pipeline.

Wires the three modality detectors, the fusion strategy, the rolling
evidence buffer and the alert sender into a single safety-gated flow.

Only :mod:`modules.types` is imported at module level so that the pipeline
can be unit-tested with stubs and never pulls in optional model runtimes.
"""

from __future__ import annotations

import time
from typing import Any, Dict, List, Mapping, Optional

from modules.types import (
    AUDIO,
    EMERGENCY,
    FUSION,
    INDETERMINATE,
    MOTION,
    VISION,
    ModalityScore,
)

# Sensors that require explicit user consent before capture starts.
_CONSENT_SENSORS = {
    "microphone": "microphone",
    "audio": "microphone",
    "camera": "camera",
    "vision": "camera",
}

_DEFAULT_USER_ID = "default_user"


def _cfg(config: Any, *path: str, default: Any = None) -> Any:
    """Read a nested config value from either a mapping or an object."""
    node = config
    for key in path:
        if node is None:
            return default
        if isinstance(node, Mapping):
            if key not in node:
                return default
            node = node[key]
        else:
            node = getattr(node, key, None)
            if node is None:
                return default
    return default if node is None else node


def _section(config: Any, *path: str) -> Dict[str, Any]:
    """Return a config section as a plain dict (empty when missing)."""
    value = _cfg(config, *path, default={})
    if isinstance(value, Mapping):
        return dict(value)
    if hasattr(value, "__dict__"):
        return dict(vars(value))
    return {}


class EmergencyPipeline:
    """End-to-end detection pipeline with safety gates.

    Every collaborator is injected so that the pipeline is deterministic and
    testable without models, sensors, or network access.
    """

    def __init__(self, audio_detector: Any, motion_detector: Any,
                 vision_detector: Any, fusion: Any, buffer: Any,
                 alert_sender: Any, consent_manager: Any, config: Any):
        self.audio_detector = audio_detector
        self.motion_detector = motion_detector
        self.vision_detector = vision_detector
        self.fusion = fusion
        self.buffer = buffer
        self.alert_sender = alert_sender
        self.consent_manager = consent_manager
        self.config = config

        self.last_decision: Optional[ModalityScore] = None
        self.user_id: str = str(
            _cfg(config, "user_id", default=_DEFAULT_USER_ID)
            or _DEFAULT_USER_ID
        )
        self._active_captures: set = set()

    # ------------------------------------------------------------------
    # Detection
    # ------------------------------------------------------------------
    def process(self, audio_chunk: Any = None, imu_sample: Any = None,
                frame: Any = None) -> ModalityScore:
        """Run the detectors that have input, fuse, and alert if needed.

        Only the modalities whose chunk is not ``None`` are evaluated.  Each
        result is appended to the rolling buffer together with its source
        chunk.  The fused decision is stored in ``last_decision`` and an
        alert is sent exactly once per call when it is ``EMERGENCY``.
        """
        scores: List[ModalityScore] = []

        if audio_chunk is not None:
            score = self.audio_detector.predict(audio_chunk, self.config)
            self.buffer.append(audio_chunk, score)
            scores.append(score)

        if imu_sample is not None:
            score = self.motion_detector.predict(imu_sample, self.config)
            self.buffer.append(imu_sample, score)
            scores.append(score)

        if frame is not None:
            score = self.vision_detector.predict(frame, self.config)
            self.buffer.append(frame, score)
            scores.append(score)

        if scores:
            fused = self.fusion.decide(scores, self.config)
        else:
            # # ASSUMPTION: an empty round has no evidence, so fusion is
            # not invoked and the pipeline reports INDETERMINATE.
            fused = ModalityScore(
                score=0.0,
                confidence=0.0,
                decision=INDETERMINATE,
                timestamp=time.time(),
                modality_type=FUSION,
                raw_features={"reason": "no sensor input"},
            )

        self.last_decision = fused

        if fused.decision == EMERGENCY:
            # # ASSUMPTION: AlertSender.send() runs the user-cancellable
            # countdown and owns the decision to actually transmit; a False
            # return means the user cancelled and must not change the
            # detected decision returned to the caller.
            self.alert_sender.send(fused, self.buffer, self.user_id)

        return fused

    # ------------------------------------------------------------------
    # Safety audit
    # ------------------------------------------------------------------
    def safety_audit(self) -> Dict[str, bool]:
        """Report whether each safety non-negotiable is currently satisfied."""
        safety = _section(self.config, "safety")
        alert = _section(self.config, "alert")

        require_mic = bool(safety.get("require_mic_consent", True))
        require_camera = bool(safety.get("require_camera_consent", True))
        indicator_enabled = bool(safety.get("recording_indicator", True))
        raw_on_device = bool(safety.get("raw_media_on_device", True))
        share_by_default = bool(safety.get("share_evidence_default", False))

        try:
            countdown_ok = int(alert.get("countdown_sec", 15)) > 0
        except (TypeError, ValueError):
            countdown_ok = False

        return {
            "recording_indicator": indicator_enabled and self._indicator_ok(),
            "mic_consent": (not require_mic) or self._has_consent("microphone"),
            "camera_consent": (not require_camera) or self._has_consent("camera"),
            "countdown_cancellable": countdown_ok,
            "raw_media_on_device": raw_on_device and not share_by_default,
        }

    # ------------------------------------------------------------------
    # Capture control (delegates to the consent manager)
    # ------------------------------------------------------------------
    def start_capture(self, sensor: str) -> bool:
        """Start capture for ``sensor``; requires consent where applicable.

        Returns ``True`` when capture is active.  The consent manager turns
        the visible recording indicator on and returns ``False`` when the
        required consent has not been granted.
        """
        name = str(sensor).strip().lower()
        if name in _CONSENT_SENSORS:
            consent_sensor = _CONSENT_SENSORS[name]
            if self.consent_manager is None:
                return False
            started = bool(
                self.consent_manager.start_recording(consent_sensor)
            )
        else:
            # Motion/IMU capture is not consent-gated.
            started = True

        if started:
            self._active_captures.add(name)
        return started

    def stop_capture(self, sensor: str) -> None:
        """Stop capture for ``sensor`` and let the manager update the indicator."""
        name = str(sensor).strip().lower()
        self._active_captures.discard(name)
        if name in _CONSENT_SENSORS and self.consent_manager is not None:
            self.consent_manager.stop_recording(_CONSENT_SENSORS[name])

    # ------------------------------------------------------------------
    # Internal helpers
    # ------------------------------------------------------------------
    def _has_consent(self, sensor: str) -> bool:
        if self.consent_manager is None:
            return False
        checker = getattr(self.consent_manager, "has_consent", None)
        if checker is None:
            return False
        try:
            return bool(checker(sensor))
        except Exception:
            return False

    def _indicator_ok(self) -> bool:
        """Indicator must be visible whenever a consent-gated capture runs."""
        if not (self._active_captures & set(_CONSENT_SENSORS)):
            return True
        visible = getattr(
            self.consent_manager, "recording_indicator_visible", True
        )
        return bool(visible)
