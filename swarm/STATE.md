# Stage: 1-SOLO (7/8 merged)

Single source of truth for the swarm. Updated by the coordinator only.

```yaml
stage: 1-SOLO
spawned: 8
agents:
  audio-detector: merged (32 tests)
  motion-detector: running
  vision-detector: merged (tests green)
  fusion-engine: merged (15 tests)
  buffer-crypto: merged (15 tests)
  alert-consent: merged (16 tests)
  evidence-store: merged (21 tests)
  integration-safety: merged (8 tests)
next: merge motion-detector, run barrier suite, open roundtable
barrier: pending
roundtable_round: 0
signoff_votes: 0/8
```

## Stages

| Stage | Meaning | Exit condition |
|-------|---------|----------------|
| 0-PREP | Contracts, config, territory map, task briefs | all prep files exist |
| 1-SOLO | 8 seniors work alone in their territories | all 8 `swarm/done/<role>.md` exist |
| 2-BARRIER | coordinator runs the full suite and posts results | suite result posted |
| 3-ROUNDTABLE | 4 rounds: judge, consolidate, jointly modify, re-judge | 4 rounds recorded |
| 4-SIGNOFF | SHIP/REJECT vote | >= 6/8 SHIP |
| 5-FINAL | deliverables + outside jury | deliverables complete |

## Progress log

- 2026-11-11: Stage 0 complete. Contracts, shared types, config, territory map and 8 task briefs written.
- 2026-11-11: Seven of eight solo branches merged; full suite green at 100 tests.
- 2026-11-11: Coordinator e2e smoke test added (`tests/test_integration_e2e.py`) to prove composition.
