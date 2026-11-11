package com.cinemind.feature.editor.timeline.preview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineLayoutTest {

    @Test
    fun timeAndPixelsRoundTrip() {
        assertEquals(150f, TimelineLayout.xForUs(1_500_000, 100f), 0.001f)
        assertEquals(1_500_000L, TimelineLayout.usForX(150f, 100f))
        assertEquals(0L, TimelineLayout.usForX(-20f, 100f))
        assertEquals(0L, TimelineLayout.usForX(50f, 0f))
    }

    @Test
    fun clipsHaveAMinimumWidth() {
        assertEquals(24f, TimelineLayout.widthForUs(10_000, 100f, minPx = 24f), 0.001f)
        assertEquals(300f, TimelineLayout.widthForUs(3_000_000, 100f, minPx = 24f), 0.001f)
    }

    @Test
    fun tickSpacingAdaptsToZoom() {
        // Zoomed in (300 px/s): half-second ticks are 150 px apart → 0.5 s.
        assertEquals(500_000L, TimelineLayout.tickIntervalUs(300f, minTickPx = 120f))
        // Zoomed out (20 px/s): need 10 s to reach 150 px.
        assertEquals(10_000_000L, TimelineLayout.tickIntervalUs(20f, minTickPx = 150f))
        // Extremely zoomed out falls back to a minute.
        assertEquals(60_000_000L, TimelineLayout.tickIntervalUs(0.1f, minTickPx = 150f))
    }

    @Test
    fun ticksCoverTheTimeline() {
        assertEquals(listOf(0L, 2_000_000L, 4_000_000L, 6_000_000L), TimelineLayout.ticks(7_000_000, 2_000_000))
        assertTrue(TimelineLayout.ticks(1_000, 0).isEmpty())
    }

    @Test
    fun labels() {
        assertEquals("0s", TimelineLayout.label(0))
        assertEquals("1.5s", TimelineLayout.label(1_500_000))
        assertEquals("12s", TimelineLayout.label(12_000_000))
        assertEquals("1:05", TimelineLayout.label(65_000_000))
    }

    @Test
    fun followScrollKeepsPlayheadNearTheLeftThirdAndClamps() {
        assertEquals(600, TimelineLayout.followScroll(playheadX = 1_000f, viewportPx = 1_000f, contentPx = 5_000f, anchor = 0.4f))
        assertEquals(0, TimelineLayout.followScroll(100f, 1_000f, 5_000f))
        assertEquals(4_000, TimelineLayout.followScroll(4_990f, 1_000f, 5_000f))
        assertEquals(0, TimelineLayout.followScroll(300f, 1_000f, 800f))
    }
}
