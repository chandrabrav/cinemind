package com.cinemind.core.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test

class TimecodeTest {

    @Test
    fun zeroIsAllZeros() {
        assertEquals("00:00:00:00", formatTimecode(0))
    }

    @Test
    fun hoursMinutesSecondsAndFrames() {
        // 1h 2m 3s + 0.5s at 30 fps = frame 15
        val us = ((3600 + 2 * 60 + 3) * 1_000_000L) + 500_000L
        assertEquals("01:02:03:15", formatTimecode(us))
    }

    @Test
    fun lastFrameOfSecondNeverRollsOverToFps() {
        // 999_999 µs is still inside second 0; must not print frame 30.
        assertEquals("00:00:00:29", formatTimecode(999_999))
    }

    @Test
    fun respectsFrameRate() {
        assertEquals("00:00:01:30", formatTimecode(1_500_000, fps = 60))
    }

    @Test
    fun negativeInputClampsToZero() {
        assertEquals("00:00:00:00", formatTimecode(-5))
    }
}
