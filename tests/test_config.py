import pytest
from config import load_config

def test_load_config():
    """Test that config loads successfully."""
    config = load_config()
    # Check that required keys exist
    assert "model_version" in config
    assert "sensor_types" in config
    assert "emergency_thresholds" in config
    assert "assumptions" in config
    # Check some values are reasonable
    assert config["model_version"] == "1.0.0"
    assert isinstance(config["assumptions"], list)
    assert len(config["assumptions"]) > 0
