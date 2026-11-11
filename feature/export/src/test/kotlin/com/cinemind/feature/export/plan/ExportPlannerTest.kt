package com.cinemind.feature.export.plan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class ExportPlannerTest {

    @Test
    fun outputSizeScalesShortSideAndKeepsAspect() {
        assertEquals(OutputSize(1080, 1920), ExportPlanner.outputSize(1080, 1920, ExportResolution.P1080))
        assertEquals(OutputSize(720, 1280), ExportPlanner.outputSize(1080, 1920, ExportResolution.P720))
        assertEquals(OutputSize(3840, 2160), ExportPlanner.outputSize(1920, 1080, ExportResolution.P2160))
        assertEquals(OutputSize(720, 720), ExportPlanner.outputSize(1080, 1080, ExportResolution.P720))
        assertEquals(OutputSize(720, 900), ExportPlanner.outputSize(1080, 1350, ExportResolution.P720))
    }

    @Test
    fun outputDimensionsAreAlwaysEven() {
        ExportResolution.entries.forEach { r ->
            listOf(1080 to 1350, 1080 to 1920, 1001 to 777).forEach { (w, h) ->
                val s = ExportPlanner.outputSize(w, h, r)
                assertTrue("$w x $h @ $r -> $s", s.width % 2 == 0 && s.height % 2 == 0)
            }
        }
    }

    @Test
    fun bitrateGrowsWithPixelsFpsAndQualityWithinBounds() {
        val hd = OutputSize(1080, 1920)
        val std30 = ExportPlanner.videoBitrateBps(hd, 30, ExportQuality.STANDARD)
        val high30 = ExportPlanner.videoBitrateBps(hd, 30, ExportQuality.HIGH)
        val high60 = ExportPlanner.videoBitrateBps(hd, 60, ExportQuality.HIGH)
        assertEquals(7_464_960, std30)
        assertTrue(high30 > std30 && high60 > high30)
        assertEquals(2_000_000, ExportPlanner.videoBitrateBps(OutputSize(2, 2), 24, ExportQuality.STANDARD))
        assertEquals(80_000_000, ExportPlanner.videoBitrateBps(OutputSize(3840, 2160), 60, ExportQuality.HIGH))
    }

    @Test
    fun sizeEstimate() {
        // 10 s at 8 Mbps + 192 kbps audio ≈ 10.24 MB * 1.02
        assertEquals(10_444_800L, ExportPlanner.estimatedSizeBytes(10_000_000, 8_000_000))
        assertEquals(0L, ExportPlanner.estimatedSizeBytes(0, 8_000_000))
    }

    @Test
    fun fileNameIsSafeAndTimestamped() {
        val cal = Calendar.getInstance(TimeZone.getDefault()).apply { clear(); set(2026, Calendar.NOVEMBER, 11, 14, 25, 30) }
        assertEquals("CineMind_Goa_trip_20261111_142530.mp4", ExportPlanner.fileName("  Goa trip! ", cal.time))
        assertEquals("CineMind_Video_20261111_142530.mp4", ExportPlanner.fileName("✨✨", cal.time))
        assertTrue(ExportPlanner.fileName("x".repeat(200), cal.time).length < 80)
    }
}
