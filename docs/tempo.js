/*
 * Real-time tempo detection, a JavaScript port of the Android app's TempoDetector.kt.
 *
 * 1. Spectral flux: every hop, an FFT frame is compared with the previous one and the
 *    increases in (log) energy across all frequencies are summed. Drum hits and note
 *    attacks make this "onset strength" spike.
 * 2. Autocorrelation of the last `windowSeconds` of onset strength reveals how often the
 *    spikes repeat. Harmonics (2x and 3x the period) are added so the true beat wins over
 *    sub-divisions, and a gentle preference around 120 BPM settles half/double ambiguity.
 * 3. The beat phase is found by sliding a comb at the detected period over the onsets,
 *    so the UI can pulse in time with the music.
 *
 * Works in browsers (window.BeatSense) and in Node (require) for the tests.
 */
(function (root) {
  'use strict';

  const MIN_BPM = 50;
  const MAX_BPM = 220;

  /** In-place radix-2 complex FFT of a fixed power-of-two size. */
  class Fft {
    constructor(n) {
      if (n < 2 || (n & (n - 1)) !== 0) throw new Error('FFT size must be a power of two');
      this.n = n;
      this.cos = new Float64Array(n / 2);
      this.sin = new Float64Array(n / 2);
      for (let i = 0; i < n / 2; i++) {
        this.cos[i] = Math.cos((2 * Math.PI * i) / n);
        this.sin[i] = Math.sin((2 * Math.PI * i) / n);
      }
      const bits = Math.log2(n);
      this.reversed = new Uint32Array(n);
      for (let i = 0; i < n; i++) {
        let r = 0;
        for (let b = 0; b < bits; b++) r = (r << 1) | ((i >> b) & 1);
        this.reversed[i] = r;
      }
    }

    transform(re, im) {
      const n = this.n;
      for (let i = 0; i < n; i++) {
        const j = this.reversed[i];
        if (j > i) {
          let t = re[i]; re[i] = re[j]; re[j] = t;
          t = im[i]; im[i] = im[j]; im[j] = t;
        }
      }
      for (let size = 2; size <= n; size *= 2) {
        const half = size / 2;
        const step = n / size;
        for (let start = 0; start < n; start += size) {
          let k = 0;
          for (let j = start; j < start + half; j++) {
            const c = this.cos[k];
            const s = this.sin[k];
            const tr = re[j + half] * c + im[j + half] * s;
            const ti = im[j + half] * c - re[j + half] * s;
            re[j + half] = re[j] - tr;
            im[j + half] = im[j] - ti;
            re[j] += tr;
            im[j] += ti;
            k += step;
          }
        }
      }
    }
  }

  class TempoDetector {
    constructor(inputRate, windowSeconds = 12) {
      this.decimation = inputRate >= 32000 ? 2 : 1;
      this.rate = inputRate / this.decimation;
      this.frameSize = 1024;
      this.hop = 128;
      this.framesPerSecond = this.rate / this.hop;

      this.fft = new Fft(this.frameSize);
      this.window = new Float64Array(this.frameSize);
      for (let i = 0; i < this.frameSize; i++) {
        this.window[i] = 0.5 - 0.5 * Math.cos((2 * Math.PI * i) / (this.frameSize - 1));
      }
      this.re = new Float64Array(this.frameSize);
      this.im = new Float64Array(this.frameSize);
      this.prevSpectrum = new Float64Array(this.frameSize / 2);
      this.circular = new Float32Array(this.frameSize);
      this.envCapacity = Math.floor(windowSeconds * this.framesPerSecond);
      this.envelope = new Float64Array(this.envCapacity);
      this.reset();
    }

    reset() {
      this.writePos = 0;
      this.filled = 0;
      this.sinceHop = 0;
      this.decimAcc = 0;
      this.decimCount = 0;
      this.envWrite = 0;
      this.envCount = 0;
      this.havePrev = false;
      /** Loudness of the latest frame, 0..1 (rough, for a level meter). */
      this.level = 0;
      this.prevSpectrum.fill(0);
    }

    /** Feed mono samples in [-1, 1] as they arrive. */
    feed(samples) {
      for (let i = 0; i < samples.length; i++) {
        const s = samples[i];
        if (this.decimation === 2) {
          this.decimAcc += s;
          if (++this.decimCount < 2) continue;
          this._push(this.decimAcc / 2);
          this.decimAcc = 0;
          this.decimCount = 0;
        } else {
          this._push(s);
        }
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
      const n = this.frameSize;
      const re = this.re;
      const im = this.im;
      let energy = 0;
      for (let i = 0; i < n; i++) {
        const s = this.circular[(this.writePos + i) % n];
        energy += s * s;
        re[i] = s * this.window[i];
        im[i] = 0;
      }
      this.level = Math.min(1, Math.sqrt(energy / n) * 4);
      this.fft.transform(re, im);

      let flux = 0;
      for (let k = 1; k < n / 2; k++) {
        const mag = Math.log(1 + 100 * Math.hypot(re[k], im[k]));
        if (this.havePrev) flux += Math.max(0, mag - this.prevSpectrum[k]);
        this.prevSpectrum[k] = mag;
      }
      this.havePrev = true;

      this.envelope[this.envWrite] = flux;
      this.envWrite = (this.envWrite + 1) % this.envCapacity;
      if (this.envCount < this.envCapacity) this.envCount++;
    }

    /** Onset strength over the analysis window, oldest first. */
    envelopeSnapshot() {
      const out = new Float64Array(this.envCount);
      const start = (this.envWrite - this.envCount + this.envCapacity) % this.envCapacity;
      for (let i = 0; i < this.envCount; i++) out[i] = this.envelope[(start + i) % this.envCapacity];
      return out;
    }

    /**
     * Current tempo, folded into [rangeLow, rangeHigh): rhythm is ambiguous by octaves (a 128 BPM
     * house track also "pulses" at 64), so the caller picks the musical range it expects.
     */
    estimate(rangeLow = 80, rangeHigh = 160) {
      const fps = this.framesPerSecond;
      const n = this.envCount;
      if (n < fps * 4) return null;
      const raw = this.envelopeSnapshot();

      // Remove the slowly varying loudness so only the spikes remain.
      const half = Math.floor(fps * 0.2);
      const prefix = new Float64Array(n + 1);
      for (let i = 0; i < n; i++) prefix[i + 1] = prefix[i] + raw[i];
      const d = new Float64Array(n);
      for (let t = 0; t < n; t++) {
        const a = Math.max(0, t - half);
        const b = Math.min(n, t + half + 1);
        d[t] = Math.max(0, raw[t] - (prefix[b] - prefix[a]) / (b - a));
      }

      const minLag = Math.max(2, Math.floor((60 * fps) / MAX_BPM));
      const maxLag = Math.floor((60 * fps) / MIN_BPM) + 1;
      if (maxLag >= Math.floor(n / 2)) return null;
      const acfLen = Math.min(n - 1, 3 * maxLag + 2);
      const acf = new Float64Array(acfLen + 1);
      for (let lag = 0; lag <= acfLen; lag++) {
        let s = 0;
        for (let t = 0; t < n - lag; t++) s += d[t] * d[t + lag];
        acf[lag] = s / (n - lag);
      }
      if (acf[0] < 1e-9) return null;

      const acfAt = (x) => {
        const i = Math.floor(x);
        if (i < 0 || i + 1 > acfLen) return 0;
        const f = x - i;
        return acf[i] * (1 - f) + acf[i + 1] * f;
      };
      const score = (lag) => {
        const bpm = (60 * fps) / lag;
        const octaves = Math.log(bpm / 120) / Math.log(2);
        const prior = Math.exp(-0.5 * octaves * octaves);
        return (acfAt(lag) + 0.5 * acfAt(2 * lag) + 0.25 * acfAt(3 * lag)) * prior;
      };

      // 1) Strongest periodicity on whole lags.
      let bestLag = minLag;
      let bestScore = -1;
      for (let lag = minLag; lag <= maxLag; lag++) {
        const s = score(lag);
        if (s > bestScore) { bestScore = s; bestLag = lag; }
      }
      const rawBpm = (60 * fps) / bestLag;

      // 2) Fold into the requested range, then polish the period on a fine fractional grid.
      const folded = TempoDetector.fold(rawBpm, rangeLow, rangeHigh);
      const centre = (60 * fps) / folded;
      let lag = centre;
      let lagScore = -1;
      for (let x = centre - 1.2; x <= centre + 1.2; x += 0.01) {
        const s = acfAt(x) + 0.5 * acfAt(2 * x);
        if (s > lagScore) { lagScore = s; lag = x; }
      }
      const bpm = (60 * fps) / lag;

      // Confidence: how far the beat peak stands above the average periodicity, gated by
      // loudness so hum, tones and room noise don't look like a groove.
      let mean = 0;
      for (let i = minLag; i <= maxLag; i++) mean += acf[i];
      mean /= maxLag - minLag + 1;
      const contrast = (Math.max(acfAt(lag), acfAt(2 * lag)) - mean) / acf[0];
      let rawMean = 0;
      for (let i = 0; i < n; i++) rawMean += raw[i];
      rawMean /= n;
      const loudness = Math.min(1, Math.max(0, (rawMean - 5) / 15));
      const confidence = Math.min(1, Math.max(0, contrast * 1.6)) * loudness;

      // 3) Beat phase: where a comb with this period lines up best with the most recent onsets.
      let bestPhase = 0;
      let bestPhaseScore = -1;
      const recent = Math.min(n, Math.floor(fps * 6));
      for (let phase = 0; phase < Math.floor(lag); phase++) {
        let s = 0;
        for (let pos = n - 1 - phase; pos >= n - recent; pos -= lag) {
          const i = Math.floor(pos);
          s += d[i] + 0.5 * (i > 0 ? d[i - 1] : 0) + 0.5 * (i < n - 1 ? d[i + 1] : 0);
        }
        if (s > bestPhaseScore) { bestPhaseScore = s; bestPhase = phase; }
      }

      return { bpm, confidence, periodSec: lag / fps, lastBeatAgoSec: bestPhase / fps };
    }

    /** Brings a tempo into [low, high) by doubling or halving, e.g. 64 -> 128 for a dance range. */
    static fold(bpm, low, high) {
      let b = bpm;
      while (b < low) b *= 2;
      while (b >= high) b /= 2;
      return b;
    }
  }

  /** Tap along with the song; the average of the recent intervals gives the tempo. */
  class TapTempo {
    constructor() { this.taps = []; }

    /** Registers a tap at nowMs; returns the tempo once there are at least 3 taps. */
    tap(nowMs) {
      if (this.taps.length && nowMs - this.taps[this.taps.length - 1] > 2500) this.taps = [];
      this.taps.push(nowMs);
      while (this.taps.length > 9) this.taps.shift();
      if (this.taps.length < 3) return null;
      let sum = 0;
      for (let i = 1; i < this.taps.length; i++) sum += this.taps[i] - this.taps[i - 1];
      return 60000 / (sum / (this.taps.length - 1));
    }

    get count() { return this.taps.length; }
    reset() { this.taps = []; }
  }

  function tempoName(bpm) {
    if (bpm < 60) return 'Largo';
    if (bpm < 66) return 'Larghetto';
    if (bpm < 76) return 'Adagio';
    if (bpm < 108) return 'Andante';
    if (bpm < 120) return 'Moderato';
    if (bpm < 156) return 'Allegro';
    if (bpm < 176) return 'Vivace';
    if (bpm < 200) return 'Presto';
    return 'Prestissimo';
  }

  function genreHint(bpm) {
    if (bpm < 70) return 'Ballads, ambient, slow jams';
    if (bpm < 90) return 'Hip-hop, R&B, chill';
    if (bpm < 100) return 'Reggaeton, hip-hop, dancehall';
    if (bpm < 115) return 'Pop, funk, disco';
    if (bpm < 125) return 'Pop, house, dance';
    if (bpm < 135) return 'House, techno, EDM';
    if (bpm < 150) return 'Trance, dubstep, techno';
    if (bpm < 165) return 'Hardstyle, punk, footwork';
    if (bpm < 185) return 'Drum & bass, jungle';
    return 'Hardcore, speed metal';
  }

  /** Small seeded random generator, so demo beats and tests are repeatable. */
  function seededRandom(seed) {
    let a = seed >>> 0;
    return () => {
      a = (a + 0x6d2b79f5) >>> 0;
      let t = a;
      t = Math.imul(t ^ (t >>> 15), t | 1);
      t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
      return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
    };
  }

  const PATTERNS = {
    house: [[0, 'kick'], [1, 'kick'], [2, 'kick'], [3, 'kick'], [1, 'snare'], [3, 'snare'],
      [0.5, 'hat'], [1.5, 'hat'], [2.5, 'hat'], [3.5, 'hat']],
    hipHop: [[0, 'kick'], [1.5, 'kick'], [2, 'kick'], [1, 'snare'], [3, 'snare'],
      ...Array.from({ length: 8 }, (_, i) => [i * 0.5, 'hat'])],
    dnb: [[0, 'kick'], [2.5, 'kick'], [1, 'snare'], [3, 'snare'],
      ...Array.from({ length: 8 }, (_, i) => [i * 0.5, 'hat'])],
  };

  /**
   * Renders a drum loop with a known tempo (the same synthesis the Android unit tests use):
   * kick, snare and hi-hat hits plus a sustained chord and a little noise that have no rhythm.
   * Returns mono samples in [-1, 1].
   */
  function drumLoop({ bpm, seconds, rate, pattern = PATTERNS.house, jitterMs = 0, seed = 1, pad = true }) {
    const rnd = seededRandom(seed);
    const out = new Float32Array(Math.floor(seconds * rate));
    const beat = 60 / bpm;
    for (let bar = 0; bar * 4 * beat < seconds; bar++) {
      for (const [pos, hit] of pattern) {
        const t = (bar * 4 + pos) * beat + ((rnd() * 2 - 1) * jitterMs) / 1000;
        const start = Math.floor(t * rate);
        if (start < 0 || start >= out.length) continue;
        if (hit === 'kick') {
          for (let i = 0; i < 0.25 * rate && start + i < out.length; i++) {
            const tt = i / rate;
            const f = 50 + 70 * Math.exp(-tt * 30);
            out[start + i] += 0.8 * Math.sin(2 * Math.PI * f * tt) * Math.exp(-tt * 14);
          }
        } else if (hit === 'snare') {
          for (let i = 0; i < 0.18 * rate && start + i < out.length; i++) {
            const tt = i / rate;
            const v = (rnd() * 2 - 1) * 0.45 + 0.2 * Math.sin(2 * Math.PI * 190 * tt);
            out[start + i] += v * Math.exp(-tt * 22);
          }
        } else {
          let prev = 0;
          for (let i = 0; i < 0.05 * rate && start + i < out.length; i++) {
            const nz = rnd() * 2 - 1;
            out[start + i] += (nz - prev) * 0.15 * Math.exp((-i / rate) * 70);
            prev = nz;
          }
        }
      }
    }
    for (let i = 0; i < out.length; i++) {
      const t = i / rate;
      if (pad) out[i] += 0.08 * Math.sin(2 * Math.PI * 55 * t) + 0.05 * Math.sin(2 * Math.PI * 220 * t) + 0.04 * Math.sin(2 * Math.PI * 330 * t);
      out[i] += (rnd() * 2 - 1) * 0.02;
      out[i] = Math.max(-1, Math.min(1, out[i]));
    }
    return out;
  }

  const api = { Fft, TempoDetector, TapTempo, tempoName, genreHint, drumLoop, PATTERNS, seededRandom };
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  else root.BeatSense = api;
})(typeof window !== 'undefined' ? window : globalThis);
