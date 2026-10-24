package com.beatsense.app.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/** Feeds synthetic songs with a known tempo through the detector, the same way the microphone does. */
class TempoDetectorTest {

    private val rate = 44100

    private enum class Hit { KICK, SNARE, HAT }

    /** Renders a drum loop. [pattern] lists (position in beats within a 4-beat bar, instrument). */
    private fun song(
        bpm: Double,
        seconds: Double,
        pattern: List<Pair<Double, Hit>>,
        jitterMs: Double = 0.0,
        seed: Int = 1,
    ): ShortArray {
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
        // A sustained bass/pad chord and room noise: sound that has no rhythm of its own.
        for (i in out.indices) {
            val t = i.toDouble() / rate
            out[i] += 0.08 * sin(2 * PI * 55 * t) + 0.05 * sin(2 * PI * 220 * t) + 0.04 * sin(2 * PI * 330 * t)
            out[i] += (rnd.nextDouble() * 2 - 1) * 0.02
        }
        return ShortArray(out.size) { (out[it].coerceIn(-1.0, 1.0) * 32767).toInt().toShort() }
    }

    private fun detect(pcm: ShortArray, low: Double = 80.0, high: Double = 160.0): TempoDetector.Estimate? {
        val det = TempoDetector(rate)
        val chunk = ShortArray(2048)
        var pos = 0
        while (pos < pcm.size) {
            val n = minOf(chunk.size, pcm.size - pos)
            System.arraycopy(pcm, pos, chunk, 0, n)
            det.feed(chunk, n)
            pos += n
        }
        return det.estimate(low, high).also { println("  diag: " + det.diagnostics) }
    }

    private fun assertTempo(expected: Double, actual: Double, tolerance: Double = 1.0) {
        assertTrue("expected $expected ± $tolerance BPM but got $actual", abs(expected - actual) <= tolerance)
    }

    private val house = listOf(
        0.0 to Hit.KICK, 1.0 to Hit.KICK, 2.0 to Hit.KICK, 3.0 to Hit.KICK,
        1.0 to Hit.SNARE, 3.0 to Hit.SNARE,
        0.5 to Hit.HAT, 1.5 to Hit.HAT, 2.5 to Hit.HAT, 3.5 to Hit.HAT,
    )
    private val hipHop = listOf(
        0.0 to Hit.KICK, 1.5 to Hit.KICK, 2.0 to Hit.KICK,
        1.0 to Hit.SNARE, 3.0 to Hit.SNARE,
    ) + (0 until 8).map { it * 0.5 to Hit.HAT }
    private val dnb = listOf(
        0.0 to Hit.KICK, 2.5 to Hit.KICK,
        1.0 to Hit.SNARE, 3.0 to Hit.SNARE,
    ) + (0 until 8).map { it * 0.5 to Hit.HAT }

    @Test fun houseAt128() {
        val e = detect(song(128.0, 14.0, house))
        assertNotNull(e)
        println("house 128 -> %.2f (confidence %.2f)".format(e!!.bpm, e.confidence))
        assertTempo(128.0, e.bpm)
        assertTrue("confidence ${e.confidence}", e.confidence > 0.3)
    }

    @Test fun hipHopAt92() {
        val e = detect(song(92.0, 14.0, hipHop))!!
        println("hip-hop 92 -> %.2f (confidence %.2f)".format(e.bpm, e.confidence))
        assertTempo(92.0, e.bpm)
    }

    @Test fun drumAndBassAt174FoldsIntoFastRange() {
        val e = detect(song(174.0, 14.0, dnb), 110.0, 220.0)!!
        println("dnb 174 -> %.2f".format(e.bpm))
        assertTempo(174.0, e.bpm)
    }

    @Test fun oddTempoWithHumanTiming() {
        val e = detect(song(103.0, 14.0, house, jitterMs = 8.0, seed = 7))!!
        println("live-feel 103 -> %.2f (confidence %.2f)".format(e.bpm, e.confidence))
        assertTempo(103.0, e.bpm)
    }

    @Test fun slowBallad() {
        val ballad = listOf(0.0 to Hit.KICK, 2.0 to Hit.SNARE) + (0 until 4).map { it.toDouble() to Hit.HAT }
        val e = detect(song(72.0, 14.0, ballad), 60.0, 120.0)!!
        println("ballad 72 -> %.2f".format(e.bpm))
        assertTempo(72.0, e.bpm)
    }

    @Test fun manyTemposAcrossTheRange() {
        for (bpm in listOf(82.0, 85.0, 100.0, 115.0, 124.0, 135.0, 140.0, 150.0, 158.0)) {
            val e = detect(song(bpm, 13.0, house, seed = bpm.toInt()))!!
            println("house $bpm -> %.2f (confidence %.2f)".format(e.bpm, e.confidence))
            assertTempo(bpm, e.bpm)
        }
    }

    @Test fun beatPhasePointsAtTheLastKick() {
        val bpm = 120.0
        val pcm = song(bpm, 12.25, house) // ends a quarter beat after a kick
        val e = detect(pcm)!!
        val beat = 60.0 / bpm
        val expectedAgo = 12.25 % beat
        println("phase: last beat %.3fs ago (expected ~%.3f)".format(e.lastBeatAgoSec, expectedAgo))
        // Kick onsets are detected within a frame or two, and the analysis frame adds ~25 ms of latency.
        assertEquals(expectedAgo, e.lastBeatAgoSec, 0.06)
    }

    @Test fun silenceGivesNoTempo() {
        assertNull(detect(ShortArray(rate * 10)))
    }

    @Test fun tooShortGivesNoTempo() {
        assertNull(detect(song(120.0, 2.0, house)))
    }

    @Test fun steadyToneHasLowConfidence() {
        val tone = ShortArray(rate * 10) { (sin(2 * PI * 440 * it / rate) * 12000).toInt().toShort() }
        val e = detect(tone)
        println("steady tone -> ${e?.let { "%.1f bpm, confidence %.2f".format(it.bpm, it.confidence) }}")
        assertTrue(e == null || e.confidence < 0.25)
    }

    @Test fun foldKeepsTempoInRange() {
        assertEquals(128.0, TempoDetector.fold(64.0, 80.0, 160.0), 1e-9)
        assertEquals(87.0, TempoDetector.fold(174.0, 60.0, 120.0), 1e-9)
        assertEquals(140.0, TempoDetector.fold(140.0, 100.0, 200.0), 1e-9)
    }
}
