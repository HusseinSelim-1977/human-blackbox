import { readFileSync } from 'fs';

const projectPath = '/home/MASTER-H/Documents/Bachelor Thesis/human-blackbox';

// Read the Python modules
const fusionEnhanced = readFileSync(
    projectPath + '/modules/fusion_enhanced.py',
    'utf-8'
);

console.log('=== Fusion Strategy Verification Test ===\n');

// Parse and display the fusion modes from the file
const classNames = fusionEnhanced.match(/class (\w+)/g).map(m => m.replace('class ', ''));
console.log('Fusion classes found:', classNames.join(', '));

// Check for key attributes in raw_features
const rawFeatures = fusionEnhanced.match(/raw_features=\{[\s\S]*?\}/);
if (rawFeatures) {
    console.log('\nSample raw_features structure:');
    console.log(rawFeatures[0]);
}

// Check what modes are available
const modeMatches = fusionEnhanced.match(/fusion_mode:\s*"[^"]+"/g);
const modes = modeMatches ? [...new Set(modeMatches.map(m => m.replace(/fusion_mode:\s*"/, '').replace('"', '')))] : [];
console.log('\nFusion modes in output:', modes.join(', '));

// Check the default weights
const weightMatch = fusionEnhanced.match(/DEFAULT_WEIGHTS = \{[\s\S]*?\}/);
if (weightMatch) {
    console.log('\nDefault weights:');
    console.log(weightMatch[0]);
}

// Check the default thresholds
const thresholdMatch = fusionEnhanced.match(/DEFAULT_THRESHOLDS = \{[\s\S]*?\}/);
if (thresholdMatch) {
    console.log('\nDefault thresholds:');
    console.log(thresholdMatch[0]);
}

console.log('\n=== Verification Complete ===');
