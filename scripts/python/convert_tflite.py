"""Convert operator-trained v2 models to TFLite artifacts.

No model is fetched and no result is reported: conversion requires a real
SavedModel directory supplied by the operator and a local TensorFlow install.
"""

from __future__ import annotations

import argparse
from pathlib import Path
from typing import Callable

MODELS = ("audio", "motion", "vision", "fusion", "personalization")
MODES = ("int8", "float16")


def _require_saved_model(saved_model: Path) -> None:
    if not saved_model.is_dir():
        raise SystemExit(f"saved model directory not found: {saved_model}")


def _require_output(output: Path) -> None:
    if output.suffix.lower() != ".tflite":
        raise SystemExit(f"{output}: output must end in .tflite")
    if not output.parent.is_dir():
        raise SystemExit(f"{output}: output directory does not exist")


def _convert(saved_model: Path, output: Path, mode: str, model: str) -> None:
    try:
        import tensorflow as tf
    except ImportError as exc:
        raise SystemExit("TensorFlow is not installed; install it locally before conversion") from exc

    try:
        converter = tf.lite.TFLiteConverter.from_saved_model(str(saved_model))
        converter.optimizations = [tf.lite.Optimize.DEFAULT]
        if mode == "int8":
            converter.target_spec.supported_ops = [tf.lite.OpsSet.TFLITE_BUILTINS_INT8]
            converter.inference_input_type = tf.int8
            converter.inference_output_type = tf.int8
        else:
            converter.target_spec.supported_ops = [tf.lite.OpsSet.TFLITE_BUILTINS]
            converter.target_spec.supported_types = [tf.float16]
        artifact = converter.convert()
    except Exception as exc:
        raise SystemExit(f"TFLite conversion failed for '{model}': {exc}") from exc

    output.write_bytes(artifact)
    print(f"OK: converted operator-supplied '{model}' SavedModel to {output} ({mode})")
    print("Conversion status only: no accuracy or latency claims are made.")


def convert_audio(saved_model: Path, output: Path, mode: str) -> None:
    _convert(saved_model, output, mode, "audio")


def convert_motion(saved_model: Path, output: Path, mode: str) -> None:
    _convert(saved_model, output, mode, "motion")


def convert_vision(saved_model: Path, output: Path, mode: str) -> None:
    _convert(saved_model, output, mode, "vision")


def convert_fusion(saved_model: Path, output: Path, mode: str) -> None:
    _convert(saved_model, output, mode, "fusion")


def convert_personalization(saved_model: Path, output: Path, mode: str) -> None:
    _convert(saved_model, output, mode, "personalization")


CONVERTERS: dict[str, Callable[[Path, Path, str], None]] = {
    "audio": convert_audio,
    "motion": convert_motion,
    "vision": convert_vision,
    "fusion": convert_fusion,
    "personalization": convert_personalization,
}


def convert(model: str, saved_model: Path, output: Path, mode: str) -> None:
    if model not in CONVERTERS:
        raise SystemExit(f"unknown model: {model}; choose from {', '.join(MODELS)}")
    if mode not in MODES:
        raise SystemExit(f"unknown mode: {mode}; choose from {', '.join(MODES)}")
    _require_saved_model(saved_model)
    _require_output(output)
    CONVERTERS[model](saved_model, output, mode)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("model", choices=MODELS, help="model-specific conversion entry point")
    parser.add_argument("--saved-model", type=Path, required=True, help="operator-supplied SavedModel directory")
    parser.add_argument("--output", type=Path, required=True, help="output .tflite file")
    parser.add_argument("--mode", choices=MODES, default="int8")
    args = parser.parse_args()
    convert(args.model, args.saved_model, args.output, args.mode)


if __name__ == "__main__":
    main()
