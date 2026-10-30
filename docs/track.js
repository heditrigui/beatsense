/*
 * Whole-track analysis for audio files, a JavaScript port of the Android app's TrackAnalyzer.kt.
 *
 * The file is fed through the same TempoDetector the live mode uses. Every few seconds the
 * detector gives an estimate over its 12-second window, exactly as it does while listening;
 * the track's tempo is the median of the confident ones, and the whole list is the tempo
 * curve. The KeyDetector listens to the whole file with no forgetting.
 *
 * Works in browsers (adds to window.BeatSense) and in Node (require) for the tests.
 */
(function (root) {
  'use strict';

  const node = typeof module !== 'undefined' && module.exports;
  const { TempoDetector } = node ? require('./tempo.js') : root.BeatSense;
  const { KeyDetector } = node ? require('./key.js') : root.BeatSense;

  const ESTIMATE_EVERY_SEC = 3;
  const FIRST_ESTIMATE_SEC = 6;

  class TrackAnalyzer {
    constructor(rate, low = 80, high = 160) {
      this.rate = rate;
      this.low = low;
      this.high = high;
      this.tempo = new TempoDetector(rate);
      this.key = new KeyDetector(rate, 0);
      this.samples = 0;
      this.nextEstimate = Math.floor(FIRST_ESTIMATE_SEC * rate);
      /** [seconds into the track, BPM, confidence] for every estimate taken. */
      this.curve = [];
    }

    /** Feed mono samples in [-1, 1], in order, as they are decoded. */
    feed(samples) {
      let pos = 0;
      while (pos < samples.length) {
        // Stop at each estimate point, so estimates land at the same place however the file is chunked.
        const n = Math.min(samples.length - pos, this.nextEstimate - this.samples);
        const part = samples.subarray(pos, pos + n);
        this.tempo.feed(part);
        this.key.feed(part);
        this.samples += n;
        pos += n;
        if (this.samples === this.nextEstimate) {
          this._estimate();
          this.nextEstimate += Math.floor(ESTIMATE_EVERY_SEC * this.rate);
        }
      }
    }

    _estimate() {
      const e = this.tempo.estimate(this.low, this.high);
      if (e) this.curve.push([this.samples / this.rate, e.bpm, e.confidence]);
    }

    get durationSec() { return this.samples / this.rate; }

    /** BPM (median of the confident estimates), key, and the tempo curve. */
    result() {
      // A short file may end before the first estimate point.
      if (!this.curve.length) this._estimate();
      const good = this.curve.filter(([, , c]) => c >= 0.15);
      const bpms = good.map(([, b]) => b).sort((a, b) => a - b);
      const key = this.key.estimate();
      return {
        durationSec: this.durationSec,
        bpm: bpms.length ? bpms[Math.floor(bpms.length / 2)] : null,
        confidence: good.length ? good.reduce((s, [, , c]) => s + c, 0) / good.length : 0,
        key: key && key.confidence >= 0.2 ? key : null,
        curve: this.curve,
      };
    }
  }

  /** Mixes the channels of a decoded AudioBuffer (or a list of Float32Arrays) down to mono. */
  function downmix(channels) {
    if (channels.length === 1) return channels[0];
    const out = new Float32Array(channels[0].length);
    for (const ch of channels) for (let i = 0; i < out.length; i++) out[i] += ch[i] / channels.length;
    return out;
  }

  const CSV_HEADER = ['file', 'duration_s', 'bpm', 'confidence', 'key', 'camelot', 'key_confidence'];

  /** One CSV row per analysed file. Same columns and format as the Android app's export. */
  function toCsv(rows) {
    const cell = (v) => {
      const s = v == null ? '' : String(v);
      return /[",\n\r]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s;
    };
    const lines = [CSV_HEADER.join(',')];
    for (const r of rows) {
      lines.push([
        r.file,
        r.durationSec == null ? '' : r.durationSec.toFixed(1),
        r.bpm == null ? '' : r.bpm.toFixed(1),
        r.bpm == null ? '' : r.confidence.toFixed(2),
        r.key ? r.key.name : '',
        r.key ? r.key.camelot : '',
        r.key ? r.key.confidence.toFixed(2) : '',
      ].map(cell).join(','));
    }
    return lines.join('\r\n') + '\r\n';
  }

  const api = { TrackAnalyzer, downmix, toCsv };
  if (node) module.exports = api;
  else Object.assign(root.BeatSense, api);
})(typeof window !== 'undefined' ? window : globalThis);
