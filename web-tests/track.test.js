// Same cases as app/src/test/.../TrackAnalyzerTest.kt and ClockTimerTest.kt, run against the web port.
// Run with: node --test web-tests/track.test.js
const test = require('node:test');
const assert = require('node:assert');
const { drumLoop, PATTERNS } = require('../docs/tempo.js');
const { chordTrack } = require('../docs/key.js');
const { TrackAnalyzer, toCsv, downmix } = require('../docs/track.js');
const { ClockTimer } = require('../docs/midi.js');

function analyse(pcm, rate, chunk = 4096, low = 80, high = 160) {
  const a = new TrackAnalyzer(rate, low, high);
  for (let pos = 0; pos < pcm.length; pos += chunk) a.feed(pcm.subarray(pos, pos + chunk));
  return a.result();
}

test('a whole track gives its tempo, key and a tempo curve', () => {
  const rate = 44100;
  const pcm = drumLoop({ bpm: 124, seconds: 30, rate, pad: false });
  chordTrack({ tonic: 2, minor: true, bpm: 124, seconds: 30, rate, out: pcm });
  const r = analyse(pcm, rate);
  assert.ok(Math.abs(r.bpm - 124) <= 1, `bpm ${r.bpm}`);
  assert.strictEqual(r.key.name, 'D minor');
  assert.strictEqual(r.key.camelot, '7A');
  assert.ok(Math.abs(r.durationSec - 30) < 0.01);
  // Estimates at 6, 9, … 30 s.
  assert.deepStrictEqual(r.curve.map(([t]) => t), [6, 9, 12, 15, 18, 21, 24, 27, 30]);
});

test('the result does not depend on how the file is chunked', () => {
  const rate = 48000;
  const pcm = drumLoop({ bpm: 96, seconds: 16, rate, pattern: PATTERNS.hipHop });
  const a = analyse(pcm, rate, 1000);
  const b = analyse(pcm, rate, 8192);
  assert.deepStrictEqual(a.curve, b.curve);
  assert.ok(Math.abs(a.bpm - 96) <= 1, `bpm ${a.bpm}`);
});

test('a tempo that drifts shows up in the curve', () => {
  const rate = 44100;
  const slow = drumLoop({ bpm: 110, seconds: 20, rate });
  const fast = drumLoop({ bpm: 130, seconds: 20, rate });
  const pcm = new Float32Array(slow.length + fast.length);
  pcm.set(slow);
  pcm.set(fast, slow.length);
  const r = analyse(pcm, rate);
  const first = r.curve.find(([t]) => t === 18)[1];
  const last = r.curve[r.curve.length - 1][1];
  assert.ok(Math.abs(first - 110) <= 1, `at 18 s: ${first}`);
  assert.ok(Math.abs(last - 130) <= 1, `at the end: ${last}`);
});

test('a short file still gets one estimate', () => {
  const r = analyse(drumLoop({ bpm: 120, seconds: 5, rate: 44100 }), 44100);
  assert.strictEqual(r.curve.length, 1);
  assert.ok(Math.abs(r.bpm - 120) <= 1, `bpm ${r.bpm}`);
});

test('silence has no tempo and no key', () => {
  const r = analyse(new Float32Array(44100 * 8), 44100);
  assert.strictEqual(r.bpm, null);
  assert.strictEqual(r.key, null);
});

test('downmix averages the channels', () => {
  const m = downmix([new Float32Array([1, 0.5]), new Float32Array([0, 0.5])]);
  assert.deepStrictEqual(Array.from(m), [0.5, 0.5]);
});

test('CSV export quotes awkward file names', () => {
  const csv = toCsv([
    { file: 'Song, "live".mp3', durationSec: 201.04, bpm: 127.96, confidence: 0.812, key: { name: 'A minor', camelot: '8A', confidence: 0.9 } },
    { file: 'noise.wav', durationSec: 3, bpm: null, confidence: 0, key: null },
  ]);
  assert.strictEqual(csv,
    'file,duration_s,bpm,confidence,key,camelot,key_confidence\r\n'
    + '"Song, ""live"".mp3",201.0,128.0,0.81,A minor,8A,0.90\r\n'
    + 'noise.wav,3.0,,,,,\r\n');
});

test('MIDI clock ticks 24 times per beat and bends on tempo changes', () => {
  const c = new ClockTimer();
  assert.deepStrictEqual(c.due(1000, 120), []); // not started
  c.start(100);
  const beat = c.due(600, 120); // one beat at 120 BPM is 500 ms
  assert.strictEqual(beat.length, 24);
  assert.strictEqual(beat[0], 100);
  assert.ok(Math.abs(beat[1] - beat[0] - 500 / 24) < 1e-9);
  const faster = c.due(1100, 150); // 400 ms beats from the next tick on
  assert.ok(Math.abs(faster[0] - 600) < 1e-6, `${faster[0]}`);
  assert.ok(Math.abs(faster[1] - faster[0] - 400 / 24) < 1e-9);
  assert.deepStrictEqual(c.due(5000, null), []); // no tempo: wait
  c.stop();
  assert.ok(!c.running);
});
