package com.cinemind.core.domain.editing

import com.cinemind.core.domain.model.Clip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayEditingTest {

    private fun clip(id: String, inUs: Long, outUs: Long, srcIn: Long = 0) =
        Clip(id = id, trackId = "o", assetId = id, timelineInUs = inUs, timelineOutUs = outUs, sourceInUs = srcIn, sourceOutUs = srcIn + (outUs - inUs))

    private val total = 10_000_000L
    private val a = clip("a", 1_000_000, 3_000_000)
    private val b = clip("b", 5_000_000, 6_000_000)

    @Test fun `gaps and covered positions`() {
        assertEquals(3_000_000L..5_000_000L, OverlayEditing.gapAt(listOf(a, b), 4_000_000, total))
        assertNull(OverlayEditing.gapAt(listOf(a, b), 2_000_000, total))
        assertEquals(6_000_000L..total, OverlayEditing.gapAt(listOf(a, b), 9_000_000, total))
        assertEquals(0L..1_000_000L, OverlayEditing.gapAt(listOf(a, b), 0, total))
    }

    @Test fun `place shortens to the free gap and keeps source in sync`() {
        val placed = OverlayEditing.place(listOf(a, b), clip("n", 0, 0), 3_500_000, 4_000_000, total)!!
        val n = placed.first { it.id == "n" }
        assertEquals(3_500_000, n.timelineInUs)
        assertEquals(5_000_000, n.timelineOutUs)
        assertEquals(1_500_000, n.sourceOutUs - n.sourceInUs)
        assertNull(OverlayEditing.place(listOf(a, b), clip("n", 0, 0), 2_000_000, 1_000_000, total))
        assertNull(OverlayEditing.place(listOf(a, b), clip("n", 0, 0), 4_950_000, 1_000_000, total))
    }

    @Test fun `move stays between neighbours, never overlaps, never ripples`() {
        val moved = OverlayEditing.move(listOf(a, b), "b", 3_500_000, total)
        val nb = moved.first { it.id == "b" }
        assertEquals(3_500_000, nb.timelineInUs)
        assertEquals(1_000_000, nb.timelineOutUs - nb.timelineInUs)
        assertEquals(a, moved.first { it.id == "a" })
        // Into a too-small spot: clamped to the gap after a (3.0 s).
        assertEquals(3_000_000, OverlayEditing.move(listOf(a, b), "b", 2_900_000, total).first { it.id == "b" }.timelineInUs)
        // Past the end: clamped so it ends at the end of the video.
        assertEquals(total - 1_000_000, OverlayEditing.move(listOf(a, b), "b", 99_000_000, total).first { it.id == "b" }.timelineInUs)
    }

    @Test fun `trim respects source, neighbours and minimum`() {
        val src = clip("v", 2_000_000, 4_000_000, srcIn = 1_000_000)
        // Extend start left: only 1 s of source before sourceIn → 1.0 s.
        val t1 = OverlayEditing.trim(listOf(src), "v", 0, 4_000_000, sourceDurationUs = 3_500_000, totalUs = total).single()
        assertEquals(1_000_000, t1.timelineInUs)
        assertEquals(0, t1.sourceInUs)
        // Extend end: source ends at 3.5 s → timeline end 2.0 + 2.5 = 4.5 s.
        val t2 = OverlayEditing.trim(listOf(src), "v", 2_000_000, 9_000_000, sourceDurationUs = 3_500_000, totalUs = total).single()
        assertEquals(4_500_000, t2.timelineOutUs)
        // Still: unlimited by source, limited by neighbour.
        val still = OverlayEditing.trim(listOf(a, b), "a", 1_000_000, 8_000_000, sourceDurationUs = null, totalUs = total).first { it.id == "a" }
        assertEquals(5_000_000, still.timelineOutUs)
        val tiny = OverlayEditing.trim(listOf(a), "a", 2_990_000, 3_000_000, null, total).single()
        assertTrue(tiny.timelineOutUs - tiny.timelineInUs >= OverlayEditing.MIN_US)
    }

    @Test fun `transform is clamped`() {
        val c = OverlayEditing.setTransform(listOf(a), "a", 1.5f, -1f, 3f, -90f, 2f).single()
        assertEquals(1f, c.transformX, 0f)
        assertEquals(0f, c.transformY, 0f)
        assertEquals(1f, c.scale, 0f)
        assertEquals(270f, c.rotation, 0.001f)
        assertEquals(1f, c.opacity, 0f)
    }

    @Test fun `pieces are back to back with gaps and cut at the end`() {
        val late = clip("c", 9_500_000, 11_000_000)
        val p = OverlayEditing.pieces(listOf(b, a, late), total)
        assertEquals(
            listOf(1_000_000L, 2_000_000L, 2_000_000L, 1_000_000L, 3_500_000L, 500_000L),
            p.map { it.durationUs },
        )
        assertTrue(p[0] is OverlayEditing.Piece.Gap)
        assertTrue(p[1] is OverlayEditing.Piece.Item)
        assertEquals(total, p.sumOf { it.durationUs })
        assertTrue(OverlayEditing.pieces(emptyList(), total).isEmpty())
    }
}
