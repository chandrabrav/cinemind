package com.cinemind.core.domain.editing

import com.cinemind.core.domain.model.Clip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class TimelineEditingTest {

    private fun clip(id: String, inUs: Long, outUs: Long) =
        Clip(id = id, trackId = "t", assetId = "a-$id", timelineInUs = 0, timelineOutUs = 0, sourceInUs = inUs, sourceOutUs = outUs)

    // a: 2 s, b: 3 s, c: 5 s  →  [0,2) [2,5) [5,10)
    private val clips = TimelineEditing.relayout(
        listOf(clip("a", 0, 2_000_000), clip("b", 0, 3_000_000), clip("c", 1_000_000, 6_000_000)),
    )
    private val video10s = ClipLimits(sourceDurationUs = 10_000_000, isStill = false)
    private val still = ClipLimits(sourceDurationUs = null, isStill = true)

    private fun spans(list: List<Clip>) = list.map { it.id to (it.timelineInUs to it.timelineOutUs) }

    @Test
    fun relayoutPlacesClipsBackToBack() {
        assertEquals(
            listOf("a" to (0L to 2_000_000L), "b" to (2_000_000L to 5_000_000L), "c" to (5_000_000L to 10_000_000L)),
            spans(clips),
        )
        assertEquals(10_000_000L, TimelineEditing.durationUs(clips))
        assertEquals(0L, TimelineEditing.durationUs(emptyList()))
    }

    @Test
    fun trimShiftsLaterClips() {
        val r = TimelineEditing.trim(clips, "b", 500_000, 2_000_000, video10s)
        val b = r.single { it.id == "b" }
        assertEquals(500_000L to 2_000_000L, b.sourceInUs to b.sourceOutUs)
        assertEquals(
            listOf("a" to (0L to 2_000_000L), "b" to (2_000_000L to 3_500_000L), "c" to (3_500_000L to 8_500_000L)),
            spans(r),
        )
    }

    @Test
    fun trimClampsToSourceBoundsAndMinimumLength() {
        val wide = TimelineEditing.trim(clips, "a", -5, 99_000_000, video10s).single { it.id == "a" }
        assertEquals(0L to 10_000_000L, wide.sourceInUs to wide.sourceOutUs)

        val tiny = TimelineEditing.trim(clips, "a", 3_000_000, 3_000_100, video10s).single { it.id == "a" }
        assertEquals(TimelineEditing.MIN_CLIP_US, tiny.sourceOutUs - tiny.sourceInUs)

        val atEnd = TimelineEditing.trim(clips, "a", 9_999_000, 10_000_000, video10s).single { it.id == "a" }
        assertEquals(10_000_000L - TimelineEditing.MIN_CLIP_US to 10_000_000L, atEnd.sourceInUs to atEnd.sourceOutUs)
    }

    @Test
    fun sourceShorterThanMinimumKeepsWholeSource() {
        val shortSource = ClipLimits(sourceDurationUs = 300_000, isStill = false)
        val list = TimelineEditing.relayout(listOf(clip("s", 0, 300_000)))
        assertSame(list, TimelineEditing.trim(list, "s", 100_000, 200_000, shortSource))
    }

    @Test
    fun unknownSourceDurationCannotExtendPastCurrentEnd() {
        val unknown = ClipLimits(sourceDurationUs = null, isStill = false)
        val b = TimelineEditing.trim(clips, "b", 0, 9_000_000, unknown).single { it.id == "b" }
        assertEquals(3_000_000L, b.sourceOutUs)
    }

    @Test
    fun stillsChangeLengthWithinLimits() {
        val stillClips = TimelineEditing.relayout(listOf(clip("p", 0, 3_000_000)))
        assertEquals(5_000_000L, TimelineEditing.trim(stillClips, "p", 0, 5_000_000, still).single().sourceOutUs)
        assertEquals(TimelineEditing.MIN_CLIP_US, TimelineEditing.trim(stillClips, "p", 0, 1, still).single().sourceOutUs)
        assertEquals(TimelineEditing.MAX_STILL_US, TimelineEditing.trim(stillClips, "p", 0, Long.MAX_VALUE / 2, still).single().sourceOutUs)
        assertEquals(0L, TimelineEditing.trim(stillClips, "p", 400, 5_000_400, still).single().sourceInUs)
    }

    @Test
    fun unchangedTrimReturnsSameList() {
        assertSame(clips, TimelineEditing.trim(clips, "a", 0, 2_000_000, video10s))
        assertSame(clips, TimelineEditing.trim(clips, "missing", 0, 1, video10s))
    }

    @Test
    fun moveReordersAndRelayouts() {
        val r = TimelineEditing.move(clips, from = 2, to = 0)
        assertEquals(listOf("c", "a", "b"), r.map { it.id })
        assertEquals(
            listOf("c" to (0L to 5_000_000L), "a" to (5_000_000L to 7_000_000L), "b" to (7_000_000L to 10_000_000L)),
            spans(r),
        )
        assertEquals(listOf("b", "c", "a"), TimelineEditing.move(clips, 0, 99).map { it.id })
    }

    @Test
    fun moveToSamePlaceOrInvalidIndexIsNoOp() {
        assertSame(clips, TimelineEditing.move(clips, 1, 1))
        assertSame(clips, TimelineEditing.move(clips, -1, 0))
        assertSame(clips, TimelineEditing.move(clips, 3, 0))
    }

    @Test
    fun deleteClosesTheGap() {
        val r = TimelineEditing.delete(clips, "b")
        assertEquals(listOf("a" to (0L to 2_000_000L), "c" to (2_000_000L to 7_000_000L)), spans(r))
        assertSame(clips, TimelineEditing.delete(clips, "missing"))
        assertEquals(0L, TimelineEditing.durationUs(TimelineEditing.delete(TimelineEditing.delete(r, "a"), "c")))
    }
}
