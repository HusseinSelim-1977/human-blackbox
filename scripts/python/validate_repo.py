"""Offline field validation of the Human BlackBox v2 repository layout and docs.

Checks, without network access or a Gradle/Android toolchain:

* all 11 Gradle modules and their build files exist;
* required root Gradle configuration files exist;
* relative links in README.md, docs/*.md and scripts/README.md resolve;
* prose does not contain invented numeric result claims (TBD/targets/planned
  values, assumptions, literature citations and code samples are allowed).

This script reports document hygiene only. It does not build, train, benchmark
or measure anything, and it never claims that a metric was achieved.
"""

from __future__ import annotations

import argparse
import re
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable
from urllib.parse import unquote, urlparse

MODULES: tuple[str, ...] = (
    "core",
    "sensors",
    "ml-audio",
    "ml-motion",
    "ml-vision",
    "ml-fusion",
    "data",
    "cloud",
    "testing",
    "scripts",
    "app",
)

ROOT_GRADLE_FILES: tuple[str, ...] = (
    "settings.gradle.kts",
    "build.gradle.kts",
    "gradle.properties",
)

FENCE_RE = re.compile(r"(?ms)^[ \t]*```.*?^[ \t]*```[ \t]*$")
INLINE_CODE_RE = re.compile(r"`[^`\n]*`")
LINK_RE = re.compile(
    r"!?\[[^\]]*\]\(\s*(?:<([^>\s]+)>|([^)\s]+))(?:\s+[\"'][^\"']*[\"'])?\s*\)"
)
HEADING_RE = re.compile(r"^[ \t]*#{1,6}[ \t]+(?P<title>.+?)[ \t]*$")
METRIC_RE = re.compile(
    r"(?i)\b(?:accuracy|precision|recall|f1(?:[- ]score)?|roc[- ]?auc|pr[- ]?auc|auc|"
    r"map|p50|p95|p99|latency|throughput|false[- ]?(?:positive|alarm|negative)[- ]?rate|"
    r"false[- ]?alarms?[- ]?per[- ]?hour|missed[- ]?event[- ]?rate|"
    r"battery[- ]?drain|peak[- ]?rss|model[- ]?size)\b"
    r"(?P<gap>[^\n]{0,28}?)(?<=[\s:(=])(?P<number>\d+(?:\.\d+)?\s*%?)"
)

# Markers that make a number a target, rule, example, reference or test fixture
# rather than a claimed measured result. "measured"/"achieved" are deliberately
# absent: those words on a numeric claim are what this check is looking for.
ALLOWED_MARKERS: tuple[str, ...] = (
    "tbd",
    "unmeasured",
    "not measured",
    "no result",
    "not claim",
    "assumption",
    "target",
    "budget",
    "threshold",
    "gate",
    "example",
    "e.g.",
    "hypothetical",
    "planned",
    "placeholder",
    "todo",
    "arxiv",
    "paper",
    "reference",
    "requirement",
    "acceptance",
    "criterion",
    "criteria",
    "operator-agreed",
    "test_",
    "assert",
    "expected",
    "at most",
    "no downloads",
)
COMPARISON_RE = re.compile(r"(?:<=|>=|<|>|≤|≥|~|≈)\s*$")
EXTERNAL_SCHEMES = ("http", "https", "mailto", "tel", "data", "ftp", "file")


@dataclass(frozen=True)
class Issue:
    kind: str
    message: str


def _repo_root(script: Path) -> Path:
    """scripts/python/validate_repo.py -> repository root."""
    return script.resolve().parents[2]


def check_structure(root: Path) -> list[Issue]:
    issues: list[Issue] = []
    for name in ROOT_GRADLE_FILES:
        if not (root / name).is_file():
            issues.append(Issue("missing-gradle-file", f"missing root file: {name}"))
    for module in MODULES:
        module_dir = root / module
        if not module_dir.is_dir():
            issues.append(Issue("missing-module", f"missing module directory: {module}/"))
        elif not (module_dir / "build.gradle.kts").is_file():
            issues.append(Issue("missing-module-file", f"missing {module}/build.gradle.kts"))
    return issues


def markdown_files(root: Path) -> list[Path]:
    files = [root / "README.md"]
    files.extend(sorted((root / "docs").glob("*.md")))
    scripts_readme = root / "scripts" / "README.md"
    if scripts_readme.is_file():
        files.append(scripts_readme)
    return [path for path in files if path.is_file()]


def _prose_lines(path: Path) -> Iterable[tuple[int, str]]:
    """Yield (line number, text) outside fenced code blocks, with inline code removed."""
    text = FENCE_RE.sub(lambda match: "\n" * match.group(0).count("\n"), path.read_text(encoding="utf-8"))
    for number, raw in enumerate(text.splitlines(), start=1):
        yield number, INLINE_CODE_RE.sub("", raw)


def check_links(root: Path, files: list[Path]) -> tuple[int, list[Issue]]:
    issues: list[Issue] = []
    checked = 0
    for path in files:
        for number, line in _prose_lines(path):
            for match in LINK_RE.finditer(line):
                target = match.group(1) or match.group(2)
                parsed = urlparse(target)
                if parsed.scheme in EXTERNAL_SCHEMES or target.startswith("#"):
                    continue  # no network access and no file to resolve
                rel = unquote(parsed.path)
                if not rel:
                    continue
                resolved = (path.parent / rel).resolve() if not rel.startswith("/") else (root / rel.lstrip("/")).resolve()
                checked += 1
                if not resolved.exists():
                    try:
                        shown = resolved.relative_to(root).as_posix()
                    except ValueError:
                        shown = target
                    issues.append(
                        Issue("broken-link", f"{path.relative_to(root).as_posix()}:{number}: {shown}")
                    )
    return checked, issues


def check_metric_claims(root: Path, files: list[Path]) -> tuple[int, list[Issue]]:
    issues: list[Issue] = []
    checked = 0
    for path in files:
        heading = ""
        for number, line in _prose_lines(path):
            heading_match = HEADING_RE.match(line)
            if heading_match:
                heading = heading_match.group("title").lower()
                continue
            lower = line.lower()
            if any(marker in lower or marker in heading for marker in ALLOWED_MARKERS):
                continue
            for match in METRIC_RE.finditer(line):
                checked += 1
                if COMPARISON_RE.search(match.group("gap")):
                    continue  # a limit ("p95 <= 100 ms") is not a result
                issues.append(
                    Issue(
                        "numeric-result-claim",
                        f"{path.relative_to(root).as_posix()}:{number}: "
                        f"{match.group(0).strip()!r} needs TBD or provenance",
                    )
                )
    return checked, issues


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument(
        "--root",
        type=Path,
        default=None,
        help="repository root (default: derived from this script's location)",
    )
    args = parser.parse_args()
    root = args.root.resolve() if args.root else _repo_root(Path(__file__))

    issues = check_structure(root)
    files = markdown_files(root)
    link_count, link_issues = check_links(root, files)
    claim_count, claim_issues = check_metric_claims(root, files)
    issues.extend(link_issues)
    issues.extend(claim_issues)

    if issues:
        for issue in issues:
            print(f"FAIL [{issue.kind}] {issue.message}")
        print(
            f"FAIL: {len(issues)} problem(s); {len(MODULES)} modules checked, "
            f"{link_count} links checked, {claim_count} metric snippets checked."
        )
        return 1

    print(
        f"PASS: {len(MODULES)} module dirs, {len(ROOT_GRADLE_FILES)} root Gradle files, "
        f"{link_count} relative links and {claim_count} metric snippets checked; "
        "no invented numeric result claims."
    )
    print("No build, download, benchmark or device measurement was performed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
