# Roundtable Reviewer Brief (all eight roles)

Read the merged tree first: every `modules/*.py`, every `tests/*.py`,
`swarm/results/barrier.md`, `docs/CONTRACTS.md`, `docs/ROUNDTABLE.md`.

## Round 1 — Judge (write `swarm/reviews/<role>-judge.md`)

```
role: <your role>
round: 1 judge
modules reviewed: <all>
scores: clarity <1-5> | correctness <1-5> | contract <1-5> | safety <1-5>
verdicts:
  modules/audio_detector.py: PASS|CONCERN|FAIL — <reason>
  ... (one line per module)
issues found:
  - <id>: <file>:<line> <problem> -> <fix>
```

Judge the code, not your own ego. Score every module including your own.

## Round 2 — Consolidate (coordinator)

`swarm/reviews/consolidated.md` merges all Round 1 issues into
`swarm/issues/ISSUE-<nn>.md` with severity, owner and acceptance check.

## Round 3 — Jointly modify (owner fixes own territory only)

For each issue assigned to you: fix it in your own files, add/adjust a test, and
update the issue file with `status: fixed`, what changed, and the real pytest
output. Never edit another role's files; propose instead.

## Round 4 — Re-judge (write `swarm/reviews/<role>-rejudge.md`)

```
role: <your role>
round: 4 re-judge
blockers remaining: none | <ids>
scores: clarity <1-5> | correctness <1-5> | contract <1-5> | safety <1-5>
verdict: PASS|FAIL
```

## Sign-off (write `swarm/votes/<role>-signoff.md`)

```
decision: SHIP | REJECT
reason: <one paragraph>
evidence: python -m pytest tests/ -q -> <real result>
timestamp: <ISO 8601 UTC>
```

Sign-off needs >= 6/8 SHIP. A REJECT must name the blocking issue id.

## Non-negotiables to check explicitly

1. recording indicator visible while capturing;
2. explicit, revocable mic/camera consent (deny by default, fail closed);
3. 15 s countdown, cancellable at any tick;
4. raw media never leaves the device (hashes/metadata only).
