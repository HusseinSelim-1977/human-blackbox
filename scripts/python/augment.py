"""Permissive, reproducible augmentations for v2 training data.

All randomness is seeded. Inputs are operator-supplied .npy arrays; nothing is
downloaded and no training metrics are implied by these transforms.
"""

from __future__ import annotations

import argparse
import math
from pathlib import Path
from typing import Any


def _numpy() -> Any:
    try:
        import numpy as np
    except ImportError as exc:
        raise RuntimeError("numpy is required for augmentation") from exc
    return np


def add_noise(pcm: Any, snr_db: float, seed: int = 0) -> Any:
    """Add seeded white noise at the requested SNR to mono PCM [samples]."""
    np = _numpy()
    if not math.isfinite(snr_db):
        raise ValueError("snr_db must be finite")
    signal = np.asarray(pcm, dtype=np.float32)
    if signal.ndim != 1 or signal.size == 0:
        raise ValueError("expected mono PCM shaped [samples]")
    signal_power = float(np.mean(np.square(signal))) + 1e-12
    noise_power = signal_power / (10 ** (snr_db / 10.0))
    rng = np.random.default_rng(seed)
    noise = rng.normal(0.0, math.sqrt(noise_power), size=signal.shape).astype(np.float32)
    return np.ascontiguousarray(signal + noise)


def time_mask(spectrogram: Any, max_steps: int, seed: int = 0) -> Any:
    """Zero a seeded contiguous time span of a [frames, features] spectrogram."""
    np = _numpy()
    if max_steps < 1:
        raise ValueError("max_steps must be positive")
    out = np.array(spectrogram, dtype=np.float32, copy=True)
    if out.ndim != 2 or out.shape[1] < 1:
        raise ValueError("expected spectrogram shaped [frames, features]")
    rng = np.random.default_rng(seed)
    start = int(rng.integers(0, out.shape[1]))
    width = int(rng.integers(1, min(max_steps, out.shape[1] - start) + 1))
    out[:, start : start + width] = 0.0
    return np.ascontiguousarray(out)


def imu_jitter(samples: Any, std: float = 0.02, seed: int = 0) -> Any:
    """Add seeded Gaussian jitter to IMU samples shaped [steps, >=6]."""
    np = _numpy()
    if not math.isfinite(std) or std < 0.0:
        raise ValueError("std must be finite and non-negative")
    data = np.asarray(samples, dtype=np.float32)
    if data.ndim != 2 or data.shape[1] < 6:
        raise ValueError("expected IMU samples shaped [steps, >=6]")
    rng = np.random.default_rng(seed)
    return np.ascontiguousarray(data + rng.normal(0.0, std, data.shape).astype(np.float32))


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    subparsers = parser.add_subparsers(dest="command", required=True)

    for name, description in (
        ("noise", "seeded SNR-controlled noise on mono PCM"),
        ("time-mask", "seeded time masking on a [frames, features] array"),
        ("imu-jitter", "seeded jitter on [steps, >=6] IMU samples"),
    ):
        command = subparsers.add_parser(name, help=description)
        command.add_argument("--input", type=Path, required=True, help="operator-supplied .npy")
        command.add_argument("--output", type=Path, required=True, help="output .npy file")
        command.add_argument("--seed", type=int, default=0, help="random seed (default: 0)")
    noise = subparsers.choices["noise"]
    noise.add_argument("--snr-db", type=float, default=20.0, help="target SNR in dB")
    masking = subparsers.choices["time-mask"]
    masking.add_argument("--max-steps", type=int, default=8, help="maximum masked frames")
    jitter = subparsers.choices["imu-jitter"]
    jitter.add_argument("--std", type=float, default=0.02, help="jitter standard deviation")

    args = parser.parse_args()
    try:
        np = _numpy()
        if args.input.suffix.lower() != ".npy":
            raise ValueError(f"{args.input}: augmentation input must be .npy")
        if args.output.suffix.lower() != ".npy" or not args.output.parent.is_dir():
            raise ValueError(f"{args.output}: output must be a .npy file in an existing directory")
        source = np.load(args.input, allow_pickle=False)
        if args.command == "noise":
            result = add_noise(source, args.snr_db, seed=args.seed)
        elif args.command == "time-mask":
            result = time_mask(source, args.max_steps, seed=args.seed)
        else:
            result = imu_jitter(source, std=args.std, seed=args.seed)
        np.save(args.output, result)
    except (RuntimeError, ValueError, OSError) as exc:
        raise SystemExit(f"augmentation failed: {exc}") from exc

    print(f"OK: {args.command} seed={args.seed} input={source.shape} output={result.shape}")
    print("Augmented features only: no labels, models or metrics were produced.")


if __name__ == "__main__":
    main()
