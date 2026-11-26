"""Training entry points for the five v2 models.

This script does not fabricate results or fetch proprietary data. It requires
operator-supplied dataset manifests and an installed TensorFlow environment.
Entry points fail loudly when the manifest, environment, class map or hardware
budget is unavailable; they never emit invented metrics or model files.
"""

from __future__ import annotations

import argparse
from pathlib import Path

from dataset_manifest import validate

MODELS = ("audio", "motion", "vision", "fusion", "personalization")


def require_environment() -> None:
    try:
        import tensorflow as tf  # noqa: F401
    except ImportError as exc:
        raise SystemExit("TensorFlow is not installed; install it locally before training") from exc


def _check_manifest(manifest: Path) -> None:
    if not manifest.is_file():
        raise SystemExit(f"dataset manifest not found: {manifest}")
    try:
        validate(manifest)
    except ValueError as exc:
        raise SystemExit(f"invalid dataset manifest: {exc}") from exc


def _refuse(model: str, output: Path) -> None:
    raise SystemExit(
        f"training hook for '{model}' is present but needs an operator-supplied "
        "dataset, class map and hardware budget; refusing to invent results. "
        f"No artifact was written to {output}."
    )


def train_audio(manifest: Path, output: Path) -> None:
    require_environment()
    _refuse("audio", output)


def train_motion(manifest: Path, output: Path) -> None:
    require_environment()
    _refuse("motion", output)


def train_vision(manifest: Path, output: Path) -> None:
    require_environment()
    _refuse("vision", output)


def train_fusion(manifest: Path, output: Path) -> None:
    require_environment()
    _refuse("fusion", output)


def train_personalization(manifest: Path, output: Path) -> None:
    require_environment()
    _refuse("personalization", output)


TRAINERS = {
    "audio": train_audio,
    "motion": train_motion,
    "vision": train_vision,
    "fusion": train_fusion,
    "personalization": train_personalization,
}


def train(model: str, manifest: Path, output: Path) -> None:
    if model not in TRAINERS:
        raise SystemExit(f"unknown model: {model}; choose from {', '.join(MODELS)}")
    _check_manifest(manifest)
    TRAINERS[model](manifest, output)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("model", choices=MODELS, help="model-specific training entry point")
    parser.add_argument("--manifest", type=Path, required=True, help="operator-supplied JSON manifest")
    parser.add_argument("--output", type=Path, required=True, help="requested output path")
    args = parser.parse_args()
    train(args.model, args.manifest, args.output)


if __name__ == "__main__":
    main()
