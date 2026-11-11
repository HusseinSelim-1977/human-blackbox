# Stage: 0-DONE

Single source of truth for the swarm. Updated by the coordinator only.

```yaml
stage: 0-DONE
spawned: none
agents: {}
next: spawn 8 seniors (Section 4 of docs/CONTRACTS.md)
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

## Solo stage roles

1. audio-detector
2. motion-detector
3. vision-detector
4. fusion-engine
5. buffer-crypto
6. alert-consent
7. evidence-store
8. integration-safety

## Progress log

- 2026-11-11: Stage 0 complete. Contracts, shared types, config, territory map and 8 task briefs written.
