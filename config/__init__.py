import yaml
from typing import Any, Dict
import os

def load_config(config_path: str = "default.yaml") -> Dict[str, Any]:
    """
    Load configuration from YAML file.
    
    Args:
        config_path: Path to the config file relative to this module
    
    Returns:
        Dictionary containing configuration values
    """
    config_dir = os.path.dirname(__file__)
    config_file = os.path.join(config_dir, config_path)
    
    if not os.path.exists(config_file):
        raise FileNotFoundError(f"Config file not found: {config_file}")
    
    with open(config_file, 'r') as f:
        config = yaml.safe_load(f)
    
    # Ensure all values are valid types
    return config
