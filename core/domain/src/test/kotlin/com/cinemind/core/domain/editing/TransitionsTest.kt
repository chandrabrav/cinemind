package com.cinemind.core.domain.editing

import com.cinemind.core.domain.model.Clip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TransitionsTest {

    private fun clip(id: String, lenUs: Long, type: ClipTransition = ClipTransition.CUT, ms: Long = 0) =
        Clip(id = id, trackId = "t", assetId = "a", timelineInUs = 0, timelineOutUs = 0, sourceInUs = 0, sourceOutUs = lenUs,
            transitionInType = type.name, transitionInMs = ms)

    // a 4 s, b 2 s (fade 1 s in), c 6 s (slide 0.8 s in)
    private val clips = TimelineEditing.relayout(
        listOf(clip("a", 4_000_000), clip("b", 2_000_000, ClipTransition.FADE, 1_000), clip("c", 6_000_000, ClipTransition.SLIDE_LEFT, 800)),
    )

    @Test
    fun animationsSplitEachTransitionAcrossBothClips() {
        val a = TransitionMath.animations(clips)
        assertEquals(ClipAnimation(ClipTransition.CUT, 0, ClipTransition.FADE, 500_000), a[0])
        assertEquals(ClipAnimation(ClipTransition.FADE, 500_000, ClipTransition.SLIDE_LEFT, 400_000), a[1])
        assertEquals(ClipAnimation(ClipTransition.SLIDE_LEFT, 400_000, ClipTransition.CUT, 0), a[2])
    }

    @Test
    fun openingUsesFullLengthAndEndingFadeAppliesToLastClip() {
        val withEnds = TransitionEditing.setOpeningAndEnding(clips, openingMs = 700, endingMs = 1_000)
        val a = TransitionMath.animations(withEnds)
        assertEquals(ClipTransition.FADE to 700_000L, a.first().inType to a.first().inUs)
        assertEquals(ClipTransition.FADE to 1_000_000L, a.last().outType to a.last().outUs)
    }

    @Test
    fun halvesAreCappedAtHalfTheClip() {
        val short = TimelineEditing.relayout(listOf(clip("x", 4_000_000), clip("y", 600_000, ClipTransition.FADE, 2_000)))
        val a = TransitionMath.animations(short)
        assertEquals(300_000L, a[1].inUs) // half of a 0.6 s clip, not 1 s
    }

    @Test
    fun fadeGoesThroughBlackAtTheBoundary() {
        val a = TransitionMath.animations(clips)
        val endOfA = TransitionMath.lookAt(3_999_999, 4_000_000, a[0])
        val startOfB = TransitionMath.lookAt(0, 2_000_000, a[1])
        assertTrue(endOfA.brightness < 0.01f)
        assertEquals(0f, startOfB.brightness, 0.0001f)
        assertEquals(FrameLook.IDENTITY, TransitionMath.lookAt(1_000_000, 4_000_000, a[0]))
        val midFadeOut = TransitionMath.lookAt(3_750_000, 4_000_000, a[0])
        assertEquals(0.5f, midFadeOut.brightness, 0.0001f)
    }

    @Test
    fun slideEntersFromTheRightAndSettles() {
        val a = TransitionMath.animations(clips)[2]
        assertEquals(1f, TransitionMath.lookAt(0, 6_000_000, a).translateX, 0.0001f)
        assertEquals(0f, TransitionMath.lookAt(400_000, 6_000_000, a).translateX, 0.0001f)
    }

    @Test
    fun cutIsAlwaysIdentity() {
        val anim = ClipAnimation(ClipTransition.CUT, 0, ClipTransition.CUT, 0)
        assertTrue(anim.isNone)
        assertTrue(TransitionMath.lookAt(0, 1_000_000, anim).isIdentity)
    }

    @Test
    fun setTransitionClampsToBothNeighbours() {
        val r = TransitionEditing.setTransition(clips, "b", ClipTransition.ZOOM_IN, 9_999)
        val b = r.single { it.id == "b" }
        assertEquals(ClipTransition.ZOOM_IN.name, b.transitionInType)
        assertEquals(2_000L, b.transitionInMs) // limited by b's 2 s length (and the 2 s max)
        val cut = TransitionEditing.setTransition(r, "b", ClipTransition.CUT, 500).single { it.id == "b" }
        assertEquals(0L, cut.transitionInMs)
    }

    @Test
    fun setAllLeavesTheOpeningAlone() {
        val r = TransitionEditing.setAll(clips, ClipTransition.ZOOM_OUT, 500)
        assertEquals(ClipTransition.CUT.name, r[0].transitionInType)
        assertTrue(r.drop(1).all { it.transitionInType == ClipTransition.ZOOM_OUT.name && it.transitionInMs == 500L })
    }

    @Test
    fun unknownNamesFallBackToCutAndNoOpsReturnSameList() {
        assertEquals(ClipTransition.CUT, ClipTransition.fromName("WARP_DRIVE"))
        assertSame(clips, TransitionEditing.setTransition(clips, "missing", ClipTransition.FADE, 500))
        assertSame(clips, TransitionEditing.setTransition(clips, "b", ClipTransition.FADE, 1_000))
    }

    @Test
    fun appendAddsAtTheEndAndRelayouts() {
        val r = TimelineEditing.append(clips, listOf(clip("d", 1_000_000)))
        assertEquals(listOf("a", "b", "c", "d"), r.map { it.id })
        assertEquals(12_000_000L to 13_000_000L, r.last().timelineInUs to r.last().timelineOutUs)
    }
}

class DissolveTest {

    private fun clip(id: String, inUs: Long, outUs: Long, t: ClipTransition = ClipTransition.CUT, ms: Long = 0) =
        Clip(id = id, trackId = "t", assetId = id, timelineInUs = 0, timelineOutUs = 0, sourceInUs = inUs, sourceOutUs = outUs,
            transitionInType = t.name, transitionInMs = ms)

    @Test
    fun dissolveBlendsAcrossTheCutUsingHandles() {
        // A: 0..3 s of a 5 s source; B: 1..4 s, dissolve 1 s.
        val clips = TimelineEditing.relayout(listOf(clip("a", 0, 3_000_000), clip("b", 1_000_000, 4_000_000, ClipTransition.DISSOLVE, 1_000)))
        val p = DissolveMath.pieces(clips, { 5_000_000L }, { false })
        assertEquals(2, p.size)
        val before = p[0]
        assertEquals(1, before.clipIndex)
        assertEquals(2_500_000L, before.timelineStartUs)
        assertEquals(500_000L, before.sourceStartUs)
        assertEquals(0f, before.alphaAt(2_500_000), 1e-4f)
        assertEquals(0.5f, before.alphaAt(3_000_000), 1e-4f)
        val after = p[1]
        assertEquals(0, after.clipIndex)
        assertEquals(3_000_000L, after.timelineStartUs)
        assertEquals(3_000_000L, after.sourceStartUs)
        assertEquals(0.25f, after.alphaAt(3_250_000), 1e-4f)
        // Total length unchanged.
        assertEquals(6_000_000L, TimelineEditing.durationUs(clips))
    }

    @Test
    fun noHandlesMeansShorterOrNoPieces() {
        // B starts at its source start (no footage before), A ends at its source end.
        val clips = TimelineEditing.relayout(listOf(clip("a", 0, 3_000_000), clip("b", 0, 3_000_000, ClipTransition.DISSOLVE, 800)))
        assertTrue(DissolveMath.pieces(clips, { 3_000_000L }, { false }).isEmpty())
        // Photos always have footage.
        assertEquals(2, DissolveMath.pieces(clips, { null }, { true }).size)
    }

    @Test
    fun otherTransitionsMakeNoPieces() {
        val clips = TimelineEditing.relayout(listOf(clip("a", 0, 3_000_000), clip("b", 1_000_000, 4_000_000, ClipTransition.FADE, 1_000)))
        assertTrue(DissolveMath.pieces(clips, { 5_000_000L }, { false }).isEmpty())
    }

    @Test
    fun openingDissolveFadesFromBlack() {
        val clips = TimelineEditing.relayout(listOf(clip("a", 0, 3_000_000, ClipTransition.DISSOLVE, 600)))
        assertEquals(ClipTransition.FADE, TransitionMath.animations(clips).single().inType)
    }

    @Test
    fun newMotionTransitionsStartAndEndClean() {
        for (t in listOf(ClipTransition.WHIP_LEFT, ClipTransition.WHIP_RIGHT, ClipTransition.SPIN, ClipTransition.SLIDE_DOWN)) {
            val anim = ClipAnimation(t, 500_000, t, 500_000)
            assertTrue(t.name, TransitionMath.lookAt(1_500_000, 3_000_000, anim).isIdentity)
            assertTrue(t.name, !TransitionMath.lookAt(0, 3_000_000, anim).isIdentity)
        }
    }
}
