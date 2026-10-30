// Same cases as app/src/test/.../KeyDetectorTest.kt, run against the web port.
// Run with: node --test web-tests/key.test.js
const test = require('node:test');
const assert = require('node:assert');
const { drumLoop } = require('../docs/tempo.js');
const { KeyDetector, camelot, keyName, chordTrack } = require('../docs/key.js');

/** A drum loop with chords in a key on top, the way a song would sound. */
function song({ tonic, minor, bpm = 112, seconds = 20, rate = 44100 }) {
  const pcm = drumLoop({ bpm, seconds, rate, seed: tonic + 1, pad: false });
  return chordTrack({ tonic, minor, bpm, seconds, rate, out: pcm });
}

function detect(pcm, rate, memorySeconds = 0) {
  const det = new KeyDetector(rate, memorySeconds);
  for (let pos = 0; pos < pcm.length; pos += 2048) det.feed(pcm.subarray(pos, pos + 2048));
  return det.estimate();
}

const cases = [
  [0, false, 44100], [9, true, 44100], [6, false, 44100], [3, true, 44100],
  [7, false, 48000], [11, true, 48000], [10, false, 48000], [5, true, 48000],
];
for (const [tonic, minor, rate] of cases) {
  test(`${keyName(tonic, minor)} (${rate} Hz)`, () => {
    const e = detect(song({ tonic, minor, rate }), rate);
    assert.ok(e, 'no key');
    assert.strictEqual(e.name, keyName(tonic, minor));
    assert.ok(e.confidence > 0.5, `confidence ${e.confidence}`);
  });
}

test('drums alone have no confident key', () => {
  const e = detect(drumLoop({ bpm: 120, seconds: 15, rate: 44100, pad: false }), 44100);
  assert.ok(e === null || e.confidence < 0.2, `confidence ${e && e.confidence}`);
});

test('silence gives no key', () => {
  assert.strictEqual(detect(new Float32Array(44100 * 10), 44100), null);
});

test('live memory follows a key change', () => {
  const rate = 44100;
  const a = song({ tonic: 0, minor: false, seconds: 20, rate });
  const b = song({ tonic: 4, minor: true, seconds: 40, rate });
  const both = new Float32Array(a.length + b.length);
  both.set(a);
  both.set(b, a.length);
  assert.strictEqual(detect(both, rate, 15).name, 'E minor');
});

test('camelot codes', () => {
  assert.strictEqual(camelot(9, true), '8A'); // A minor
  assert.strictEqual(camelot(0, false), '8B'); // C major
  assert.strictEqual(camelot(7, false), '9B'); // G major
  assert.strictEqual(camelot(4, true), '9A'); // E minor
  assert.strictEqual(camelot(11, false), '1B'); // B major
  assert.strictEqual(camelot(8, true), '1A'); // G# minor
  assert.strictEqual(camelot(5, false), '7B'); // F major
  assert.strictEqual(camelot(2, true), '7A'); // D minor
  // Every code is used exactly once.
  const all = new Set();
  for (let t = 0; t < 12; t++) for (const m of [false, true]) all.add(camelot(t, m));
  assert.strictEqual(all.size, 24);
});
