package com.beatsense.app.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/** Feeds synthetic songs with a known tempo through the detector, the same way the microphone does. */
class TempoDetectorTest {

    private val rate = 44100

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

    private val house = TestAudio.house
    private val hipHop = TestAudio.hipHop
    private val dnb = TestAudio.dnb
    private fun song(bpm: Double, seconds: Double, pattern: List<Pair<Double, TestAudio.Hit>>, jitterMs: Double = 0.0, seed: Int = 1) =
        TestAudio.song(bpm, seconds, pattern, jitterMs, seed)

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
        val ballad = listOf(0.0 to TestAudio.Hit.KICK, 2.0 to TestAudio.Hit.SNARE) + (0 until 4).map { it.toDouble() to TestAudio.Hit.HAT }
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
