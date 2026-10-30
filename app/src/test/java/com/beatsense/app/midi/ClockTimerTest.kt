package com.beatsense.app.midi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** MIDI clock timing. Same case as the clock test in web-tests/track.test.js. */
class ClockTimerTest {

    @Test fun ticks24TimesPerBeatAndBendsOnTempoChanges() {
        val c = ClockTimer()
        assertTrue(c.due(1000.0, 120.0).isEmpty()) // not started
        c.start(100.0)
        val beat = c.due(600.0, 120.0) // one beat at 120 BPM is 500 ms
        assertEquals(24, beat.size)
        assertEquals(100.0, beat[0], 0.0)
        assertEquals(500.0 / 24, beat[1] - beat[0], 1e-9)
        val faster = c.due(1100.0, 150.0) // 400 ms beats from the next tick on
        assertEquals(600.0, faster[0], 1e-6)
        assertEquals(400.0 / 24, faster[1] - faster[0], 1e-9)
        assertTrue(c.due(5000.0, null).isEmpty()) // no tempo: wait
        c.stop()
        assertFalse(c.running)
    }
}
