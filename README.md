# Human BlackBox Edge AI Emergency Detection System

Replication of the edge AI emergency detection system according to the paper's architecture using the opencode (8B model) as a swarm of parallel subagents.

## Architecture Overview

The system consists of several modules:
- Edge AI Inference Engine
- Sensor Data Preprocessor
- Emergency Detector
- Alert Manager
- Config Manager
- Tests

## Assumptions

See `docs/ASSUMPTIONS.md` for documented assumptions.

## Usage

```bash
# Load configuration
python -c "from config import load_config; config = load_config()"

# Run tests
pytest tests/ -v
```
