"""Summarise latency samples that were already measured elsewhere.

SUPPLIED-DATA-ONLY: the script never runs the app, a device, a model or a
benchmark suite. It reads JSON/CSV numbers in milliseconds that the operator
already measured, then reports descriptive statistics and a PASS/FAIL against
an operator-supplied threshold. Every output is labelled as a supplied
measurement, never as a result measured by this script.

Accepted input shapes:
  JSON  a list of millisecond numbers, {"latency_ms": 12.3}, or an object
        with "values" / "samples" / "measurements" / "latencies" / "results".
  CSV   one numeric column, or a header row containing "latency_ms" (or
        another scalar/list key); --column selects an exact header name.

Criteria: --criterion chooses mean, p95 or max. p95 is nearest-rank and
matches the Kotlin helper in testing/. PASS means selected statistic <=
--threshold-ms. The verdict describes only the supplied samples: it does not
establish an app, device or model result. Attach --source-label and record
date, hardware/OS, dataset manifest hash and model checksum; otherwise label
any reported value TBD / unmeasured. No network access or download occurs.
"""

from __future__ import annotations

import argparse
import csv
import json
import math
from pathlib import Path
from typing import Any

SCALAR_KEYS = ("latency_ms", "elapsed_ms", "duration_ms", "ms")
LIST_KEYS = ("values", "samples", "measurements", "latencies", "results")
DISCLAIMER = (
    "SUPPLIED MEASUREMENTS only; not app measurements and not collected by this script."
)


def _is_number(value: Any) -> bool:
    return isinstance(value, (int, float)) and not isinstance(value, bool)


def _values_from_json(payload: Any) -> list[float]:
    if isinstance(payload, list):
        if all(_is_number(item) for item in payload):
            return [float(item) for item in payload]
        values: list[float] = []
        for item in payload:
            values.extend(_values_from_json(item))
        return values
    if isinstance(payload, dict):
        for key in SCALAR_KEYS:
            if key in payload and _is_number(payload[key]):
                return [float(payload[key])]
        for key in LIST_KEYS:
            if key in payload:
                return _values_from_json(payload[key])
    raise ValueError(
        "JSON must contain latency numbers in milliseconds: a list of numbers, "
        "a list of objects with a latency_ms key, or one of "
        + ", ".join(LIST_KEYS)
    )


def _values_from_csv(path: Path, column: str | None) -> list[float]:
    with path.open("r", encoding="utf-8", newline="") as handle:
        rows = [row for row in csv.reader(handle) if row and not row[0].strip().startswith("#")]
    if not rows:
        raise ValueError(f"{path}: CSV has no data rows")

    header = rows[0] if any(not _is_numeric_cell(cell) for cell in rows[0]) else None
    if header is not None:
        candidates = [column] if column else list(SCALAR_KEYS)
        index = next(
            (header.index(name) for name in candidates if name and name in header), None
        )
        if index is None:
            raise ValueError(
                f"{path}: no latency column found; pass --column with the exact header "
                f"or use one of {', '.join(SCALAR_KEYS)}"
            )
        data_rows = rows[1:]
    else:
        if column:
            raise ValueError(f"{path}: --column requires a header row")
        index = 0
        data_rows = rows

    values: list[float] = []
    for row_number, row in enumerate(data_rows, start=2 if header else 1):
        if index >= len(row):
            raise ValueError(f"{path}:{row_number}: missing value in selected column")
        try:
            values.append(float(row[index]))
        except ValueError as exc:
            raise ValueError(f"{path}:{row_number}: not a number: {row[index]!r}") from exc
    return values


def _is_numeric_cell(cell: str) -> bool:
    try:
        float(cell)
    except ValueError:
        return False
    return True


def summarize(values: list[float], threshold_ms: float, criterion: str) -> dict[str, Any]:
    if not values:
        raise ValueError("no latency samples were supplied")
    if any(not math.isfinite(value) or value < 0.0 for value in values):
        raise ValueError("latency samples must be finite non-negative milliseconds")
    if not math.isfinite(threshold_ms) or threshold_ms < 0.0:
        raise ValueError("--threshold-ms must be a finite non-negative number")

    ordered = sorted(values)
    stats = {
        "mean": math.fsum(values) / len(values),
        # Nearest-rank p95, matching the Kotlin helper in testing/.
        "p95": ordered[max(0, math.ceil(0.95 * len(ordered)) - 1)],
        "max": ordered[-1],
    }
    selected = stats[criterion]
    passed = selected <= threshold_ms
    return {
        "samples": len(values),
        "mean_ms": stats["mean"],
        "p95_ms": stats["p95"],
        "max_ms": stats["max"],
        "threshold_ms": threshold_ms,
        "criterion": criterion,
        "budget_pass": passed,
        "verdict": "PASS" if passed else "FAIL",
        "source_kind": "supplied measurements",
        "disclaimer": DISCLAIMER,
    }


def main() -> None:
    parser = argparse.ArgumentParser(
        description=__doc__.splitlines()[0],
        epilog="\n".join(__doc__.splitlines()[1:]),
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument("--input", type=Path, required=True, help="JSON/CSV file of operator-measured latency samples in ms; none are generated")
    parser.add_argument("--threshold-ms", type=float, required=True, help="operator-supplied latency budget in ms; required for PASS/FAIL, no default")
    parser.add_argument("--column", help="exact CSV header holding latency values (auto-detects latency_ms/values/samples/...)")
    parser.add_argument("--criterion", choices=("p95", "mean", "max"), default="p95", help="statistic compared with the budget; p95 is nearest-rank (default: p95)")
    parser.add_argument("--source-label", help="label for the supplied source (device/OS/run); echoed in the output")
    parser.add_argument("--format", choices=("text", "json"), default="text", help="human-readable summary or JSON record of supplied measurements")
    args = parser.parse_args()

    try:
        if not args.input.is_file():
            raise ValueError(f"input file not found: {args.input}")
        text = args.input.read_text(encoding="utf-8")
        if args.input.suffix.lower() == ".json":
            values = _values_from_json(json.loads(text))
        elif args.input.suffix.lower() == ".csv":
            values = _values_from_csv(args.input, args.column)
        else:
            raise ValueError("input must be a .json or .csv file")
        report = summarize(values, args.threshold_ms, args.criterion)
    except (ValueError, OSError, json.JSONDecodeError) as exc:
        raise SystemExit(f"benchmark summary failed: {exc}") from exc

    report["input_name"] = args.input.name
    report["source_label"] = args.source_label or "unlabelled supplied file"

    if args.format == "json":
        print(json.dumps(report, indent=2, sort_keys=True))
    else:
        print(f"SOURCE: {DISCLAIMER}")
        print(f"Input: {report['input_name']} ({report['source_label']})")
        print(f"Samples: {report['samples']}")
        print(
            "mean_ms={mean_ms:.3f} p95_ms={p95_ms:.3f} max_ms={max_ms:.3f}".format(**report)
        )
        print(
            f"Latency budget ({report['criterion']} <= {report['threshold_ms']:.3f} ms): "
            f"{report['verdict']}"
        )
        print("No app or device run was performed by this script.")


if __name__ == "__main__":
    main()
