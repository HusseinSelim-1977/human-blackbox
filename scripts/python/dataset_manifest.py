"""Validate dataset provenance and license metadata without downloading anything.

The CLI prints only a safe summary: the accepted license identifier and the
presence of provenance/splits. Sample identifiers, paths and URLs are never
printed so that a manifest cannot leak operator-local details through logs.
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any

# Deliberately small: an unrecognised licence is rejected rather than guessed.
ALLOWED_LICENSES = frozenset(
    {
        "cc0-1.0",
        "cc-by-4.0",
        "cc-by-sa-4.0",
        "mit",
        "apache-2.0",
    }
)
REQUIRED_SPLITS = ("train", "val", "test")


def validate(path: Path) -> dict[str, Any]:
    """Return a validated manifest, or raise ValueError with a clear reason."""
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except OSError as exc:
        raise ValueError(f"{path}: cannot read manifest: {exc}") from exc
    except json.JSONDecodeError as exc:
        raise ValueError(f"{path}: invalid JSON: {exc}") from exc
    if not isinstance(data, dict):
        raise ValueError(f"{path}: manifest must be a JSON object")

    license_id = data.get("license")
    if not isinstance(license_id, str) or not license_id.strip():
        raise ValueError(f"{path}: missing or empty required key 'license'")
    normalized = license_id.strip().lower()
    if normalized not in ALLOWED_LICENSES:
        raise ValueError(
            f"{path}: unknown license '{license_id}'; allowed: "
            + ", ".join(sorted(ALLOWED_LICENSES))
        )

    provenance = data.get("provenance")
    if isinstance(provenance, str):
        if not provenance.strip():
            raise ValueError(f"{path}: required key 'provenance' is empty")
    elif isinstance(provenance, dict):
        if not any(isinstance(value, str) and value.strip() for value in provenance.values()):
            raise ValueError(f"{path}: 'provenance' needs at least one non-empty field")
    else:
        raise ValueError(f"{path}: missing or invalid required key 'provenance'")

    splits = data.get("splits")
    if not isinstance(splits, dict):
        raise ValueError(f"{path}: missing or invalid required key 'splits'")
    for name in REQUIRED_SPLITS:
        if name not in splits:
            raise ValueError(f"{path}: missing split '{name}'")
    return data


def _split_size(value: Any) -> str:
    """Describe a split without printing identifiers or paths."""
    if isinstance(value, (list, tuple)):
        return str(len(value))
    if isinstance(value, dict):
        for key in ("samples", "count", "n"):
            count = value.get(key)
            if isinstance(count, int) and count >= 0:
                return str(count)
    return "not declared"


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("manifest", type=Path, help="operator-supplied JSON manifest")
    args = parser.parse_args()

    try:
        data = validate(args.manifest)
    except ValueError as exc:
        raise SystemExit(f"INVALID: {exc}") from exc

    license_id = data["license"].strip().lower()
    sizes = ", ".join(
        f"{name}={_split_size(data['splits'][name])}" for name in REQUIRED_SPLITS
    )
    print(f"OK: license={license_id} provenance=present splits={sizes}")
    print("Safe summary only: no paths, identifiers or URLs are printed.")


if __name__ == "__main__":
    main()
