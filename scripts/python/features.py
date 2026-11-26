"""Feature extraction helpers for the five v2 models.

Requires numpy; log-mel extraction requires librosa. Dataset paths are supplied
by the operator. No assets are downloaded by this module. Tensor shapes are
explicit: audio is [frames, mel_bins] and IMU is [steps, 9] at 100 Hz.
"""

from __future__ import annotations

import argparse
import csv
from pathlib import Path
from typing import Any


def _numpy() -> Any:
    try:
        import numpy as np
    except ImportError as exc:
        raise RuntimeError("numpy is required for feature extraction") from exc
    return np


def log_mel_spectrogram(
    pcm: Any,
    sample_rate: int = 16_000,
    n_fft: int = 1024,
    hop_length: int = 512,
    mel_bins: int = 64,
) -> Any:
    """Return log-mel features shaped [frames, mel_bins] from mono PCM.

    librosa yields [mel_bins, frames]; this function transposes it to match the
    tensor layout expected by the audio model.
    """
    np = _numpy()
    try:
        import librosa
    except ImportError as exc:
        raise RuntimeError(
            "librosa is required for log-mel extraction; install it locally "
            "before running this script (no download is performed here)"
        ) from exc

    y = np.asarray(pcm, dtype=np.float32)
    if y.ndim != 1 or y.size == 0:
        raise ValueError("expected mono PCM shaped [samples] with at least one sample")
    if mel_bins < 1 or hop_length < 1 or n_fft < 1:
        raise ValueError("mel_bins, hop_length and n_fft must be positive")

    mel = librosa.feature.melspectrogram(
        y=y,
        sr=sample_rate,
        n_fft=n_fft,
        hop_length=hop_length,
        n_mels=mel_bins,
    )
    features = librosa.power_to_db(mel, ref=np.max).astype(np.float32).T
    if features.ndim != 2 or features.shape[1] != mel_bins:
        raise RuntimeError(f"unexpected log-mel shape: {features.shape}")
    return np.ascontiguousarray(features)


def imu_window(samples: Any, window_steps: int = 32, sample_rate_hz: int = 100) -> Any:
    """Return a gravity-compensated IMU window shaped [window_steps, 9] at 100 Hz.

    Columns: linear residual x/y/z (3), gyro x/y/z (3), |linear| (1),
    |gyro| (1), |jerk| (1). Short inputs are zero-padded on the left.
    """
    np = _numpy()
    if sample_rate_hz != 100:
        raise ValueError(
            f"IMU windowing is defined at 100 Hz; got {sample_rate_hz} Hz. "
            "Resample the supplied data before calling this function."
        )
    if window_steps < 1:
        raise ValueError("window_steps must be positive")

    data = np.asarray(samples, dtype=np.float32)
    if data.ndim != 2 or data.shape[1] < 6:
        raise ValueError("expected IMU samples shaped [steps, >=6] (linear3 + gyro3)")
    tail = data[-window_steps:]
    linear = tail[:, :3]
    gyro = tail[:, 3:6]
    gravity = linear.mean(axis=0)
    residual = linear - gravity
    jerk = np.zeros_like(linear)
    if linear.shape[0] > 1:
        jerk[1:] = np.diff(linear, axis=0)
    features = np.concatenate(
        [
            residual,
            gyro,
            np.linalg.norm(linear, axis=1, keepdims=True),
            np.linalg.norm(gyro, axis=1, keepdims=True),
            np.linalg.norm(jerk, axis=1, keepdims=True),
        ],
        axis=1,
    )
    if features.shape[0] < window_steps:
        pad = np.zeros(
            (window_steps - features.shape[0], features.shape[1]), dtype=np.float32
        )
        features = np.concatenate([pad, features], axis=0)
    if features.shape != (window_steps, 9):
        raise RuntimeError(f"unexpected IMU window shape: {features.shape}")
    return np.ascontiguousarray(features, dtype=np.float32)


def _load_array(path: Path, np: Any) -> Any:
    """Load operator-supplied numeric .npy or .csv data; never fetches inputs."""
    suffix = path.suffix.lower()
    if suffix == ".npy":
        return np.load(path, allow_pickle=False)
    if suffix == ".csv":
        with path.open("r", encoding="utf-8", newline="") as handle:
            rows = [
                [float(cell) for cell in row]
                for row in csv.reader(handle)
                if row and not row[0].strip().startswith("#")
            ]
        if not rows:
            raise ValueError(f"{path}: no numeric rows found")
        width = len(rows[0])
        if any(len(row) != width for row in rows):
            raise ValueError(f"{path}: inconsistent CSV row widths")
        return np.asarray(rows, dtype=np.float32)
    raise ValueError(f"{path}: unsupported input format; use .npy or numeric .csv")


def _save_array(path: Path, array: Any, np: Any) -> None:
    if path.suffix.lower() != ".npy":
        raise ValueError(f"{path}: output must be a .npy file")
    if not path.parent.is_dir():
        raise ValueError(f"{path}: output directory does not exist")
    np.save(path, array)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    subparsers = parser.add_subparsers(dest="command", required=True)

    audio = subparsers.add_parser("audio", help="PCM [samples] -> log-mel [frames, mel_bins]")
    audio.add_argument("--input", type=Path, required=True, help="operator-supplied mono PCM .npy/.csv")
    audio.add_argument("--output", type=Path, required=True, help="output .npy file")
    audio.add_argument("--sample-rate", type=int, default=16_000)
    audio.add_argument("--n-fft", type=int, default=1024)
    audio.add_argument("--hop-length", type=int, default=512)
    audio.add_argument("--mel-bins", type=int, default=64)

    imu = subparsers.add_parser("imu", help="100 Hz IMU [steps, >=6] -> window [steps, 9]")
    imu.add_argument("--input", type=Path, required=True, help="operator-supplied IMU .npy/.csv")
    imu.add_argument("--output", type=Path, required=True, help="output .npy file")
    imu.add_argument("--steps", type=int, default=32)
    imu.add_argument("--sample-rate", type=int, default=100)

    args = parser.parse_args()
    try:
        np = _numpy()
        source = _load_array(args.input, np)
        if args.command == "audio":
            result = log_mel_spectrogram(
                source,
                sample_rate=args.sample_rate,
                n_fft=args.n_fft,
                hop_length=args.hop_length,
                mel_bins=args.mel_bins,
            )
            label = f"audio log-mel [frames, mel_bins]={result.shape}"
        else:
            result = imu_window(
                source, window_steps=args.steps, sample_rate_hz=args.sample_rate
            )
            label = f"IMU window [steps, 9]={result.shape} at 100 Hz"
        _save_array(args.output, result, np)
    except (RuntimeError, ValueError, OSError) as exc:
        raise SystemExit(f"feature extraction failed: {exc}") from exc

    print(f"OK: {label}")
    print(f"Input: {args.input.name} (operator-supplied; shape={source.shape})")
    print("Features only: no labels, models or metrics were produced.")


if __name__ == "__main__":
    main()
