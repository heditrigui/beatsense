package com.beatsense.app.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Musical key detection from raw samples (the web app's key.js is a line-by-line port).
 *
 * 1. The audio is low-passed and decimated to about 11 kHz, where a 4096-point FFT resolves
 *    semitones down to the bass register.
 * 2. Every frame's spectrum between 65 Hz and 2.1 kHz is folded into a 12-bin chromagram
 *    (how much C, C♯, D… is sounding), normalised so loud and quiet passages count alike.
 * 3. The chromagram, summed over time, is correlated with the Krumhansl-Kessler key profiles
 *    rotated to all 24 major and minor keys. The best match is the key.
 *
 * [memorySeconds] is how long the chromagram remembers: about 30 s for live listening so it can
 * follow a key change, 0 to weigh a whole file equally.
 */
class KeyDetector(inputRate: Int, memorySeconds: Double = 30.0) {

    data class Estimate(
        /** Pitch class of the tonic, 0 = C … 11 = B. */
        val tonic: Int,
        val minor: Boolean,
        /** Pearson correlation with the best key profile, -1..1. */
        val correlation: Double,
        /** 0..1 — a clear best match that stands out from the next key. */
        val confidence: Double,
    ) {
        val name: String get() = keyName(tonic, minor)
        val camelot: String get() = camelot(tonic, minor)
    }

    private val decimation = max(1, inputRate / 11025)
    private val rate = inputRate.toDouble() / decimation
    private val frameSize = 4096
    private val hop = 2048
    private val decay = if (memorySeconds > 0) exp(-hop / rate / memorySeconds) else 1.0

    // 2nd-order Butterworth low-pass (RBJ cookbook), run twice for a steeper anti-alias filter.
    private val b0: Double
    private val b1: Double
    private val b2: Double
    private val a1: Double
    private val a2: Double
    init {
        val w0 = 2 * PI * 2500 / inputRate
        val alpha = sin(w0) / (2 * sqrt(0.5))
        val a0 = 1 + alpha
        val c = cos(w0)
        b0 = (1 - c) / 2 / a0; b1 = (1 - c) / a0; b2 = (1 - c) / 2 / a0; a1 = -2 * c / a0; a2 = (1 - alpha) / a0
    }

    private val fft = Fft(frameSize)
    private val window = DoubleArray(frameSize) { 0.5 - 0.5 * cos(2 * PI * it / (frameSize - 1)) }
    private val re = DoubleArray(frameSize)
    private val im = DoubleArray(frameSize)
    private val circular = FloatArray(frameSize)

    // Each FFT bin in range feeds its nearest pitch class, weighted by how close it is to the note.
    private val binFrom = kotlin.math.ceil(65.0 * frameSize / rate).toInt()
    private val binTo = (2100.0 * frameSize / rate).toInt()
    private val binClass = IntArray(binTo + 1)
    private val binWeight = DoubleArray(binTo + 1)
    init {
        for (k in binFrom..binTo) {
            val midi = 69 + 12 * ln(k * rate / frameSize / 440) / ln(2.0)
            val nearest = midi.roundToInt()
            binClass[k] = nearest.mod(12)
            binWeight[k] = cos(PI * (midi - nearest)).pow(2)
        }
    }

    private val chroma = DoubleArray(12)
    private val frame = DoubleArray(12)
    private var writePos = 0
    private var filled = 0
    private var sinceHop = 0
    private var decimCount = 0
    private val z = DoubleArray(8) // x1, x2, y1, y2 for each of the two filter stages

    /** Frames with enough sound to count, since [reset]. */
    var tonalFrames = 0
        private set

    fun reset() {
        writePos = 0; filled = 0; sinceHop = 0; decimCount = 0
        z.fill(0.0); chroma.fill(0.0); tonalFrames = 0
    }

    /** Feed 16-bit PCM mono samples as they arrive. */
    fun feed(samples: ShortArray, count: Int) {
        for (i in 0 until count) {
            var x = samples[i] / 32768.0
            var s = 0
            while (s < 8) {
                val y = b0 * x + b1 * z[s] + b2 * z[s + 1] - a1 * z[s + 2] - a2 * z[s + 3]
                z[s + 1] = z[s]; z[s] = x; z[s + 3] = z[s + 2]; z[s + 2] = y
                x = y
                s += 4
            }
            if (++decimCount < decimation) continue
            decimCount = 0
            push(x.toFloat())
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
        for (i in 0 until 12) chroma[i] *= decay
        if (sqrt(energy / frameSize) < 0.003) return // silence says nothing about the key
        fft.transform(re, im)

        frame.fill(0.0)
        var total = 0.0
        for (k in binFrom..binTo) {
            val v = hypot(re[k], im[k]) * binWeight[k]
            frame[binClass[k]] += v
            total += v
        }
        if (total <= 0) return
        for (i in 0 until 12) chroma[i] += frame[i] / total
        tonalFrames++
    }

    /** Seconds of sound that went into the chromagram. */
    val tonalSeconds: Double get() = tonalFrames * hop / rate

    /** The most likely key, or null until there are a few seconds of sound. */
    fun estimate(): Estimate? = if (tonalSeconds < 4) null else matchKey(chroma)

    companion object {
        // Krumhansl & Kessler (1982): how well each scale degree fits a major or a minor key.
        val MAJOR_PROFILE = doubleArrayOf(6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88)
        val MINOR_PROFILE = doubleArrayOf(6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17)
        private val MAJOR_NAMES = listOf("C", "D♭", "D", "E♭", "E", "F", "F♯", "G", "A♭", "A", "B♭", "B")
        private val MINOR_NAMES = listOf("C", "C♯", "D", "E♭", "E", "F", "F♯", "G", "G♯", "A", "B♭", "B")

        /** Camelot wheel code for a key, e.g. A minor → "8A", C major → "8B" (DJs mix neighbours). */
        fun camelot(tonic: Int, minor: Boolean): String {
            val major = if (minor) (tonic + 3) % 12 else tonic // a minor key sits with its relative major
            return "${(major * 7 + 7) % 12 + 1}${if (minor) "A" else "B"}"
        }

        fun keyName(tonic: Int, minor: Boolean): String =
            "${(if (minor) MINOR_NAMES else MAJOR_NAMES)[tonic]} ${if (minor) "minor" else "major"}"

        /** Pearson correlation of a chromagram with a profile rotated to start on [tonic]. */
        private fun correlate(chroma: DoubleArray, profile: DoubleArray, tonic: Int): Double {
            val mc = chroma.average()
            val mp = profile.average()
            var num = 0.0; var dc = 0.0; var dp = 0.0
            for (i in 0 until 12) {
                val c = chroma[(i + tonic) % 12] - mc
                val p = profile[i] - mp
                num += c * p; dc += c * c; dp += p * p
            }
            return if (dc > 0) num / sqrt(dc * dp) else 0.0
        }

        /** Best of the 24 keys for a chromagram, plus how far it beats the runner-up. */
        fun matchKey(chroma: DoubleArray): Estimate {
            val scores = (0 until 12).flatMap { t ->
                listOf(Triple(t, false, correlate(chroma, MAJOR_PROFILE, t)), Triple(t, true, correlate(chroma, MINOR_PROFILE, t)))
            }.sortedByDescending { it.third }
            val (tonic, minor, r) = scores[0]
            return Estimate(
                tonic = tonic,
                minor = minor,
                correlation = r,
                confidence = ((r - 0.4) / 0.4).coerceIn(0.0, 1.0) * min(1.0, (r - scores[1].third) * 10),
            )
        }
    }
}
