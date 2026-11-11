package com.cinemind.feature.editor.timeline.preview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TimelineMathTest {

    // Three clips: [0,2s) [2s,5s) [5s,10.5s)
    private val spans = listOf(
        ClipSpan(0, 2_000_000),
        ClipSpan(2_000_000, 5_000_000),
        ClipSpan(5_000_000, 10_500_000),
    )

    @Test
    fun totalDurationIsEndOfLastClip() {
        assertEquals(10_500_000L, TimelineMath.totalDurationUs(spans))
        assertEquals(0L, TimelineMath.totalDurationUs(emptyList()))
    }

    @Test
    fun locateFindsClipAndOffset() {
        assertEquals(ClipLocation(0, 0), TimelineMath.locate(spans, 0))
        assertEquals(ClipLocation(0, 1_999_999), TimelineMath.locate(spans, 1_999_999))
        assertEquals(ClipLocation(2, 2_500_000), TimelineMath.locate(spans, 7_500_000))
    }

    @Test
    fun clipBoundaryBelongsToTheNextClip() {
        assertEquals(ClipLocation(1, 0), TimelineMath.locate(spans, 2_000_000))
        assertEquals(ClipLocation(2, 0), TimelineMath.locate(spans, 5_000_000))
    }

    @Test
    fun outOfRangePositionsClamp() {
        assertEquals(ClipLocation(0, 0), TimelineMath.locate(spans, -100))
        assertEquals(ClipLocation(2, 5_500_000), TimelineMath.locate(spans, 10_500_000))
        assertEquals(ClipLocation(2, 5_500_000), TimelineMath.locate(spans, 99_000_000))
    }

    @Test
    fun emptyTimelineHasNoLocation() {
        assertNull(TimelineMath.locate(emptyList(), 0))
    }

    @Test
    fun absolutePositionRoundTripsWithLocate() {
        listOf(0L, 1_000_000L, 2_000_000L, 4_999_999L, 9_000_000L).forEach { t ->
            val loc = TimelineMath.locate(spans, t)!!
            assertEquals(t, TimelineMath.absolutePositionUs(spans, loc.index, loc.offsetUs))
        }
    }

    @Test
    fun absolutePositionClampsOffsetToClip() {
        assertEquals(5_000_000L, TimelineMath.absolutePositionUs(spans, 1, 99_000_000))
        assertEquals(2_000_000L, TimelineMath.absolutePositionUs(spans, 1, -5))
    }
}
