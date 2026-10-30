/*
 * MIDI beat clock timing, a JavaScript port of the Android app's ClockTimer (MidiClock.kt).
 *
 * MIDI clock is 24 "timing clock" messages (0xF8) per quarter note, framed by Start (0xFA)
 * and Stop (0xFC). Other apps, drum machines and synths follow the tempo by measuring the
 * time between clocks. The timer decides when each clock is due; the app sends it with
 * Web MIDI's timestamped send, ahead of time, so the spacing stays exact.
 *
 * Works in browsers (adds to window.BeatSense) and in Node (require) for the tests.
 */
(function (root) {
  'use strict';

  const CLOCK = 0xf8;
  const START = 0xfa;
  const STOP = 0xfc;
  const PPQN = 24;

  class ClockTimer {
    constructor() { this.next = null; }

    get running() { return this.next != null; }

    /** Starts the clock with the first tick at `at` (ms). */
    start(at) { this.next = at; }

    stop() { this.next = null; }

    /**
     * Clock times (ms) due before `until` at `bpm`. A tempo change applies from the next tick,
     * so the clock bends smoothly instead of jumping.
     */
    due(until, bpm) {
      const out = [];
      if (this.next == null || !(bpm > 0)) return out;
      const step = 60000 / bpm / PPQN;
      while (this.next < until) {
        out.push(this.next);
        this.next += step;
      }
      return out;
    }
  }

  const api = { ClockTimer, MIDI_CLOCK: CLOCK, MIDI_START: START, MIDI_STOP: STOP, PPQN };
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  else Object.assign(root.BeatSense, api);
})(typeof window !== 'undefined' ? window : globalThis);
