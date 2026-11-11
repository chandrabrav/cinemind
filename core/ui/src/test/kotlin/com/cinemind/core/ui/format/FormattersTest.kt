package com.cinemind.core.ui.format

import org.junit.Assert.assertEquals
import org.junit.Test

class FormattersTest {

    @Test
    fun shortTime() {
        assertEquals("0:00.0", formatShortTime(0))
        assertEquals("0:03.2", formatShortTime(3_250_000))
        assertEquals("12:05.0", formatShortTime(725_000_000))
        assertEquals("0:00.0", formatShortTime(-5))
    }

    @Test
    fun durationFormatting() {
        assertEquals("0:00", formatDuration(0))
        assertEquals("0:00", formatDuration(-1))
        assertEquals("0:09", formatDuration(9_999_999))
        assertEquals("2:05", formatDuration(125_000_000))
        assertEquals("1:00:01", formatDuration(3_601_000_000))
    }
}
