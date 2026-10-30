package com.beatsense.app.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Feeds synthetic songs in a known key through the detector. Same cases as web-tests/key.test.js. */
class KeyDetectorTest {

    /** A drum loop with chords in a key on top, the way a song would sound. */
    private fun song(tonic: Int, minor: Boolean, seconds: Double = 20.0, rate: Int = 44100, bpm: Double = 112.0): ShortArray {
        val out = TestAudio.drums(bpm, seconds, TestAudio.house, rate = rate, seed = tonic + 1, pad = false)
        TestAudio.addChords(out, tonic, minor, bpm, rate)
        return TestAudio.pcm(out)
    }

    private fun detect(pcm: ShortArray, rate: Int = 44100, memorySeconds: Double = 0.0): KeyDetector.Estimate? {
        val det = KeyDetector(rate, memorySeconds)
        val chunk = ShortArray(2048)
        var pos = 0
        while (pos < pcm.size) {
            val n = minOf(chunk.size, pcm.size - pos)
            System.arraycopy(pcm, pos, chunk, 0, n)
            det.feed(chunk, n)
            pos += n
        }
        return det.estimate()
    }

    @Test fun keysAcrossTheCircle() {
        val cases = listOf(
            Triple(0, false, 44100), Triple(9, true, 44100), Triple(6, false, 44100), Triple(3, true, 44100),
            Triple(7, false, 48000), Triple(11, true, 48000), Triple(10, false, 48000), Triple(5, true, 48000),
        )
        for ((tonic, minor, rate) in cases) {
            val e = detect(song(tonic, minor, rate = rate), rate)
            assertNotNull(e)
            println("${KeyDetector.keyName(tonic, minor)} @ $rate -> ${e!!.name} ${e.camelot} (r %.3f, confidence %.2f)".format(e.correlation, e.confidence))
            assertEquals(KeyDetector.keyName(tonic, minor), e.name)
            assertTrue("confidence ${e.confidence}", e.confidence > 0.5)
        }
    }

    @Test fun drumsAloneHaveNoConfidentKey() {
        val e = detect(TestAudio.pcm(TestAudio.drums(120.0, 15.0, TestAudio.house, pad = false)))
        assertTrue("confidence ${e?.confidence}", e == null || e.confidence < 0.2)
    }

    @Test fun silenceGivesNoKey() {
        assertNull(detect(ShortArray(44100 * 10)))
    }

    @Test fun liveMemoryFollowsAKeyChange() {
        val a = song(0, false, 20.0)
        val b = song(4, true, 40.0)
        assertEquals("E minor", detect(a + b, memorySeconds = 15.0)!!.name)
    }

    @Test fun camelotCodes() {
        assertEquals("8A", KeyDetector.camelot(9, true)) // A minor
        assertEquals("8B", KeyDetector.camelot(0, false)) // C major
        assertEquals("9B", KeyDetector.camelot(7, false)) // G major
        assertEquals("9A", KeyDetector.camelot(4, true)) // E minor
        assertEquals("1B", KeyDetector.camelot(11, false)) // B major
        assertEquals("1A", KeyDetector.camelot(8, true)) // G♯ minor
        assertEquals("7B", KeyDetector.camelot(5, false)) // F major
        assertEquals("7A", KeyDetector.camelot(2, true)) // D minor
        val all = (0 until 12).flatMap { t -> listOf(KeyDetector.camelot(t, false), KeyDetector.camelot(t, true)) }.toSet()
        assertEquals(24, all.size)
    }
}
