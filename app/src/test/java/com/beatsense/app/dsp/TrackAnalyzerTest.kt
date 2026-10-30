package com.beatsense.app.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Whole-file analysis on synthetic tracks. Same cases as web-tests/track.test.js. */
class TrackAnalyzerTest {

    private fun analyse(pcm: ShortArray, rate: Int, chunk: Int = 4096): TrackAnalyzer.Result {
        val a = TrackAnalyzer(rate)
        val buf = ShortArray(chunk)
        var pos = 0
        while (pos < pcm.size) {
            val n = minOf(chunk, pcm.size - pos)
            System.arraycopy(pcm, pos, buf, 0, n)
            a.feed(buf, n)
            pos += n
        }
        return a.result()
    }

    private fun assertTempo(expected: Double, actual: Double?) =
        assertTrue("expected $expected but got $actual", actual != null && abs(expected - actual) <= 1.0)

    @Test fun wholeTrackGivesTempoKeyAndCurve() {
        val out = TestAudio.drums(124.0, 30.0, TestAudio.house, pad = false)
        TestAudio.addChords(out, 2, true, 124.0)
        val r = analyse(TestAudio.pcm(out), 44100)
        assertTempo(124.0, r.bpm)
        assertEquals("D minor", r.key?.name)
        assertEquals("7A", r.key?.camelot)
        assertEquals(30.0, r.durationSec, 0.01)
        assertEquals(listOf(6.0, 9.0, 12.0, 15.0, 18.0, 21.0, 24.0, 27.0, 30.0), r.curve.map { it.timeSec })
    }

    @Test fun resultDoesNotDependOnChunking() {
        val pcm = TestAudio.pcm(TestAudio.drums(96.0, 16.0, TestAudio.hipHop, rate = 48000))
        val a = analyse(pcm, 48000, 1000)
        val b = analyse(pcm, 48000, 8192)
        assertEquals(a.curve, b.curve)
        assertTempo(96.0, a.bpm)
    }

    @Test fun driftingTempoShowsInTheCurve() {
        val pcm = TestAudio.song(110.0, 20.0, TestAudio.house) + TestAudio.song(130.0, 20.0, TestAudio.house)
        val r = analyse(pcm, 44100)
        assertTempo(110.0, r.curve.first { it.timeSec == 18.0 }.bpm)
        assertTempo(130.0, r.curve.last().bpm)
    }

    @Test fun shortFileStillGetsOneEstimate() {
        val r = analyse(TestAudio.song(120.0, 5.0, TestAudio.house), 44100)
        assertEquals(1, r.curve.size)
        assertTempo(120.0, r.bpm)
    }

    @Test fun silenceHasNoTempoAndNoKey() {
        val r = analyse(ShortArray(44100 * 8), 44100)
        assertNull(r.bpm)
        assertNull(r.key)
    }

    @Test fun csvQuotesAwkwardFileNames() {
        val key = KeyDetector.Estimate(tonic = 9, minor = true, correlation = 0.9, confidence = 0.9)
        val csv = TrackAnalyzer.toCsv(
            listOf(
                "Song, \"live\".mp3" to TrackAnalyzer.Result(201.04, 127.96, 0.812, key, emptyList()),
                "noise.wav" to TrackAnalyzer.Result(3.0, null, 0.0, null, emptyList()),
                "broken.mp3" to null,
            ),
        )
        assertEquals(
            "file,duration_s,bpm,confidence,key,camelot,key_confidence\r\n" +
                "\"Song, \"\"live\"\".mp3\",201.0,128.0,0.81,A minor,8A,0.90\r\n" +
                "noise.wav,3.0,,,,,\r\n" +
                "broken.mp3,,,,,,\r\n",
            csv,
        )
    }
}
