package com.cinemind.core.domain.editing

import com.cinemind.core.domain.model.Clip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class SplitTest {

    private val clips = TimelineEditing.relayout(
        listOf(
            Clip(id = "a", trackId = "t", assetId = "x", timelineInUs = 0, timelineOutUs = 0, sourceInUs = 0, sourceOutUs = 2_000_000),
            Clip(id = "b", trackId = "t", assetId = "y", timelineInUs = 0, timelineOutUs = 0, sourceInUs = 1_000_000, sourceOutUs = 5_000_000,
                transitionInType = ClipTransition.FADE.name, transitionInMs = 500, transitionOutMs = 800),
        ),
    )

    @Test
    fun splitKeepsPlaybackIdentical() {
        // b occupies [2 s, 6 s) on the timeline and source [1 s, 5 s); split at timeline 3.5 s.
        val r = TimelineEditing.split(clips, "b", 3_500_000, newId = { "b2" })
        assertEquals(listOf("a", "b", "b2"), r.map { it.id })
        val (b1, b2) = r[1] to r[2]
        assertEquals(1_000_000L to 2_500_000L, b1.sourceInUs to b1.sourceOutUs)
        assertEquals(2_500_000L to 5_000_000L, b2.sourceInUs to b2.sourceOutUs)
        assertEquals(2_000_000L to 3_500_000L, b1.timelineInUs to b1.timelineOutUs)
        assertEquals(3_500_000L to 6_000_000L, b2.timelineInUs to b2.timelineOutUs)
        assertEquals("y", b2.assetId)
    }

    @Test
    fun transitionsStayOnTheOuterEdges() {
        val r = TimelineEditing.split(clips, "b", 3_500_000, newId = { "b2" })
        assertEquals(ClipTransition.FADE.name, r[1].transitionInType)
        assertEquals(0L, r[1].transitionOutMs)
        assertEquals(ClipTransition.CUT.name, r[2].transitionInType)
        assertEquals(800L, r[2].transitionOutMs)
    }

    @Test
    fun tooCloseToAnEdgeOrOutsideIsANoOp() {
        assertSame(clips, TimelineEditing.split(clips, "b", 2_100_000))
        assertSame(clips, TimelineEditing.split(clips, "b", 5_900_000))
        assertSame(clips, TimelineEditing.split(clips, "b", 9_000_000))
        assertSame(clips, TimelineEditing.split(clips, "missing", 1_000_000))
    }
}
