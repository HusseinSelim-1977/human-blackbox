import yaml

# Load the config
with open('/home/MASTER-H/Documents/Bachelor Thesis/human-blackbox/config/default.yaml', 'r') as f:
    config = yaml.safe_load(f)

print("=== CONFIG VALIDATION REPORT ===")
print()

# 1. Check top-level structure
print("1. TOP-LEVEL STRUCTURE:")
print("   - Has 'fusion' section:", 'fusion' in config)
print("   - Has 'detectors' section:", 'detectors' in config)
print("   - Has 'sensor_types' (nested):", 'sensor_types' in config if 'detectors' in config else 'N/A')
print()

# 2. Verify detector structure parity
print("2. DETECTOR SECTION PARITY:")
detectors = config['detectors']
for modality in ['audio', 'motion', 'vision']:
    if modality in detectors:
        print("   -", modality, ":", len(detectors[modality]), "keys")
        print("      Keys:", list(detectors[modality].keys()))
    else:
        print("   -", modality, ":", "MISSING!")
print()

# 3. Check emergency_classes structure
print("3. EMERGENCY_CLASSES STRUCTURE:")
for modality in ['audio', 'motion', 'vision']:
    if modality in detectors and 'emergency_classes' in detectors[modality]:
        classes = detectors[modality]['emergency_classes']
        if isinstance(classes, list):
            print("   -", modality, ":", len(classes), "items (list)")
            for i, c in enumerate(classes[:5]):
                print("      [", i, "]:", repr(c), "(type:", type(c).__name__, ")")
        else:
            print("   -", modality, ":", len(classes), "items (nested mapping)")
    elif modality in detectors:
        print("   -", modality, ":", "No emergency_classes (nested mapping)")
print()

# 4. Validate fusion weights sum to ~1.0
print("4. FUSION WEIGHTS (should sum to ~1.0):")
fusion = config['fusion']
weights = fusion['weights']
total = sum(weights.values())
print("   - Weights:", weights)
print("   - Total:", total)
print("   - Within 0.05 tolerance:", abs(total - 1.0) < 0.05)
print()

# 5. Check for any nested mappings (sensor_types, detectors at top level)
print("5. NESTED MAPPING STRUCTURE:")
print("   - Top-level 'sensor_types' exists:", 'sensor_types' in config)
if 'sensor_types' in config:
    st = config['sensor_types']
    print("      Type:", type(st).__name__)
    if isinstance(st, dict):
        print("      Value types:", [type(v).__name__ for v in st.values()])
print()

# 6. Check 'detectors' at top level
print("6. TOP-LEVEL 'detectors' STRUCTURE:")
print("   - Type:", type(config['detectors']).__name__)
if isinstance(config['detectors'], dict):
    print("      Value types:", [type(v).__name__ for v in config['detectors'].values()])
print()

# 7. Validate specific values
print("7. KEY VALUE VALIDATION:")
print("   - Audio sample_rate_hz:", detectors['audio'].get('sample_rate_hz', 'MISSING'))
print("   - Motion sequence_length:", detectors['motion'].get('sequence_length', 'MISSING'))
print("   - Vision frame dimensions:", detectors['vision'].get('frame_width', 'MISSING'), "x", detectors['vision'].get('frame_height', 'MISSING'))
print()

# 8. Test Python iteration over sensor_types
print("8. PYTHON ITERATION TEST (sensor_types):")
if 'sensor_types' in config:
    sensor_types = config['sensor_types']
    if isinstance(sensor_types, dict):
        print("   - Iterating over", len(sensor_types), "modality keys:")
        for key in sensor_types.keys():
            print("      -", repr(key), ":", type(sensor_types[key]).__name__)
print()

print("=== END VALIDATION REPORT ===")
