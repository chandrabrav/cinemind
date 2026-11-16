package com.cinemind.core.domain.editing

import com.cinemind.core.domain.model.Clip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class SpeedTest {

    private fun clip(id: String, inUs: Long, outUs: Long, speed: Float = 1f, curve: String = "") =
        Clip(id = id, trackId = "t", assetId = "a-$id", timelineInUs = 0, timelineOutUs = 0, sourceInUs = inUs, sourceOutUs = outUs, speed = speed, speedCurve = curve)

    @Test
    fun constantSpeedChangesTimelineLength() {
        val laid = TimelineEditing.relayout(listOf(clip("a", 0, 4_000_000, speed = 2f), clip("b", 0, 1_000_000, speed = 0.5f)))
        assertEquals(0L to 2_000_000L, laid[0].timelineInUs to laid[0].timelineOutUs)
        assertEquals(2_000_000L to 4_000_000L, laid[1].timelineInUs to laid[1].timelineOutUs)
    }

    @Test
    fun encodeDecodeRoundTrip() {
        val pts = listOf(SpeedPoint(1_000_000, 0.5f, SpeedEase.LINEAR), SpeedPoint(0, 2f))
        val back = SpeedMath.decode(SpeedMath.encode(pts))
        assertEquals(listOf(0L, 1_000_000L), back.map { it.sourceUs })
        assertEquals(2f, back[0].speed, 1e-3f)
        assertEquals(SpeedEase.LINEAR, back[1].ease)
        assertTrue(SpeedMath.decode("garbage;;x:y").isEmpty())
    }

    @Test
    fun speedsAreClamped() {
        assertEquals(SpeedMath.MAX_SPEED, SpeedMath.decode("0:50:LINEAR").single().speed, 0f)
        assertEquals(SpeedMath.MIN_SPEED, SpeedMath.decode("0:0:LINEAR").single().speed, 0f)
    }

    @Test
    fun flatRampEqualsConstant() {
        val c = clip("a", 0, 3_000_000, curve = SpeedMath.encode(listOf(SpeedPoint(0, 0.5f), SpeedPoint(3_000_000, 0.5f))))
        assertEquals(6_000_000L, SpeedMath.timelineDurationUs(c))
    }

    @Test
    fun linearRampLengthMatchesIntegral() {
        // 1× → 2× linearly over 4 s of source: timeline = ∫ dt / s(t) = 4 s · ln(2) / 1 ≈ 2.7726 s.
        val c = clip("a", 0, 4_000_000, curve = SpeedMath.encode(listOf(SpeedPoint(0, 1f, SpeedEase.LINEAR), SpeedPoint(4_000_000, 2f))))
        val expected = 4_000_000.0 * kotlin.math.ln(2.0)
        assertTrue(abs(SpeedMath.timelineDurationUs(c) - expected) < 5_000)
    }

    @Test
    fun timelineSourceMappingIsInverse() {
        val c = clip("a", 500_000, 5_500_000, curve = SpeedRampPreset.HERO.pointsFor(clip("x", 500_000, 5_500_000)).let(SpeedMath::encode))
        val total = SpeedMath.timelineDurationUs(c)
        for (t in listOf(0L, 100_000L, total / 3, total / 2, total - 1)) {
            val src = SpeedMath.timelineToSource(c, t)
            assertTrue("t=$t", abs(SpeedMath.sourceToTimeline(c, src) - t) <= 5)
        }
        assertEquals(5_000_000L, SpeedMath.timelineToSource(c, total))
    }

    @Test
    fun segmentsCoverWholeClipAndMerge() {
        val c = clip("a", 0, 2_000_000, curve = SpeedRampPreset.CINEMATIC.pointsFor(clip("x", 0, 2_000_000)).let(SpeedMath::encode))
        val segs = SpeedMath.segments(c)
        assertEquals(0L, segs.first().startUs)
        assertEquals(2_000_000L, segs.last().endUs)
        segs.zipWithNext { a, b -> assertEquals(a.endUs, b.startUs); assertTrue(a.speed != b.speed) }
        assertEquals(1, SpeedMath.segments(clip("b", 0, 2_000_000, speed = 3f)).size)
    }

    @Test
    fun splitOfRampedClipKeepsTotalLength() {
        val base = clip("a", 0, 6_000_000)
        val c = TimelineEditing.relayout(listOf(base.copy(speedCurve = SpeedMath.encode(SpeedRampPreset.VELOCITY.pointsFor(base)))))
        val before = TimelineEditing.durationUs(c)
        val split = TimelineEditing.split(c, "a", before / 2, newId = { "b" })
        assertEquals(2, split.size)
        assertTrue(abs(TimelineEditing.durationUs(split) - before) <= SpeedMath.STEP_US)
        assertEquals(split[0].sourceOutUs, split[1].sourceInUs)
    }

    @Test
    fun splitOfConstantSpeedClipCutsAtRightSourceTime() {
        val c = TimelineEditing.relayout(listOf(clip("a", 0, 4_000_000, speed = 2f)))
        val split = TimelineEditing.split(c, "a", 500_000, newId = { "b" })
        assertEquals(1_000_000L, split[0].sourceOutUs)
        assertEquals(500_000L, split[0].timelineOutUs)
        assertEquals(2_000_000L, split[1].timelineOutUs)
    }

    @Test
    fun freezeGoesBetweenSplitHalves() {
        val c = TimelineEditing.relayout(listOf(clip("a", 0, 4_000_000)))
        val still = clip("f", 0, 2_000_000)
        val out = TimelineEditing.insertFreeze(c, "a", 1_000_000, still, newId = { "b" })
        assertEquals(listOf("a", "f", "b"), out.map { it.id })
        assertEquals(6_000_000L, TimelineEditing.durationUs(out))
        val atStart = TimelineEditing.insertFreeze(c, "a", 50_000, still)
        assertEquals(listOf("f", "a"), atStart.map { it.id })
    }

    @Test
    fun slowMotionSmoothness() {
        assertEquals(0.25f, SpeedMath.smoothSlowestSpeed(120f, 30), 1e-4f)
        assertEquals(1f, SpeedMath.smoothSlowestSpeed(30f, 30), 1e-4f)
    }
}

class KeepPitchTest {
    private fun clip(curve: String = "") = Clip(id = "a", trackId = "t", assetId = "a", timelineInUs = 0, timelineOutUs = 0, sourceInUs = 0, sourceOutUs = 2_000_000, speed = 2f, speedCurve = curve)

    @Test
    fun flagSurvivesRampsAndIsIgnoredByTiming() {
        val ramp = SpeedMath.encode(listOf(SpeedPoint(0, 1f), SpeedPoint(2_000_000, 2f)))
        val withFlag = SpeedMath.withKeepPitch(ramp, true)
        assertTrue(SpeedMath.keepsPitch(clip(withFlag)))
        assertEquals(SpeedMath.decode(ramp), SpeedMath.decode(withFlag))
        assertEquals(SpeedMath.timelineDurationUs(clip(ramp)), SpeedMath.timelineDurationUs(clip(withFlag)))
        // Constant speed: the flag alone is not a ramp.
        val constant = clip(SpeedMath.withKeepPitch("", true))
        assertEquals(1_000_000L, SpeedMath.timelineDurationUs(constant))
        assertEquals(ramp, SpeedMath.withKeepPitch(withFlag, false))
    }
}
