"""Vision detector (role 3) of the Human BlackBox Edge AI Emergency Detection System.

Scores Frames into ModalityScore (docs/CONTRACTS.md); YOLOv8-nano when the
model file exists, else a deterministic numpy pixel-statistics heuristic.
"""

import os
from typing import Any, Dict

import numpy as np

from modules.types import (
    EMERGENCY,
    INDETERMINATE,
    NORMAL,
    VISION,
    Frame,
    ModalityScore,
)

DARK_BRIGHTNESS = 40.0   # pixel brightness below this counts as dark
FIRE_R_MIN, FIRE_G_MAX, FIRE_B_MAX = 180.0, 140.0, 90.0  # fire-like pixel test
EDGE_NORM = 64.0         # mean gradient magnitude mapped to edge_density 1.0
W_FIRE = 1.6             # gain on fire_ratio
W_OBSTRUCTION = 2.0      # gain on dark_ratio * edge_density
W_CLOSE_PERSON = 1.2     # gain on edge_density * (1 - dark_ratio)
DEFAULT_THRESHOLD = 0.50  # mirrors detectors.vision.threshold, config/default.yaml
MIN_CONFIDENCE = 0.30     # mirrors fusion min_confidence, config/default.yaml
DEFAULT_EMERGENCY_CLASSES = ("person", "fire hydrant", "knife", "car", "motorcycle")


def _vision_section(config: Any) -> Dict[str, Any]:
    """Accept a full config dict or the detectors.vision sub-dict."""
    if not isinstance(config, dict):
        return {}
    detectors = config.get("detectors")
    if isinstance(detectors, dict) and isinstance(detectors.get("vision"), dict):
        return detectors["vision"]
    return config


def _empty_features() -> Dict[str, Any]:
    """raw_features payload for frames that could not be analysed."""
    return {
        "mean_rgb": [0.0, 0.0, 0.0],
        "brightness": 0.0,
        "red_dominance": 0.0,
        "fire_ratio": 0.0,
        "dark_ratio": 0.0,
        "edge_density": 0.0,
        "mode": "invalid",
    }


def _pixel_stats(image: np.ndarray) -> Dict[str, Any]:
    """Deterministic pixel statistics for an (h, w, 3) RGB image."""
    r, g, b = image[:, :, 0], image[:, :, 1], image[:, :, 2]
    brightness_map = (r + g + b) / 3.0
    gy, gx = np.gradient(brightness_map)
    edge_mag = np.sqrt(gx * gx + gy * gy)
    mean_rgb = [float(r.mean()), float(g.mean()), float(b.mean())]
    return {
        "mean_rgb": mean_rgb,
        "brightness": float(brightness_map.mean()),
        "red_dominance": (mean_rgb[0] - mean_rgb[2]) / 255.0,
        "fire_ratio": float(np.mean((r > FIRE_R_MIN) & (g < FIRE_G_MAX) & (b < FIRE_B_MAX))),
        "dark_ratio": float(np.mean(brightness_map < DARK_BRIGHTNESS)),
        # ASSUMPTION: EDGE_NORM=64 hand-tuned: indoor scenes ~0.1, clutter ~1.0.
        "edge_density": float(min(edge_mag.mean() / EDGE_NORM, 1.0)),
    }


class VisionDetector:
    """Vision modality detector (contract in docs/CONTRACTS.md)."""

    def __init__(self, model_path: str, config: dict):
        self.model_path = str(model_path)
        self._config = _vision_section(config)
        self._model = None
        self._model_error = None
        if self.model_path and os.path.isfile(self.model_path):
            self._load_yolo()

    def _load_yolo(self) -> None:
        # ASSUMPTION: heuristic fallback — no weights exist under
        # modules/models/ and ultralytics is not installed here, so the
        # numpy heuristic is the live path; the YOLO branch is untested.
        try:
            from ultralytics import YOLO  # type: ignore[import-untyped]
        except Exception as exc:
            self._model_error = f"ultralytics unavailable: {type(exc).__name__}: {exc}"
            return
        try:
            self._model = YOLO(self.model_path)
        except Exception as exc:
            self._model = None
            self._model_error = f"YOLO load failed: {type(exc).__name__}: {exc}"

    @property
    def mode(self) -> str:
        """'model' when a YOLOv8 model is loaded, else 'heuristic'."""
        return "model" if self._model is not None else "heuristic"

    def predict(self, frame: Frame, config: dict) -> ModalityScore:
        cfg = _vision_section(config) if isinstance(config, dict) else self._config
        threshold = float(cfg.get("threshold", DEFAULT_THRESHOLD))
        min_conf = float(cfg.get("min_confidence", MIN_CONFIDENCE))

        prepared = self._prepare(frame)
        if prepared is None:
            return ModalityScore(
                score=0.0,
                confidence=0.0,
                decision=INDETERMINATE,
                timestamp=frame.timestamp,
                modality_type=VISION,
                raw_features=_empty_features(),
            )
        image, stats = prepared
        if self._model is not None:
            score, confidence = self._predict_yolo(frame, image, cfg, stats)
        else:
            score, confidence = self._heuristic_score(stats)

        score = round(float(score), 6)
        confidence = round(float(confidence), 6)
        # EMERGENCY needs score >= threshold AND confidence >= min_conf.
        decision = EMERGENCY if score >= threshold and confidence >= min_conf else NORMAL
        raw_features = dict(stats)
        raw_features["mode"] = self.mode
        return ModalityScore(
            score=score,
            confidence=confidence,
            decision=decision,
            timestamp=frame.timestamp,
            modality_type=VISION,
            raw_features=raw_features,
        )

    def _prepare(self, frame: Frame):
        """Validate pixel data; return (image, stats) or None when invalid."""
        expected = int(frame.width) * int(frame.height) * 3
        data = frame.image_data
        # ASSUMPTION: zero-size frames count as invalid input.
        if not isinstance(data, (list, tuple)) or expected <= 0 or len(data) != expected:
            return None
        pixels = np.asarray(data, dtype=np.float64)
        if pixels.shape != (expected,) or not bool(np.all(np.isfinite(pixels))):
            return None
        image = pixels.reshape(int(frame.height), int(frame.width), 3)
        return image, _pixel_stats(image)

    def _heuristic_score(self, stats: Dict[str, Any]):
        """Score = strongest of fire / obstruction / close-person cues
        (fire_ratio | dark_ratio*edge_density | edge_density*(1-dark_ratio));
        confidence is the winning cue's un-gained feature strength.
        """
        fire = min(1.0, stats["fire_ratio"] * W_FIRE)
        obstruction = min(1.0, stats["dark_ratio"] * stats["edge_density"] * W_OBSTRUCTION)
        close_person = min(1.0, stats["edge_density"] * (1.0 - stats["dark_ratio"]) * W_CLOSE_PERSON)
        cues = (
            (fire, stats["fire_ratio"]),
            (obstruction, min(1.0, stats["dark_ratio"] * stats["edge_density"])),
            (close_person, min(1.0, stats["edge_density"] * (1.0 - stats["dark_ratio"]))),
        )
        return max(cues)

    def _predict_yolo(self, frame: Frame, image: np.ndarray, cfg: Dict[str, Any], stats):
        # ASSUMPTION: heuristic fallback — YOLOv8 cannot run here (no
        # ultralytics/torch). Converts RGB->BGR for OpenCV-style arrays
        # and parses the documented ultralytics result-box API.
        emergency_classes = set(cfg.get("emergency_classes", DEFAULT_EMERGENCY_CLASSES))
        min_area = float(cfg.get("min_person_area_ratio", 0.15))
        bgr = np.ascontiguousarray(image.astype(np.uint8)[..., ::-1])
        try:
            result = self._model(bgr, verbose=False)[0]
        except Exception as exc:
            self._model = None
            self._model_error = f"inference failed: {type(exc).__name__}: {exc}"
            return self._heuristic_score(stats)
        best_conf = 0.0
        person_close = False
        frame_area = float(max(int(frame.width) * int(frame.height), 1))
        names = getattr(self._model, "names", None)
        names = names if isinstance(names, dict) else {}
        for box in getattr(result, "boxes", None) or []:
            conf = float(box.conf[0])
            name = str(names.get(int(box.cls[0]), int(box.cls[0])))
            if name in emergency_classes and conf > best_conf:
                best_conf = conf
            if name == "person" and conf >= 0.5:
                x1, y1, x2, y2 = (float(v) for v in box.xyxy[0])
                if max(0.0, x2 - x1) * max(0.0, y2 - y1) >= min_area * frame_area:
                    person_close = True
        # ASSUMPTION: a person box covering >= min_person_area_ratio of the
        # frame is a possible collapse cue.
        if person_close:
            return 0.85, 0.75
        return best_conf, best_conf
