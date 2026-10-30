package com.beatsense.app.dsp

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/** Synthetic songs with a known tempo and key, shared by the detector tests. */
object TestAudio {

    enum class Hit { KICK, SNARE, HAT }

    val house = listOf(
        0.0 to Hit.KICK, 1.0 to Hit.KICK, 2.0 to Hit.KICK, 3.0 to Hit.KICK,
        1.0 to Hit.SNARE, 3.0 to Hit.SNARE,
        0.5 to Hit.HAT, 1.5 to Hit.HAT, 2.5 to Hit.HAT, 3.5 to Hit.HAT,
    )
    val hipHop = listOf(
        0.0 to Hit.KICK, 1.5 to Hit.KICK, 2.0 to Hit.KICK,
        1.0 to Hit.SNARE, 3.0 to Hit.SNARE,
    ) + (0 until 8).map { it * 0.5 to Hit.HAT }
    val dnb = listOf(
        0.0 to Hit.KICK, 2.5 to Hit.KICK,
        1.0 to Hit.SNARE, 3.0 to Hit.SNARE,
    ) + (0 until 8).map { it * 0.5 to Hit.HAT }

    /**
     * Renders a drum loop. [pattern] lists (position in beats within a 4-beat bar, instrument).
     * With [pad], a sustained bass/pad drone and room noise are added: sound with no rhythm of its own.
     */
    fun drums(
        bpm: Double,
        seconds: Double,
        pattern: List<Pair<Double, Hit>>,
        rate: Int = 44100,
        jitterMs: Double = 0.0,
        seed: Int = 1,
        pad: Boolean = true,
    ): DoubleArray {
        val rnd = Random(seed)
        val out = DoubleArray((seconds * rate).toInt())
        val beat = 60.0 / bpm
        var bar = 0
        while (bar * 4 * beat < seconds) {
            for ((pos, hit) in pattern) {
                val t = (bar * 4 + pos) * beat + (rnd.nextDouble() * 2 - 1) * jitterMs / 1000
                val start = (t * rate).toInt()
                if (start < 0 || start >= out.size) continue
                when (hit) {
                    Hit.KICK -> for (i in 0 until (0.25 * rate).toInt()) {
                        val tt = i.toDouble() / rate
                        val f = 50 + 70 * exp(-tt * 30)
                        if (start + i < out.size) out[start + i] += 0.8 * sin(2 * PI * f * tt) * exp(-tt * 14)
                    }
                    Hit.SNARE -> for (i in 0 until (0.18 * rate).toInt()) {
                        val tt = i.toDouble() / rate
                        val v = (rnd.nextDouble() * 2 - 1) * 0.45 + 0.2 * sin(2 * PI * 190 * tt)
                        if (start + i < out.size) out[start + i] += v * exp(-tt * 22)
                    }
                    Hit.HAT -> {
                        var prev = 0.0
                        for (i in 0 until (0.05 * rate).toInt()) {
                            val n = rnd.nextDouble() * 2 - 1
                            if (start + i < out.size) out[start + i] += (n - prev) * 0.15 * exp(-i.toDouble() / rate * 70)
                            prev = n
                        }
                    }
                }
            }
            bar++
        }
        for (i in out.indices) {
            val t = i.toDouble() / rate
            if (pad) out[i] += 0.08 * sin(2 * PI * 55 * t) + 0.05 * sin(2 * PI * 220 * t) + 0.04 * sin(2 * PI * 330 * t)
            out[i] += (rnd.nextDouble() * 2 - 1) * 0.02
        }
        return out
    }

    /**
     * Adds chords in a key to [out]: one chord per bar (I IV V I vi IV V I, or the minor-key
     * equivalent with a major V), each a triad plus the root doubled in the bass, with a few
     * harmonics so it sounds like an instrument. Same synthesis as the web app's chordTrack().
     */
    fun addChords(out: DoubleArray, tonic: Int, minor: Boolean, bpm: Double, rate: Int = 44100, level: Double = 0.12) {
        val scale = if (minor) intArrayOf(0, 2, 3, 5, 7, 8, 11) else intArrayOf(0, 2, 4, 5, 7, 9, 11)
        val prog = intArrayOf(0, 3, 4, 0, 5, 3, 4, 0)
        val bar = 4 * 60.0 / bpm
        val seconds = out.size.toDouble() / rate
        var b = 0
        while (b * bar < seconds) {
            val degree = prog[b % prog.size]
            val notes = (0..4 step 2).map { step ->
                val idx = degree + step
                60 + tonic + scale[idx % 7] + 12 * (idx / 7) - if (tonic > 5) 12 else 0
            }.let { it + (it[0] - 12) + (it[0] - 24) }
            val start = (b * bar * rate).toInt()
            val end = min(out.size, ((b + 1) * bar * rate).toInt())
            for (note in notes) {
                val w = 2 * PI * 440 * 2.0.pow((note - 69) / 12.0) / rate
                val amp = level / notes.size
                for (i in start until end) {
                    val t = (i - start).toDouble() / rate
                    val ph = w * i
                    val tone = sin(ph) + sin(2 * ph) / 2 + sin(3 * ph) / 3 + sin(4 * ph) / 4
                    out[i] += amp * tone * exp(-t * 0.6) * min(1.0, t * 50)
                }
            }
            b++
        }
    }

    fun pcm(out: DoubleArray): ShortArray = ShortArray(out.size) { (out[it].coerceIn(-1.0, 1.0) * 32767).toInt().toShort() }

    /** A drum loop with or without the drone, as 16-bit PCM — what the tempo tests feed the detector. */
    fun song(bpm: Double, seconds: Double, pattern: List<Pair<Double, Hit>>, jitterMs: Double = 0.0, seed: Int = 1): ShortArray =
        pcm(drums(bpm, seconds, pattern, jitterMs = jitterMs, seed = seed))
}
