package com.cinemind.core.ui.format

import org.junit.Assert.assertEquals
import org.junit.Test

class FormattersTest {

    @Test
    fun durationFormatting() {
        assertEquals("0:00", formatDuration(0))
        assertEquals("0:00", formatDuration(-1))
        assertEquals("0:09", formatDuration(9_999_999))
        assertEquals("2:05", formatDuration(125_000_000))
        assertEquals("1:00:01", formatDuration(3_601_000_000))
    }
}
