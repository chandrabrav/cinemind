package com.cinemind.core.domain.editing

import com.cinemind.core.domain.audio.Waveform
import com.cinemind.core.domain.model.Clip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CreatorToolsTest {

    private val b = Waveform.BUCKET_US

    /** Speech (0.6–0.8) with pauses (0.02) at the given seconds ranges, [seconds] long. */
    private fun talking(seconds: Int, pauses: List<Pair<Double, Double>>, spikesAt: List<Double> = emptyList()): FloatArray {
        val n = (seconds * 1_000_000L / b).toInt()
        return FloatArray(n) { i ->
            val t = i * b / 1e6
            when {
                pauses.any { t >= it.first && t < it.second } -> 0.02f
                spikesAt.any { kotlin.math.abs(t - it) < 0.03 } -> 1f
                else -> 0.6f + 0.2f * ((i % 7) / 7f)
            }
        }
    }

    private fun clip(id: String, inUs: Long, outUs: Long) = Clip(id = id, trackId = "t", assetId = "a", timelineInUs = 0, timelineOutUs = outUs - inUs, sourceInUs = inUs, sourceOutUs = outUs)

    @Test
    fun pausesAreCutWithPaddingAndShortGapsKept() {
        val peaks = talking(20, listOf(4.0 to 5.5, 10.0 to 10.3, 15.0 to 16.0))
        val cuts = TalkingHead.pauses(peaks, 0, 20_000_000, TalkingHead.SilenceSettings())
        assertEquals(2, cuts.size) // the 0.3 s gap is a natural breath, kept
        assertEquals(4_120_000L, cuts[0].first)
        assertEquals(5_380_000L, cuts[0].last + 1)
    }

    @Test
    fun removingSilencesSplitsClipsAndShortensTheTimeline() {
        val peaks = talking(20, listOf(4.0 to 5.5, 15.0 to 16.0))
        val r = TalkingHead.removeSilences(listOf(clip("c", 0, 20_000_000)), { peaks })
        assertEquals(2, r.changes)
        assertEquals(3, r.clips.size)
        assertEquals("c", r.clips.first().id)
        assertTrue(r.removedUs in 1_900_000L..2_100_000L)
        // Back-to-back on the timeline, middle piece zoomed for the jump cut.
        assertEquals(r.clips[0].timelineOutUs, r.clips[1].timelineInUs)
        assertEquals(1.12f, ClipFraming.decode(r.clips[1].framing).keys.single().scale, 1e-4f)
        assertTrue(ClipFraming.decode(r.clips[2].framing).keys.isEmpty())
    }

    @Test
    fun musicOrSteadyNoiseIsNotCut() {
        val steady = FloatArray(1_000) { 0.5f + 0.05f * (it % 3) }
        assertTrue(TalkingHead.pauses(steady, 0, 20_000_000, TalkingHead.SilenceSettings()).isEmpty())
        val r = TalkingHead.removeSilences(listOf(clip("c", 0, 20_000_000)), { steady })
        assertEquals(0, r.changes)
    }

    @Test
    fun punchInsLandOnEmphasisAndAreSpacedOut() {
        val peaks = talking(20, emptyList(), spikesAt = listOf(5.0, 6.0, 12.0))
        val r = TalkingHead.punchIns(listOf(clip("c", 0, 20_000_000)), { peaks })
        assertEquals(2, r.changes) // 6.0 is too close to 5.0
        val keys = ClipFraming.decode(r.clips.single().framing).keys
        assertTrue(keys.any { it.scale > 1.1f && kotlin.math.abs(it.sourceUs - 5_000_000) < 40_000 })
        assertTrue(keys.any { it.scale > 1.1f && kotlin.math.abs(it.sourceUs - 12_000_000) < 40_000 })
    }

    @Test
    fun reframeCropsAroundTheSubjectAndStaysInside() {
        val crop = Reframe.cropFor(1920, 1080, 9f / 16f, 0.95f, 0.5f)!!
        assertEquals(0.316f, crop.keptWidth, 0.01f)
        assertEquals(0f, crop.right, 0.01f) // pushed against the right edge, never outside
        val centred = Reframe.cropFor(1920, 1080, 9f / 16f, 0.5f, 0.5f)!!
        assertEquals(centred.left, centred.right, 0.01f)
        assertNull(Reframe.cropFor(1080, 1920, 9f / 16f, 0.5f, 0.5f))
        val tall = Reframe.cropFor(1080, 1920, 16f / 9f, 0.5f, 0.1f)!!
        assertEquals(0f, tall.top, 0.01f)
    }

    @Test
    fun aSubjectThatMovesFarSplitsTheClip() {
        val samples = (0 until 20).map { i -> Reframe.SubjectSample(i * 500_000L, if (i < 10) 0.2f else 0.8f, 0.5f) }
        val r = Reframe.apply(listOf(clip("c", 0, 10_000_000)), { 1920 to 1080 }, { samples }, 9f / 16f)
        assertEquals(2, r.clips.size)
        val first = ClipFraming.decode(r.clips[0].framing)
        val second = ClipFraming.decode(r.clips[1].framing)
        assertTrue(first.fill && first.crop.left < second.crop.left)
        assertEquals(10_000_000L, r.clips.last().timelineOutUs)
    }

    @Test
    fun subjectCentreComesFromTheCutout() {
        val w = 10
        val h = 4
        val mask = ByteArray(w * h)
        for (y in 0 until h) mask[y * w + 8] = -1 // column 8 fully subject
        val c = Reframe.centre(mask, w, h)
        assertNotNull(c)
        assertEquals(8f / 9f, c!!.first, 1e-3f)
        assertNull(Reframe.centre(ByteArray(w * h), w, h))
    }

    @Test
    fun assistantUnderstandsAutoEditCommands() {
        fun type(s: String) = com.cinemind.core.domain.assistant.QuickCommands.parse(s, 2, 0.0)?.actions?.single()?.type
        assertEquals(com.cinemind.core.domain.assistant.AssistantActionType.REMOVE_PAUSES, type("remove the silences"))
        assertEquals(com.cinemind.core.domain.assistant.AssistantActionType.REMOVE_PAUSES, type("cut out the dead air"))
        assertEquals(com.cinemind.core.domain.assistant.AssistantActionType.PUNCH_INS, type("add punch ins"))
        assertEquals(com.cinemind.core.domain.assistant.AssistantActionType.REFRAME, type("make it vertical for reels"))
        assertEquals(com.cinemind.core.domain.assistant.AssistantActionType.REFRAME, type("auto reframe"))
    }
}
