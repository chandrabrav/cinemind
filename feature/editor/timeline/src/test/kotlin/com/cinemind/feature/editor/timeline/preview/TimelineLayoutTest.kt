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

    @Test
    fun pinchKeepsTheTimeUnderTheFingers() {
        // 100 px/s, scrolled 500 px, fingers at x=300, lead 40 → time = (500+300-40)/100 = 7.6 s.
        val newScroll = TimelineLayout.scrollAfterZoom(500f, 300f, 40f, 100f, 250f)
        val timeAfter = (newScroll + 300f - 40f) / 250f
        assertEquals(7.6f, timeAfter, 0.0001f)
        // Never scrolls before the start.
        assertEquals(0f, TimelineLayout.scrollAfterZoom(0f, 10f, 40f, 100f, 10f), 0.0001f)
    }

    @Test
    fun zoomRangeFitsProjectAndStopsAtFrames() {
        val density = 3f
        val r = TimelineLayout.zoomRange(totalUs = 10_000_000, fps = 30, density = density, viewportPx = 1_080f, leadPx = 48f)
        // Fit: (1080 - 96) / 10 s = 98.4 px/s; min is the smaller of that and 2 dp/s.
        assertEquals(6f, r.start, 0.001f)
        // Max: 28 dp per frame × 30 fps × 3 = 2520 px/s (10 s × 2520 = 25,200 px, under the layout cap).
        assertEquals(2_520f, r.endInclusive, 0.001f)
        // A 10-minute project is capped by the layout limit instead.
        val long = TimelineLayout.zoomRange(600_000_000, 30, density, 1_080f, 48f)
        assertTrue(long.endInclusive * 600 <= TimelineLayout.MAX_CONTENT_PX)
        assertTrue(long.endInclusive >= long.start)
        assertEquals(98.4f, TimelineLayout.fitPxPerSecond(10_000_000, 1_080f, 48f), 0.001f)
    }

    @Test
    fun rulerGoesDownToFrames() {
        // 2520 px/s at 30 fps: one frame = 84 px ≥ 60 px → every frame.
        assertEquals(TimelineLayout.RulerStep(1, 33_333), TimelineLayout.rulerStep(2_520f, 60f, 30))
        // 500 px/s: 5 frames = 83 px.
        assertEquals(5, TimelineLayout.rulerStep(500f, 60f, 30).frames)
        // 100 px/s: frames too dense → time ticks (1 s).
        val coarse = TimelineLayout.rulerStep(100f, 60f, 30)
        assertTrue(!coarse.isFrames)
        assertEquals(1_000_000L, coarse.us)
    }

    @Test
    fun frameTicksAreExactAndOnlyVisible() {
        val step = TimelineLayout.RulerStep(1, 33_333)
        val ticks = TimelineLayout.ticksIn(step, 30, fromUs = 990_000, toUs = 1_100_000, totalUs = 5_000_000)
        assertEquals(listOf(1_000_000L, 1_033_333L, 1_066_666L, 1_100_000L), ticks)
        assertEquals("1s", TimelineLayout.frameLabel(1_000_000, 30))
        assertEquals("f2", TimelineLayout.frameLabel(1_066_666, 30))
        val time = TimelineLayout.ticksIn(TimelineLayout.RulerStep(0, 2_000_000), 30, 3_000_000, 9_000_000, 7_000_000)
        assertEquals(listOf(4_000_000L, 6_000_000L), time)
        assertTrue(TimelineLayout.ticksIn(step, 30, 6_000_000, 7_000_000, 5_000_000).isEmpty())
    }
}
