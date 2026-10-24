package com.hedi.beatsense.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Real-time tempo detection from raw microphone samples.
 *
 * 1. Spectral flux: every hop, an FFT frame is compared with the previous one and the
 *    increases in (log) energy across all frequencies are summed. Drum hits and note
 *    attacks make this "onset strength" spike.
 * 2. Autocorrelation of the last [windowSeconds] of onset strength reveals how often the
 *    spikes repeat. Harmonics (2× and 3× the period) are added so the true beat wins over
 *    sub-divisions, and a gentle preference around 120 BPM settles half/double ambiguity.
 * 3. The beat phase is found by sliding a comb at the detected period over the onsets,
 *    so the UI can pulse in time with the music.
 */
class TempoDetector(inputRate: Int, private val windowSeconds: Double = 12.0) {

    data class Estimate(
        val bpm: Double,
        /** 0..1 — how clearly the rhythm stands out. */
        val confidence: Double,
        val periodSec: Double,
        /** Seconds since the most recent beat, at the time of the estimate. */
        val lastBeatAgoSec: Double,
    )

    private val decimation = if (inputRate >= 32000) 2 else 1
    val rate = inputRate / decimation
    private val frameSize = 1024
    private val hop = 128
    val framesPerSecond = rate.toDouble() / hop

    private val fft = Fft(frameSize)
    private val window = DoubleArray(frameSize) { 0.5 - 0.5 * cos(2 * PI * it / (frameSize - 1)) }
    private val re = DoubleArray(frameSize)
    private val im = DoubleArray(frameSize)
    private val prevSpectrum = DoubleArray(frameSize / 2)
    private var havePrev = false

    private val circular = FloatArray(frameSize)
    private var writePos = 0
    private var filled = 0
    private var sinceHop = 0
    private var decimAcc = 0f
    private var decimCount = 0

    private val envCapacity = (windowSeconds * framesPerSecond).toInt()
    private val envelope = DoubleArray(envCapacity)
    private var envWrite = 0
    private var envCount = 0

    /** Loudness of the latest frame, 0..1 (rough, for a level meter). */
    var level = 0.0
        private set

    var diagnostics = ""

    /** Total onset frames analysed since [reset]. */
    var framesAnalysed = 0L
        private set

    fun reset() {
        writePos = 0; filled = 0; sinceHop = 0; decimAcc = 0f; decimCount = 0
        envWrite = 0; envCount = 0; havePrev = false; level = 0.0; framesAnalysed = 0
        prevSpectrum.fill(0.0)
    }

    /** Feed 16-bit PCM mono samples as they arrive from the microphone. */
    fun feed(samples: ShortArray, count: Int) {
        for (i in 0 until count) {
            val s = samples[i] / 32768f
            if (decimation == 2) {
                decimAcc += s
                if (++decimCount < 2) continue
                push(decimAcc / 2f)
                decimAcc = 0f
                decimCount = 0
            } else {
                push(s)
            }
        }
    }

    private fun push(sample: Float) {
        circular[writePos] = sample
        writePos = (writePos + 1) % frameSize
        if (filled < frameSize) filled++
        if (++sinceHop >= hop && filled == frameSize) {
            sinceHop = 0
            analyseFrame()
        }
    }

    private fun analyseFrame() {
        var energy = 0.0
        for (i in 0 until frameSize) {
            val s = circular[(writePos + i) % frameSize].toDouble()
            energy += s * s
            re[i] = s * window[i]
            im[i] = 0.0
        }
        level = min(1.0, sqrt(energy / frameSize) * 4)
        fft.transform(re, im)

        var flux = 0.0
        for (k in 1 until frameSize / 2) {
            val mag = ln(1.0 + 100.0 * hypot(re[k], im[k]))
            if (havePrev) flux += max(0.0, mag - prevSpectrum[k])
            prevSpectrum[k] = mag
        }
        havePrev = true

        envelope[envWrite] = flux
        envWrite = (envWrite + 1) % envCapacity
        if (envCount < envCapacity) envCount++
        framesAnalysed++
    }

    /** Onset strength over the analysis window, oldest first. */
    fun envelopeSnapshot(): DoubleArray {
        val out = DoubleArray(envCount)
        val start = (envWrite - envCount + envCapacity) % envCapacity
        for (i in 0 until envCount) out[i] = envelope[(start + i) % envCapacity]
        return out
    }

    /** Seconds of audio currently in the analysis window. */
    val bufferedSeconds: Double get() = envCount / framesPerSecond

    /**
     * Current tempo, folded into [rangeLow, rangeHigh) — rhythm is ambiguous by octaves (a 128 BPM
     * house track also "pulses" at 64), so the caller picks the musical range it expects.
     */
    fun estimate(rangeLow: Double = 80.0, rangeHigh: Double = 160.0): Estimate? {
        val n = envCount
        if (n < framesPerSecond * 4) return null
        val raw = envelopeSnapshot()

        // Remove the slowly varying loudness so only the spikes remain.
        val half = (framesPerSecond * 0.2).toInt()
        val prefix = DoubleArray(n + 1)
        for (i in 0 until n) prefix[i + 1] = prefix[i] + raw[i]
        val d = DoubleArray(n)
        for (t in 0 until n) {
            val a = max(0, t - half)
            val b = min(n, t + half + 1)
            d[t] = max(0.0, raw[t] - (prefix[b] - prefix[a]) / (b - a))
        }

        val minLag = (60.0 * framesPerSecond / MAX_BPM).toInt().coerceAtLeast(2)
        val maxLag = (60.0 * framesPerSecond / MIN_BPM).toInt() + 1
        if (maxLag >= n / 2) return null
        val acfLen = min(n - 1, 3 * maxLag + 2)
        val acf = DoubleArray(acfLen + 1)
        for (lag in 0..acfLen) {
            var s = 0.0
            for (t in 0 until n - lag) s += d[t] * d[t + lag]
            acf[lag] = s / (n - lag)
        }
        if (acf[0] < 1e-9) return null

        fun acfAt(x: Double): Double {
            val i = x.toInt()
            if (i < 0 || i + 1 > acfLen) return 0.0
            val f = x - i
            return acf[i] * (1 - f) + acf[i + 1] * f
        }

        fun score(lag: Double): Double {
            val bpm = 60.0 * framesPerSecond / lag
            val octaves = ln(bpm / 120.0) / ln(2.0)
            val prior = exp(-0.5 * octaves * octaves)
            return (acfAt(lag) + 0.5 * acfAt(2 * lag) + 0.25 * acfAt(3 * lag)) * prior
        }

        // 1) Strongest periodicity on whole lags.
        var bestLag = minLag
        var bestScore = -1.0
        for (lag in minLag..maxLag) {
            val s = score(lag.toDouble())
            if (s > bestScore) { bestScore = s; bestLag = lag }
        }
        val rawBpm = 60.0 * framesPerSecond / bestLag

        // 2) Fold into the requested range, then polish the period on a fine fractional grid.
        val folded = fold(rawBpm, rangeLow, rangeHigh)
        val centre = 60.0 * framesPerSecond / folded
        var lag = centre
        var lagScore = -1.0
        var x = centre - 1.2
        while (x <= centre + 1.2) {
            val s = acfAt(x) + 0.5 * acfAt(2 * x)
            if (s > lagScore) { lagScore = s; lag = x }
            x += 0.01
        }
        val bpm = 60.0 * framesPerSecond / lag

        // Confidence: how far the beat peak stands above the average periodicity, gated by
        // loudness so hum, tones and room noise don't look like a groove.
        val mean = (minLag..maxLag).sumOf { acf[it] } / (maxLag - minLag + 1)
        val contrast = (max(acfAt(lag), acfAt(2 * lag)) - mean) / acf[0]
        val loudness = ((raw.average() - 5.0) / 15.0).coerceIn(0.0, 1.0)
        val confidence = (contrast * 1.6).coerceIn(0.0, 1.0) * loudness
        diagnostics = "acf0=%.4g rawMean=%.4g raw=%.2f folded=%.2f contrast=%.3f".format(
            acf[0], raw.average(), rawBpm, bpm, contrast)

        // 3) Beat phase: where a comb with this period lines up best with the most recent onsets.
        var bestPhase = 0
        var bestPhaseScore = -1.0
        val recent = min(n, (framesPerSecond * 6).toInt())
        for (phase in 0 until lag.toInt()) {
            var s = 0.0
            var pos = n - 1 - phase.toDouble()
            while (pos >= n - recent) {
                val i = pos.toInt()
                s += d[i] + 0.5 * (if (i > 0) d[i - 1] else 0.0) + 0.5 * (if (i < n - 1) d[i + 1] else 0.0)
                pos -= lag
            }
            if (s > bestPhaseScore) { bestPhaseScore = s; bestPhase = phase }
        }

        return Estimate(
            bpm = bpm,
            confidence = confidence,
            periodSec = lag / framesPerSecond,
            lastBeatAgoSec = bestPhase / framesPerSecond,
        )
    }

    companion object {
        private const val MIN_BPM = 50.0
        private const val MAX_BPM = 220.0

        /** Brings a tempo into [low, high) by doubling or halving, e.g. 64 → 128 for a dance range. */
        fun fold(bpm: Double, low: Double, high: Double): Double {
            var b = bpm
            while (b < low) b *= 2
            while (b >= high) b /= 2
            return b
        }
    }
}
