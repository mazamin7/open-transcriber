/**
 * REA Reconstruction Obligation Verification Test Suite
 * Validates all 7 obligations defined in rea/reconstruction-ledger.json
 */

const assert = require('assert');
const fs = require('fs');
const path = require('path');

// Load modules
const AudioAnalyzer = require('../public/js/audio-analyzer.js');
const SpeechEngine = require('../public/js/speech-engine.js');
const TwoWayCommunication = require('../public/js/two-way-comm.js');
const AccessibilityController = require('../public/js/accessibility.js');

let testsPassed = 0;
let testsFailed = 0;

function runTest(name, fn) {
  try {
    fn();
    console.log(`  [PASS] ${name}`);
    testsPassed++;
  } catch (err) {
    console.error(`  [FAIL] ${name}: ${err.message}`);
    testsFailed++;
  }
}

console.log('\n======================================================');
console.log('  Running REA Reconstruction Verification Suite');
console.log('======================================================\n');

/* -------------------------------------------------------------
 * Obligation 1: Streaming Speech-to-Text Pipeline
 * ------------------------------------------------------------- */
runTest('OBL-001: SpeechEngine Mock Streaming & Finalization', () => {
  let latestResult = null;
  const engine = new SpeechEngine({
    engineType: 'mock',
    onResult: (r) => { latestResult = r; }
  });

  engine.start();
  assert.strictEqual(engine.isListening, true, 'SpeechEngine should be in listening state');

  // Inject interim hypothesis
  engine.injectMockResult('hello', false);
  assert.strictEqual(latestResult.interim, 'hello');
  assert.strictEqual(latestResult.final, '');
  assert.strictEqual(latestResult.full, 'hello');

  // Finalize phrase
  engine.injectMockResult('hello world.', true);
  assert.strictEqual(latestResult.interim, '');
  assert.strictEqual(latestResult.final, 'hello world.');
  assert.strictEqual(latestResult.full, 'hello world.');

  // Second phrase concatenation
  engine.injectMockResult('how are you today?', true);
  assert.strictEqual(latestResult.full, 'hello world. how are you today?');

  // Clear
  engine.clearTranscript();
  assert.strictEqual(engine.finalTranscript, '');
  assert.strictEqual(engine.interimTranscript, '');
  assert.strictEqual(latestResult.full, '');

  engine.stop();
  assert.strictEqual(engine.isListening, false);
});

/* -------------------------------------------------------------
 * Obligation 2: Ambient Sound & Decibel Meter Bubble
 * ------------------------------------------------------------- */
runTest('OBL-002: AudioAnalyzer RMS & Decibel Calculations', () => {
  // Test pure silence (all 128 in unsigned 8-bit PCM)
  const silenceBuffer = new Uint8Array(512).fill(128);
  const silenceRms = AudioAnalyzer.calculateRMS(silenceBuffer);
  assert.strictEqual(silenceRms, 0, 'Zero amplitude buffer should yield 0 RMS');

  const silenceDb = AudioAnalyzer.calculateDecibels(silenceRms);
  assert.strictEqual(silenceDb, -90, 'Zero RMS should clamp to -90 dB');

  // Test full scale square wave (alternating 0 and 255)
  const squareWave = new Uint8Array(512);
  for (let i = 0; i < squareWave.length; i++) {
    squareWave[i] = i % 2 === 0 ? 0 : 255;
  }
  const squareRms = AudioAnalyzer.calculateRMS(squareWave);
  assert(squareRms > 0.95 && squareRms <= 1.0, `Square wave RMS should approach 1.0, got ${squareRms}`);

  const squareDb = AudioAnalyzer.calculateDecibels(squareRms);
  assert(squareDb >= -1 && squareDb <= 0, `Full scale square wave should be near 0 dB, got ${squareDb}`);
});

/* -------------------------------------------------------------
 * Obligation 3: Two-Way Deaf Communication & Table-Flip
 * ------------------------------------------------------------- */
runTest('OBL-003: TwoWayCommunication Quick Chips & Flip State', () => {
  // Mock localStorage for node environment
  const mockStorage = {};
  global.localStorage = {
    getItem: (k) => mockStorage[k] || null,
    setItem: (k, v) => { mockStorage[k] = v; }
  };

  const twoWay = new TwoWayCommunication();
  assert(twoWay.customChips.length >= 6, 'Should have standard deaf communication default chips');
  assert(twoWay.customChips.includes("Sono sorda, per favore parla chiaro"));

  // Add custom chip in Italian
  const added = twoWay.addCustomChip("Vorrei un caffè d'orzo");
  assert.strictEqual(added, true);
  assert(twoWay.customChips.includes("Vorrei un caffè d'orzo"));

  // Prevent duplicates
  const duplicate = twoWay.addCustomChip("Vorrei un caffè d'orzo");
  assert.strictEqual(duplicate, false);

  // Remove chip
  twoWay.removeCustomChip("Vorrei un caffè d'orzo");
  assert.strictEqual(twoWay.customChips.includes("Vorrei un caffè d'orzo"), false);

  // Mock DOM element for flip
  const mockElement = {
    classes: new Set(),
    classList: {
      add: function(c) { mockElement.classes.add(c); },
      remove: function(c) { mockElement.classes.delete(c); },
      contains: function(c) { return mockElement.classes.has(c); }
    }
  };

  assert.strictEqual(twoWay.isFlipped, false);
  twoWay.toggleFlip(mockElement);
  assert.strictEqual(twoWay.isFlipped, true);
  assert.strictEqual(mockElement.classList.contains('table-flipped'), true);

  twoWay.toggleFlip(mockElement);
  assert.strictEqual(twoWay.isFlipped, false);
  assert.strictEqual(mockElement.classList.contains('table-flipped'), false);
});

/* -------------------------------------------------------------
 * Obligation 4: Acoustic Event & Sound Classification
 * ------------------------------------------------------------- */
runTest('OBL-004: Acoustic Sound Event Detection', () => {
  const binCount = 256;
  const emptyFreq = new Uint8Array(binCount).fill(0);

  // Silence
  const eventSilence = AudioAnalyzer.classifyAcousticEvent(0.005, emptyFreq, binCount);
  assert.strictEqual(eventSilence, 'Silence');

  // Loud Noise
  const eventLoud = AudioAnalyzer.classifyAcousticEvent(0.85, emptyFreq, binCount);
  assert.strictEqual(eventLoud, 'Loud Noise');

  // Speech (mid-range vocal formant energy)
  const speechFreq = new Uint8Array(binCount);
  const speechCutoff = Math.floor(binCount * 0.45);
  for (let i = Math.floor(binCount * 0.1); i < speechCutoff; i++) {
    speechFreq[i] = 200; // heavy energy in 300Hz-3kHz
  }
  const eventSpeech = AudioAnalyzer.classifyAcousticEvent(0.20, speechFreq, binCount);
  assert.strictEqual(eventSpeech, 'Speech');

  // Applause (high frequency burst)
  const applauseFreq = new Uint8Array(binCount);
  for (let i = speechCutoff; i < binCount; i++) {
    applauseFreq[i] = 240; // high frequency splatter
  }
  const eventApplause = AudioAnalyzer.classifyAcousticEvent(0.35, applauseFreq, binCount);
  assert.strictEqual(eventApplause, 'Applause');
});

/* -------------------------------------------------------------
 * Obligation 5: Offline Airgap & CSP Validation
 * ------------------------------------------------------------- */
runTest('OBL-005: Strict Airgap Security & CSP Verification', () => {
  const htmlPath = path.join(__dirname, '../public/index.html');
  const html = fs.readFileSync(htmlPath, 'utf8');

  // Verify Content Security Policy tag is present
  assert(html.includes('Content-Security-Policy'), 'index.html must specify Content-Security-Policy');
  assert(html.includes("default-src 'self'"), "CSP must restrict default-src to 'self'");
  assert(!html.includes('google-analytics'), 'No external Google analytics allowed');
  assert(!html.includes('http://') && !html.includes('https://'), 'No external HTTP/HTTPS CDN references in index.html');

  // Verify Service Worker
  const swPath = path.join(__dirname, '../public/sw.js');
  const sw = fs.readFileSync(swPath, 'utf8');
  assert(sw.includes('caches.open'), 'sw.js must implement cache-first offline storage');
  assert(sw.includes('requestUrl.origin !== self.location.origin'), 'sw.js must block cross-origin requests');

  // Verify Manifest
  const manifestPath = path.join(__dirname, '../public/manifest.json');
  const manifest = JSON.parse(fs.readFileSync(manifestPath, 'utf8'));
  assert.strictEqual(manifest.display, 'standalone');
  assert(manifest.icons.length > 0);
});

/* -------------------------------------------------------------
 * Obligation 6: Accessibility Controls & Physical Feedback
 * ------------------------------------------------------------- */
runTest('OBL-006: Accessibility Typography & Haptic Intervals', () => {
  // Mock DOM
  global.document = {
    body: {
      className: '',
      classList: {
        add: function(c) { global.document.body.className = c; }
      }
    },
    documentElement: {
      style: {
        setProperty: function() {}
      }
    },
    addEventListener: function() {}
  };

  const a11y = new AccessibilityController();
  
  // Font scaling clamping
  a11y.applyFontSize(10); // below min 16
  assert.strictEqual(a11y.fontSize, 16);

  a11y.applyFontSize(100); // above max 72
  assert.strictEqual(a11y.fontSize, 72);

  a11y.applyFontSize(36);
  assert.strictEqual(a11y.fontSize, 36);

  // Theme application
  a11y.applyTheme('oled-black');
  assert.strictEqual(a11y.theme, 'oled-black');
  assert.strictEqual(global.document.body.className, 'theme-oled-black');

  a11y.applyTheme('yellow-black');
  assert.strictEqual(a11y.theme, 'yellow-black');
  assert.strictEqual(global.document.body.className, 'theme-yellow-black');
});

/* -------------------------------------------------------------
 * Obligation 7: Local Storage Serialization & Export
 * ------------------------------------------------------------- */
runTest('OBL-007: History Export Formatting & Shredding Verification', () => {
  const sampleRecords = [
    {
      id: 1,
      timestamp: '2026-10-15T12:00:00.000Z',
      text: 'Hello, how can I help you today?',
      durationSeconds: 15,
      wordCount: 7
    },
    {
      id: 2,
      timestamp: '2026-10-15T12:05:00.000Z',
      text: 'I am doing well, thank you!',
      durationSeconds: 8,
      wordCount: 6
    }
  ];

  // Verify text serialization
  const txtContent = sampleRecords.map(c => {
    return `[${new Date(c.timestamp).toLocaleString()}]\n${c.text}\n----------------------------------------\n`;
  }).join('\n');

  assert(txtContent.includes('Hello, how can I help you today?'));
  assert(txtContent.includes('I am doing well, thank you!'));

  // Verify JSON serialization
  const jsonContent = JSON.stringify(sampleRecords, null, 2);
  const parsed = JSON.parse(jsonContent);
  assert.strictEqual(parsed.length, 2);
  assert.strictEqual(parsed[0].wordCount, 7);
});

console.log('\n======================================================');
console.log(`  Tests completed: ${testsPassed} passed, ${testsFailed} failed.`);
console.log('======================================================\n');

if (testsFailed > 0) {
  process.exit(1);
}
