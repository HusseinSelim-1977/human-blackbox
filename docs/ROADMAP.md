# Enhancement Roadmap — Index

Status: post-thesis planning artifact (no tier is implemented in the shipped
baseline)
Scope: Human BlackBox (`human-blackbox/`), on-device multi-modal emergency
detection
Companion files (published names in `human-blackbox/docs/`):

- `TIER1-CONFORMAL-FUSION.md`
- `TIER2-TEMPORAL-EVENT-FUSION.md`
- `TIER3-PERSONALIZATION.md`
- `TIER4-DEPLOYMENT-PRIVACY.md`

Source-to-docs filename map:

- `00-roadmap-index.md` → `ROADMAP.md`
- `tier1-conformal-fusion-design.md` → `TIER1-CONFORMAL-FUSION.md`
- `tier2-temporal-event-fusion-design.md` → `TIER2-TEMPORAL-EVENT-FUSION.md`
- `tier3-personalization-design.md` → `TIER3-PERSONALIZATION.md`
- `tier4-deployment-privacy-design.md` → `TIER4-DEPLOYMENT-PRIVACY.md`

## 0. Baseline (what exists today)

Eight modules, 159 tests, all green; swarm state `COMPLETE / SHIP`, 8/8 SHIP.

- **Audio** — YAMNet hook + heuristic fallback. Works; fallback is silent.
- **Motion** — LSTM hook + hand-tuned heuristic. Works; per-instant only.
- **Vision** — YOLOv8n hook + heuristic fallback. Works; RGB privacy risk.
- **Fusion** — confidence-gated weights 0.40/0.35/0.25, threshold 0.55,
  temporal coherence, event spans and an optional ambient Z-score boost. No
  error guarantee, cost-blind.
- **Buffer** — XOR + HMAC stand-in, 60 s. Not AEAD; documented as stand-in.
- **Alert** — 15 s cancellable countdown. Solid.
- **Consent** — scope-based. Solid; needs a `federation` scope.
- **Evidence** — at-rest store. Solid.

Every weakness below is a consequence of four gaps: **no calibration**, **no
temporal event**, **no personalization**, **no deployment budget**.

## 1. Tier summary

None of the tiers below is implemented in the shipped 159-test baseline. They
are post-thesis proposals, retained as design records.

- **Tier 1 — Rigor.** Conformal threshold + cost-sensitive policy + mode-aware
  confidence. Risk if skipped: claims stay anecdotal; false-alarm rate
  unbounded. Effort: ~2–3 days.
- **Tier 2 — Temporal signal.** Gated CNN + event-level fusion. Risk if
  skipped: brief impact signatures missed; falls scored as three instants.
  Effort: ~1–2 weeks including training.
- **Tier 3 — Personalization.** Adaptive head + prototype replay + optional
  federated client. Risk if skipped: systematic per-user bias; calibration
  drift. Effort: ~1 week.
- **Tier 4 — Deployment.** Real AEAD + key store + thermal/mmWave + budgets.
  Risk if skipped: privacy promise unbacked; "on-device" unmeasured.
  Effort: ~1 week.

## 2. Dependency graph

```text
Tier 1 (calibration, costs, mode penalty)
   │
   ├──► Tier 2 (temporal models, event fusion)
   │        │
   │        └──► Tier 3 (adapt heads + thresholds on device)
   │
   └──► Tier 4 (deployment; independent of 2/3 for crypto, better with 2)
```

Tier 1 is a prerequisite because Tiers 2–4 are all evaluated against a
**false-alarm budget**, which only Tier 1 defines. Tier 4's crypto work is
independent and can ship in parallel.

## 3. Sequencing recommendation

**Phase A — credibility (Tier 1 only).**
Ship `modules/calibration.py`, mode penalty, config block with
`enabled: false`, and the regression that disabled calibration is
byte-identical. This is the single highest value-per-line change: it converts
"we chose 0.65" into "≤5% false alarms at 95% coverage."

**Phase B — signal quality (Tier 2, motion first).**
Train the gated CNN on motion only (FallAllD/SisFall/KFall, subject-wise
splits), export `.npz`, enable event fusion for motion. Audio and vision
temporal models follow only if Phase B validates.

**Phase C — honesty at the edges (Tier 4 crypto + budget).**
Swap in AES-GCM behind a provider interface and add `BudgetMonitor` to the e2e
test. Publish measured latency/RSS. This is low-risk and closes the two biggest
gaps between the README and the code.

**Phase D — personalization (Tier 3).**
Only after Tier 1 calibration is live and has produced `coverage_estimate()`
history. Adaptation without a calibration signal to watch is unverifiable.

**Phase E — privacy-by-sensor (Tier 4 thermal/mmWave).**
Optional, and the most defensible answer to "you have a camera in a bedroom."

## 4. What changes in the repository, by tier

**Tier 1**

- New: `modules/calibration.py`, `tests/test_calibration.py`
- Modified: `modules/fusion.py`, `modules/pipeline.py`,
  `config/default.yaml`, `docs/ASSUMPTIONS.md`

**Tier 2**

- New: `modules/temporal.py`, `tests/test_temporal.py`, `training/`
- Modified: `modules/motion_detector.py`, `modules/fusion.py`,
  `modules/pipeline.py`, `config/default.yaml`

**Tier 3**

- New: `modules/adaptation.py`, `modules/replay.py`,
  `federation/client.py`, `tests/test_adaptation.py`
- Modified: `modules/pipeline.py`, `modules/user_consent.py`,
  `config/default.yaml`

**Tier 4**

- New: `modules/key_store.py`, `modules/thermal_detector.py`,
  `modules/mmwave_detector.py`, `modules/budget.py`, `tests/test_crypto.py`,
  `tests/test_budget.py`
- Modified: `modules/rolling_buffer.py`, `config/default.yaml`,
  `docs/ASSUMPTIONS.md`, `README.md`

## 5. Non-negotiable invariants across all tiers

1. **Legacy default is byte-identical.** Every tier ships behind an `enabled:
   false` flag; existing tests must pass unchanged.
2. **Hard safety floors win.** Freefall, impact and distress-scream floors are
   applied *after* any learned/personalized layer. Nothing may veto them.
3. **No silent degradation.** Heuristic mode, standin crypto and disabled
   calibration must be visible in the decision's `raw_features` / logs, not
   inferred.
4. **Every claim is measured.** False-alarm rate, latency, RSS and artifact
   size are recorded numbers in `docs/ASSUMPTIONS.md`, with the command that
   produced them.
5. **Nothing leaves the device by default.** Federation, telemetry and cloud
   are opt-in and consent-scoped.

## 6. Evaluation protocol (applies to all tiers)

Report these, not accuracy:

- **Recall at a fixed false-alarm budget** (e.g. 1 per 24 h) — the metric that
  matters for a safety system.
- **PR-AUC and macro-F1** on subject-wise splits.
- **Cross-dataset transfer** (train FallAllD → test FARSEEING) to expose
  lab-to-real degradation.
- **Time-to-detect** after impact, and **time-to-cancel** for the alert path.
- **Calibration diagnostics**: `coverage_estimate()`, reliability curve,
  expected calibration error.
- **Resource numbers**: p50/p95 latency, peak RSS, flash, sustained CPU.

## 7. Paper anchors by tier

**Tier 1** — 2609.33866, 2610.02357, 2608.10553 (conformal); 2509.11789
(cost-sensitive); 2604.14154 (edge fusion with confidence propagation).

**Tier 2** — 2605.20275 (gated CNN, Pixel Watch 3); 2610.18260 (FALLNEST
condensation); 2608.13197 (real-world scarcity).

**Tier 3** — 2609.04552 (CFAMs); 2610.27942 (APRIL); 2510.20960 (EPFL
federated); 2507.10474 (SF2D); 2610.28081 (PULSE).

**Tier 4** — 2509.16479 (thermal); 2606.11109 (mmWave); 2611.00671 (edge
compression survey).

## 8. Suggested thesis framing

The defensible contribution is not "we built a detector" — that is
well-trodden. It is:

> A **calibration-first** emergency detection architecture that (a) provides a
> distribution-free bound on false alarms, (b) treats detection as an event
> rather than an instant, and (c) personalizes without removing raw data from
> the device — with each claim measured on real public datasets and on-device
> budgets.

That framing survives a skeptical examiner because every clause maps to a
number in `docs/ASSUMPTIONS.md`.
