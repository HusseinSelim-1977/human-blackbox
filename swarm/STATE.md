# Stage: COMPLETE / SHIP

Project complete and signed off. No further implementation work is queued.

## Current Status
- **Phase**: Final — shipped baseline verified
- **Tests**: `python3 -m pytest tests/ -q` → **159 passed**
- **Sign-off**: 8/8 SHIP (`swarm/votes/*-signoff.md`)
- **Barrier**: all 8 `swarm/done/*.md` present
- **Docs**: reconciled with the implementation on 2026-11-25

## Shipped Baseline

| Area | State |
|------|-------|
| Detectors | Audio (YAMNet hook + heuristic), motion (LSTM hook + heuristic), vision (YOLOv8n hook + heuristic) |
| Fusion | `modules/fusion.py`: confidence-gated weighted fusion, temporal coherence, event spans, optional ambient Z-score boost |
| Buffer | 60 s encrypted rolling buffer (XOR + HMAC stand-in, documented) |
| Alert | Consent-gated, 15 s cancellable countdown |
| Evidence | Encrypted per-session store |
| Types | `modules/modality_types.py` canonical; `modules/types.py` backwards-compatible shim |
| Pipeline | End-to-end wiring + `safety_audit()` |

### Final fusion details
- Config: weights AUDIO 0.40 / MOTION 0.35 / VISION 0.25, threshold 0.55,
  `min_confidence` 0.30, `require_two_modalities` true.
- `raw_features` keys: `per_modality`, `weights_used`, `weighted_score`,
  `threshold`, `modalities_count`, `agreement`, `event_start`, `event_end`,
  `duration_span`, `temporal_coherence`, plus `ambient_boosts` when active.
- Ambient Z-score boost is opt-in at runtime: it requires `ambient_mean` /
  `ambient_std` tables; `config/default.yaml` only declares tracked features.

## Progress Log

- 2026-11-11: Stage 0 complete. Contracts, shared types, config, territory map and 8 task briefs written.
- 2026-11-11: Seven of eight solo branches merged; suite green at 100 tests.
- 2026-11-11: Coordinator e2e smoke test added (`tests/test_integration_e2e.py`).
- 2026-11-12: Barrier reached; all 8 modules integrated and tests passing.
- 2026-11-12: Roundtable review completed; all modules passed.
- 2026-11-12: Sign-off votes collected: 8/8 SHIP.
- 2026-11-24: Barrier re-run; 150/150 tests passing.
- 2026-11-24: SHIP state finalized; core modules integrated and stable.
- 2026-11-25: Fusion rewritten with confidence gating, temporal/event metrics and optional ambient Z-score boost.
- 2026-11-25: `modules/modality_types.py` made canonical; `modules/types.py` retained as re-export shim.
- 2026-11-25: Docs reconciled; final suite **159 passed**; state set to COMPLETE / SHIP.

## Remaining Risks

- **Heuristic fallbacks, not trained models.** Detection quality is unvalidated
  without YAMNet/LSTM/YOLOv8 weights and real sensor data.
- **Ambient boost lacks in-suite coverage.** `tests/` has no dedicated ambient
  Z-score test; root-level scratch scripts (`test_fusion_ambient.py`) are not
  collected and one fails at collection.
- **Crypto is a stand-in.** XOR + HMAC, not AES-256-GCM; documented as such.
- **False-positive/negative targets are assumptions**, not measured.
- **`fusion_enhanced.py` is unwired** experimental code; `fusion.py` is the only
  shipped path.
- **Tiers 1–4 remain future design proposals** and are not implemented.
