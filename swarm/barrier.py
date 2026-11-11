#!/usr/bin/env python3
"""Barrier check for the Human BlackBox swarm.

The solo stage ends only when all eight `swarm/done/<role>.md` files exist.
When they do, this script runs the full test suite and writes the result to
`swarm/results/barrier.md`.

Usage:
    python swarm/barrier.py           # check + run suite when complete
    python swarm/barrier.py --check   # check only
"""

from __future__ import annotations

import argparse
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DONE_DIR = ROOT / "swarm" / "done"
RESULTS_DIR = ROOT / "swarm" / "results"

ROLES = [
    "audio-detector",
    "motion-detector",
    "vision-detector",
    "fusion-engine",
    "buffer-crypto",
    "alert-consent",
    "evidence-store",
    "integration-safety",
]


def status() -> list[tuple[str, bool, int]]:
    rows = []
    for role in ROLES:
        path = DONE_DIR / f"{role}.md"
        exists = path.is_file()
        size = path.stat().st_size if exists else 0
        rows.append((role, exists, size))
    return rows


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true", help="check only")
    args = parser.parse_args()

    rows = status()
    for role, exists, size in rows:
        mark = "DONE" if exists else "WAIT"
        print(f"[{mark}] {role:<20} {size:>6} bytes")

    present = sum(1 for _, exists, _ in rows if exists)
    print(f"\n{present}/{len(ROLES)} done files present")

    if present < len(ROLES):
        print("Barrier not reached. Roundtable must not start.")
        return 1

    print("Barrier reached: all eight done files exist.")
    if args.check:
        return 0

    RESULTS_DIR.mkdir(parents=True, exist_ok=True)
    started = datetime.now(timezone.utc).isoformat()
    proc = subprocess.run(
        [sys.executable, "-m", "pytest", "tests/", "-v"],
        cwd=ROOT,
        capture_output=True,
        text=True,
    )
    output = proc.stdout + proc.stderr
    report = RESULTS_DIR / "barrier.md"
    report.write_text(
        "# Barrier Test Run\n\n"
        f"- started: {started}\n"
        f"- command: `python -m pytest tests/ -v`\n"
        f"- exit code: {proc.returncode}\n\n"
        "```\n" + output.strip() + "\n```\n",
        encoding="utf-8",
    )
    print(output)
    print(f"\nWritten to {report.relative_to(ROOT)}")
    return proc.returncode


if __name__ == "__main__":
    raise SystemExit(main())
