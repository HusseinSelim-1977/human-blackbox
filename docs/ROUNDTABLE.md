# Roundtable Protocol

The roundtable opens **only** when `swarm/barrier.py` reports 8/8 done files and
the full suite has been recorded in `swarm/results/barrier.md`.

## Rounds

### Round 1 — Judge
Each senior reviews the consolidated solo work (all eight modules + tests) and
writes `swarm/reviews/<role>-judge.md`:
- verdict per module: PASS / CONCERN / FAIL
- score 1-5 for clarity, correctness, contract compliance, safety
- concrete issues, each with file + line + proposed fix

### Round 2 — Consolidate
The coordinator merges Round 1 reviews into `swarm/reviews/consolidated.md`,
deduplicates issues and files each one as `swarm/issues/ISSUE-<nn>.md` with
severity (blocker / major / minor), owner role, and acceptance check.

### Round 3 — Jointly modify
Owners fix their own issues in their own territories. Cross-territory fixes are
proposed in the issue file and applied only by the owning role. Every fix must
add or update a test. Record what changed in the issue file (`status: fixed`,
commit/commit-less diff summary, test command + output).

### Round 4 — Re-judge
Every senior re-reviews the fixed tree and writes
`swarm/reviews/<role>-rejudge.md` with a 1-5 score and PASS/FAIL. Any remaining
blocker blocks sign-off.

## Sign-off

Each senior writes `swarm/votes/<role>-signoff.md`:

```
decision: SHIP | REJECT
reason: <one paragraph>
evidence: <tests run + result>
timestamp: <ISO 8601 UTC>
```

Sign-off requires **>= 6 of 8 SHIP**. A REJECT must name the blocking issue id.

## Outside jury

After sign-off, an independent reviewer (not one of the eight) scores the
deliverables against the paper's claims: detection coverage, latency budget,
encryption of the rolling buffer, cancellable countdown, consent + indicator,
on-device raw media. Result goes to `swarm/results/jury.md`.

## Rules

- No senior edits another senior's files, even during the roundtable.
- Every claim needs a command and its real output.
- Safety non-negotiables are never traded for detection scores.
