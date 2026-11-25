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

```text
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

---

# Final Four-Lens Review (2026-11-25)

Scope: the shipped baseline at **159 tests passing**, 8/8 SHIP. The tiers in
`ROADMAP.md` are future designs, not part of this review.

## Product / PM

- **Strengths:** Clear user story (detect → 15 s cancellable countdown → alert),
  four safety gates visible in `safety_audit()`, and raw media kept on-device.
- **Strengths:** Scope is honest: three modalities, one fused decision, one
  evidence window; no feature sprawl.
- **Risk:** Detection quality is unproven on real users; heuristic fallbacks may
  not match the paper's false-alarm/negative targets.
- **Risk:** "Emergency detection" is a high-trust claim; deployment needs a
  measured pilot before marketing it as production-grade.
- **Verdict:** **GO for the thesis/demo scope; NO-GO for unsupervised field
  deployment** until model-backed accuracy and false-alarm rates are measured.

## Architect

- **Strengths:** Clean seams — detectors, fusion, buffer, alert, evidence and
  pipeline communicate through `ModalityScore` and constructor injection.
- **Strengths:** Fusion contract is explicit: normalized weights, confidence
  gate, two-modality rule, temporal coherence, event spans, ambient boost.
- **Strengths:** `modules/modality_types.py` is canonical; `modules/types.py` is
  a thin shim, so the rename did not break imports.
- **Risk:** `fusion_enhanced.py` duplicates the fusion concept and is unwired;
  it can confuse future maintainers.
- **Verdict:** **GO.** Architecture is coherent for the shipped scope.

## Developer

- **Strengths:** 159 deterministic tests, fast suite (~0.2 s), no heavy ML
  dependency required for CI.
- **Strengths:** Config-driven thresholds/weights; no magic numbers in modules.
- **Strengths:** Backwards-compatible types shim and documented assumptions.
- **Strength:** `tests/test_fusion_ambient_boost.py` provides dedicated ambient
  Z-score boost coverage (6 tests) inside CI.
- **Risk:** Legacy root scratch scripts are not part of CI; remove before commit.
- **Risk:** Crypto is a documented stand-in, not production AEAD.
- **Verdict:** **GO**, with production validation pending model-backed and
  hardware-in-the-loop checks.

## QA

- **Strengths:** Unit coverage spans every module plus e2e composition; safety
  paths (consent, indicator, countdown cancellation) are asserted, not assumed.
- **Strengths:** `pytest tests/ -q` reproducibly reports **159 passed**.
- **Risk:** No test exercises real model weights, real sensor noise, or
  long-running buffer/evidence behavior.
- **Risk:** No load/latency/RSS budget test against the 100 ms inference target.
- **Verdict:** **GO for release of the verified baseline; conditional for
  production** pending hardware-in-the-loop and model-backed validation.

## Synthesis

The four lenses converge on one central tension: the repository is demonstrably ready for a thesis demo, but not for unsupervised field deployment. PM and QA both require hardware-in-the-loop and model-backed validation before production use; Developer repeats the same gap; Architect accepts the shipped runtime but flags `fusion_enhanced.py` as unwired duplication. Because three or more lenses share the production-validation gap, unsupervised production deployment is a blocking issue while the thesis/demo baseline remains GO.

Secondary tensions:

- **Demo-readiness vs production hardening:** 159 deterministic tests and heuristic fallbacks keep CI fast, but real sensor noise, trained models, and long-running buffer/evidence behavior remain unvalidated.
- **Runtime clarity vs architectural duplication:** `fusion.py` is the shipped path while `fusion_enhanced.py` is unwired, risking maintainer confusion.
- **Privacy intent vs crypto stand-in:** evidence encryption is documented and tested, but it is not production AEAD.
- **CI confidence vs operational confidence:** consent, indicator, and countdown cancellation are asserted, but load/latency/RSS against the 100 ms inference target are not.

Resolution: **GO** for the documented thesis/demo baseline; **NO-GO for unsupervised production** until model-backed detection, hardware-in-the-loop testing, and performance budgets are validated.
