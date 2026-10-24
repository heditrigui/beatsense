// Same cases as app/src/test/.../TempoDetectorTest.kt, run against the web port.
// Run with: node --test web-tests
const test = require('node:test');
const assert = require('node:assert');
const { TempoDetector, TapTempo, drumLoop, PATTERNS } = require('../docs/tempo.js');

function detect(pcm, rate, low = 80, high = 160) {
  const det = new TempoDetector(rate);
  // Feed in browser-sized chunks, the way the web app's audio callback does.
  for (let pos = 0; pos < pcm.length; pos += 2048) det.feed(pcm.subarray(pos, pos + 2048));
  return det.estimate(low, high);
}

function assertTempo(expected, actual, tolerance = 1) {
  assert.ok(Math.abs(expected - actual) <= tolerance, `expected ${expected} ± ${tolerance} BPM but got ${actual.toFixed(2)}`);
}

for (const rate of [44100, 48000]) {
  test(`house at 128 (${rate} Hz)`, () => {
    const e = detect(drumLoop({ bpm: 128, seconds: 14, rate }), rate);
    assert.ok(e);
    assertTempo(128, e.bpm);
    assert.ok(e.confidence > 0.3, `confidence ${e.confidence}`);
  });

  test(`hip-hop at 92 (${rate} Hz)`, () => {
    assertTempo(92, detect(drumLoop({ bpm: 92, seconds: 14, rate, pattern: PATTERNS.hipHop }), rate).bpm);
  });

  test(`drum & bass at 174 folds into the fast range (${rate} Hz)`, () => {
    assertTempo(174, detect(drumLoop({ bpm: 174, seconds: 14, rate, pattern: PATTERNS.dnb }), rate, 110, 220).bpm);
  });
}

test('odd tempo with human timing', () => {
  assertTempo(103, detect(drumLoop({ bpm: 103, seconds: 14, rate: 44100, jitterMs: 8, seed: 7 }), 44100).bpm);
});

test('slow ballad', () => {
  const ballad = [[0, 'kick'], [2, 'snare'], [0, 'hat'], [1, 'hat'], [2, 'hat'], [3, 'hat']];
  assertTempo(72, detect(drumLoop({ bpm: 72, seconds: 14, rate: 44100, pattern: ballad }), 44100, 60, 120).bpm);
});

test('many tempos across the range', () => {
  for (const bpm of [82, 85, 100, 115, 124, 135, 140, 150, 158]) {
    assertTempo(bpm, detect(drumLoop({ bpm, seconds: 13, rate: 48000, seed: bpm }), 48000).bpm);
  }
});

test('beat phase points at the last kick', () => {
  const bpm = 120;
  const e = detect(drumLoop({ bpm, seconds: 12.25, rate: 44100 }), 44100); // ends a quarter beat after a kick
  const expectedAgo = 12.25 % (60 / bpm);
  assert.ok(Math.abs(e.lastBeatAgoSec - expectedAgo) <= 0.06, `last beat ${e.lastBeatAgoSec}s ago, expected ~${expectedAgo}`);
});

test('silence gives no tempo', () => {
  assert.strictEqual(detect(new Float32Array(44100 * 10), 44100), null);
});

test('too short gives no tempo', () => {
  assert.strictEqual(detect(drumLoop({ bpm: 120, seconds: 2, rate: 44100 }), 44100), null);
});

test('a steady tone has low confidence', () => {
  const tone = new Float32Array(44100 * 10).map((_, i) => Math.sin((2 * Math.PI * 440 * i) / 44100) * 0.37);
  const e = detect(tone, 44100);
  assert.ok(e === null || e.confidence < 0.25, `confidence ${e && e.confidence}`);
});

test('fold keeps the tempo in range', () => {
  assert.strictEqual(TempoDetector.fold(64, 80, 160), 128);
  assert.strictEqual(TempoDetector.fold(174, 60, 120), 87);
  assert.strictEqual(TempoDetector.fold(140, 100, 200), 140);
});

test('tap tempo averages recent taps and resets after a pause', () => {
  const t = new TapTempo();
  assert.strictEqual(t.tap(0), null);
  assert.strictEqual(t.tap(500), null);
  assert.strictEqual(t.tap(1000), 120);
  assert.strictEqual(t.tap(1500), 120);
  assert.strictEqual(t.tap(5000), null); // more than 2.5 s later: starts over
  assert.strictEqual(t.count, 1);
});
