// Verify enhanced fusion end-to-end
const path = require('path');

// Read and parse the Python modules
const fs = require('fs');

// Read fusion_enhanced.py
const fusionEnhanced = fs.readFileSync(
  '/home/MASTER-H/Documents/Bachelor Thesis/human-blackbox/modules/fusion_enhanced.py',
  'utf8'
);

// Read original fusion.py
const fusionOriginal = fs.readFileSync(
  '/home/MASTER-H/Documents/Bachelor Thesis/human-blackbox/modules/fusion.py',
  'utf8'
);

console.log('=== FUSION ENHANCED STRUCTURE ===');
console.log(fusionEnhanced);

console.log('\n\n=== ORIGINAL FUSION STRUCTURE ===');
console.log(fusionOriginal);

// Read config
const config = fs.readFileSync(
  '/home/MASTER-H/Documents/Bachelor Thesis/human-blackbox/config/default.yaml',
  'utf8'
);

console.log('\n\n=== CONFIG DEFAULT ===');
console.log(config);

// Read types
const types = fs.readFileSync(
  '/home/MASTER-H/Documents/Bachelor Thesis/human-blackbox/modules/types.py',
  'utf8'
);

console.log('\n\n=== TYPES (ModalityScore) ===');
console.log(types);
