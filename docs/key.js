/*
 * Musical key detection, a JavaScript port of the Android app's KeyDetector.kt.
 *
 * 1. The audio is low-passed and decimated to about 11 kHz, where a 4096-point FFT resolves
 *    semitones down to the bass register.
 * 2. Every frame's spectrum between 65 Hz and 2.1 kHz is folded into a 12-bin chromagram
 *    (how much C, C#, D... is sounding), normalised so loud and quiet passages count alike.
 * 3. The chromagram, summed over time, is correlated with the Krumhansl-Kessler key profiles
 *    rotated to all 24 major and minor keys. The best match is the key.
 *
 * Works in browsers (adds to window.BeatSense) and in Node (require) for the tests.
 */
(function (root) {
  'use strict';

  const { Fft } = typeof module !== 'undefined' && module.exports ? require('./tempo.js') : root.BeatSense;

  // Krumhansl & Kessler (1982): how well each scale degree fits a major or a minor key.
  const MAJOR_PROFILE = [6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88];
  const MINOR_PROFILE = [6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17];
  const MAJOR_NAMES = ['C', 'D♭', 'D', 'E♭', 'E', 'F', 'F♯', 'G', 'A♭', 'A', 'B♭', 'B'];
  const MINOR_NAMES = ['C', 'C♯', 'D', 'E♭', 'E', 'F', 'F♯', 'G', 'G♯', 'A', 'B♭', 'B'];

  /** Camelot wheel code for a key, e.g. A minor -> "8A", C major -> "8B" (DJs mix neighbours). */
  function camelot(tonic, minor) {
    const major = minor ? (tonic + 3) % 12 : tonic; // a minor key sits with its relative major
    return `${((major * 7 + 7) % 12) + 1}${minor ? 'A' : 'B'}`;
  }

  function keyName(tonic, minor) {
    return `${(minor ? MINOR_NAMES : MAJOR_NAMES)[tonic]} ${minor ? 'minor' : 'major'}`;
  }

  /** Pearson correlation of a chromagram with a profile rotated to start on `tonic`. */
  function correlate(chroma, profile, tonic) {
    let mc = 0, mp = 0;
    for (let i = 0; i < 12; i++) { mc += chroma[i]; mp += profile[i]; }
    mc /= 12; mp /= 12;
    let num = 0, dc = 0, dp = 0;
    for (let i = 0; i < 12; i++) {
      const c = chroma[(i + tonic) % 12] - mc;
      const p = profile[i] - mp;
      num += c * p; dc += c * c; dp += p * p;
    }
    return dc > 0 ? num / Math.sqrt(dc * dp) : 0;
  }

  /** Best of the 24 keys for a chromagram, plus how far it beats the runner-up. */
  function matchKey(chroma) {
    const scores = [];
    for (let tonic = 0; tonic < 12; tonic++) {
      scores.push({ tonic, minor: false, r: correlate(chroma, MAJOR_PROFILE, tonic) });
      scores.push({ tonic, minor: true, r: correlate(chroma, MINOR_PROFILE, tonic) });
    }
    scores.sort((a, b) => b.r - a.r);
    const best = scores[0];
    return {
      tonic: best.tonic,
      minor: best.minor,
      name: keyName(best.tonic, best.minor),
      camelot: camelot(best.tonic, best.minor),
      correlation: best.r,
      // 0..1: a clear best match that stands out from the next key.
      confidence: Math.min(1, Math.max(0, (best.r - 0.4) / 0.4)) * Math.min(1, (best.r - scores[1].r) * 10),
    };
  }

  /** 2nd-order Butterworth low-pass (RBJ cookbook), run twice for a steeper anti-alias filter. */
  function lowPass(rate, cutoff) {
    const w0 = (2 * Math.PI * cutoff) / rate;
    const alpha = Math.sin(w0) / (2 * Math.SQRT1_2);
    const a0 = 1 + alpha;
    const cos = Math.cos(w0);
    return { b0: (1 - cos) / 2 / a0, b1: (1 - cos) / a0, b2: (1 - cos) / 2 / a0, a1: (-2 * cos) / a0, a2: (1 - alpha) / a0 };
  }

  class KeyDetector {
    /**
     * `memorySeconds` is how long the chromagram remembers: about 30 s for live listening so it can
     * follow a key change, 0 to weigh a whole file equally.
     */
    constructor(inputRate, memorySeconds = 30) {
      this.decimation = Math.max(1, Math.floor(inputRate / 11025));
      this.rate = inputRate / this.decimation;
      this.frameSize = 4096;
      this.hop = 2048;
      this.decay = memorySeconds > 0 ? Math.exp(-this.hop / this.rate / memorySeconds) : 1;
      this.filter = lowPass(inputRate, 2500);

      this.fft = new Fft(this.frameSize);
      this.window = new Float64Array(this.frameSize);
      for (let i = 0; i < this.frameSize; i++) {
        this.window[i] = 0.5 - 0.5 * Math.cos((2 * Math.PI * i) / (this.frameSize - 1));
      }
      this.re = new Float64Array(this.frameSize);
      this.im = new Float64Array(this.frameSize);
      this.circular = new Float32Array(this.frameSize);

      // Each FFT bin in range feeds its nearest pitch class, weighted by how close it is to the note.
      this.binFrom = Math.ceil((65 * this.frameSize) / this.rate);
      this.binTo = Math.floor((2100 * this.frameSize) / this.rate);
      this.binClass = new Int8Array(this.binTo + 1);
      this.binWeight = new Float64Array(this.binTo + 1);
      for (let k = this.binFrom; k <= this.binTo; k++) {
        const midi = 69 + 12 * Math.log2((k * this.rate) / this.frameSize / 440);
        const nearest = Math.round(midi);
        this.binClass[k] = ((nearest % 12) + 12) % 12;
        this.binWeight[k] = Math.cos(Math.PI * (midi - nearest)) ** 2;
      }
      this.chroma = new Float64Array(12);
      this.reset();
    }

    reset() {
      this.writePos = 0;
      this.filled = 0;
      this.sinceHop = 0;
      this.decimCount = 0;
      this.z = [0, 0, 0, 0, 0, 0, 0, 0]; // x1, x2, y1, y2 for each of the two filter stages
      this.chroma.fill(0);
      /** Frames with enough sound to count, since reset. */
      this.tonalFrames = 0;
    }

    /** Feed mono samples in [-1, 1] as they arrive. */
    feed(samples) {
      const f = this.filter, z = this.z;
      for (let i = 0; i < samples.length; i++) {
        let x = samples[i];
        for (let s = 0; s < 8; s += 4) {
          const y = f.b0 * x + f.b1 * z[s] + f.b2 * z[s + 1] - f.a1 * z[s + 2] - f.a2 * z[s + 3];
          z[s + 1] = z[s]; z[s] = x; z[s + 3] = z[s + 2]; z[s + 2] = y;
          x = y;
        }
        if (++this.decimCount < this.decimation) continue;
        this.decimCount = 0;
        this._push(x);
      }
    }

    _push(sample) {
      this.circular[this.writePos] = sample;
      this.writePos = (this.writePos + 1) % this.frameSize;
      if (this.filled < this.frameSize) this.filled++;
      if (++this.sinceHop >= this.hop && this.filled === this.frameSize) {
        this.sinceHop = 0;
        this._analyseFrame();
      }
    }

    _analyseFrame() {
      const n = this.frameSize, re = this.re, im = this.im;
      let energy = 0;
      for (let i = 0; i < n; i++) {
        const s = this.circular[(this.writePos + i) % n];
        energy += s * s;
        re[i] = s * this.window[i];
        im[i] = 0;
      }
      for (let i = 0; i < 12; i++) this.chroma[i] *= this.decay;
      if (Math.sqrt(energy / n) < 0.003) return; // silence says nothing about the key
      this.fft.transform(re, im);

      const frame = new Float64Array(12);
      let total = 0;
      for (let k = this.binFrom; k <= this.binTo; k++) {
        const v = Math.hypot(re[k], im[k]) * this.binWeight[k];
        frame[this.binClass[k]] += v;
        total += v;
      }
      if (total <= 0) return;
      for (let i = 0; i < 12; i++) this.chroma[i] += frame[i] / total;
      this.tonalFrames++;
    }

    /** Seconds of sound that went into the chromagram. */
    get tonalSeconds() { return (this.tonalFrames * this.hop) / this.rate; }

    /** The most likely key, or null until there are a few seconds of sound. */
    estimate() {
      if (this.tonalSeconds < 4) return null;
      return matchKey(this.chroma);
    }
  }

  /**
   * Renders chords in a key with a known tonic (for the tests and the demo beat): one chord per
   * bar of `progression` (scale degrees), each a root-position triad plus a bass note, with a few
   * harmonics so it sounds like an instrument rather than pure sine waves. Mono samples, added
   * to `out` if given.
   */
  function chordTrack({ tonic, minor, bpm, seconds, rate, out, level = 0.12, progression }) {
    const buf = out || new Float32Array(Math.floor(seconds * rate));
    // Natural minor, with the raised 7th of the dominant chord (harmonic minor) like most songs.
    const scale = minor ? [0, 2, 3, 5, 7, 8, 11] : [0, 2, 4, 5, 7, 9, 11];
    const prog = progression || (minor ? [0, 3, 4, 0, 5, 3, 4, 0] : [0, 3, 4, 0, 5, 3, 4, 0]);
    const bar = (4 * 60) / bpm;
    const total = Math.floor(seconds * rate);
    for (let b = 0; b * bar < seconds; b++) {
      const degree = prog[b % prog.length];
      const notes = [0, 2, 4].map((step) => {
        const idx = degree + step;
        return 60 + tonic + scale[idx % 7] + 12 * Math.floor(idx / 7) - (tonic > 5 ? 12 : 0);
      });
      notes.push(notes[0] - 12, notes[0] - 24); // bass doubles the root
      const start = Math.floor(b * bar * rate);
      const end = Math.min(total, Math.floor((b + 1) * bar * rate));
      for (const note of notes) {
        const w = (2 * Math.PI * 440 * 2 ** ((note - 69) / 12)) / rate;
        const amp = level / notes.length;
        for (let i = start; i < end; i++) {
          const t = (i - start) / rate;
          const ph = w * i;
          const tone = Math.sin(ph) + Math.sin(2 * ph) / 2 + Math.sin(3 * ph) / 3 + Math.sin(4 * ph) / 4;
          buf[i] += amp * tone * Math.exp(-t * 0.6) * Math.min(1, t * 50);
        }
      }
    }
    return buf;
  }

  const api = { KeyDetector, camelot, keyName, matchKey, chordTrack, MAJOR_PROFILE, MINOR_PROFILE };
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  else Object.assign(root.BeatSense, api);
})(typeof window !== 'undefined' ? window : globalThis);
