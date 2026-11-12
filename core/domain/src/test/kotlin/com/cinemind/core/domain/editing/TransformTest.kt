package com.cinemind.core.domain.editing

import com.cinemind.core.domain.model.Clip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransformTest {

    private fun clip(inUs: Long = 0, outUs: Long = 4_000_000, speed: Float = 1f) =
        Clip(id = "c", trackId = "t", assetId = "a", timelineInUs = 0, timelineOutUs = 0, sourceInUs = inUs, sourceOutUs = outUs, speed = speed)

    @Test
    fun noneEncodesEmptyAndRoundTrips() {
        assertEquals("", ClipFraming().encode())
        assertTrue(ClipFraming.decode("").isNone)
        val f = ClipFraming(
            crop = CropRect(0.1f, 0.05f, 0.2f, 0f),
            quarterTurns = 1,
            flipH = true,
            fill = true,
            keys = listOf(MotionKey(2_000_000, x = 0.1f, scale = 1.5f, rotation = -3f, opacity = 0.5f, ease = SpeedEase.LINEAR), MotionKey(0)),
        )
        val back = ClipFraming.decode(f.encode())
        assertEquals(f.crop, back.crop)
        assertEquals(1, back.quarterTurns)
        assertTrue(back.flipH)
        assertFalse(back.flipV)
        assertTrue(back.fill)
        assertEquals(listOf(0L, 2_000_000L), back.keys.map { it.sourceUs })
        assertEquals(1.5f, back.keys[1].scale, 1e-4f)
        assertEquals(SpeedEase.LINEAR, back.keys[1].ease)
    }

    @Test
    fun badTextIsIgnored() {
        val f = ClipFraming.decode("crop=a,b;turn=9;keys=x|1:2;flip=zz")
        assertTrue(f.crop.isNone)
        assertEquals(1, f.quarterTurns)
        assertTrue(f.keys.isEmpty())
    }

    @Test
    fun cropIsClamped() {
        val c = CropRect(0.95f, 0f, 0.9f, 0f).clamped()
        assertTrue(c.keptWidth >= 0.05f - 1e-4f)
    }

    @Test
    fun aspectCropIsCentred() {
        val c = CropRect.forAspect(1920, 1080, 9, 16)
        assertEquals(c.left, c.right, 1e-5f)
        assertEquals(0f, c.top, 0f)
        assertEquals(9f / 16f, 1920 * c.keptWidth / 1080f, 1e-3f)
        val tall = CropRect.forAspect(1080, 1920, 1, 1)
        assertEquals(1f, 1080f / (1920 * tall.keptHeight), 1e-3f)
    }

    @Test
    fun lookInterpolatesWithEaseAndHolds() {
        val keys = listOf(MotionKey(1_000_000, scale = 1f, ease = SpeedEase.LINEAR), MotionKey(3_000_000, scale = 2f))
        assertEquals(1f, TransformMath.lookAt(keys, 0).scale, 0f)
        assertEquals(1.5f, TransformMath.lookAt(keys, 2_000_000).scale, 1e-4f)
        assertEquals(2f, TransformMath.lookAt(keys, 9_000_000).scale, 0f)
        val smooth = keys.mapIndexed { i, k -> if (i == 0) k.copy(ease = SpeedEase.EASE_IN_OUT) else k }
        assertTrue(TransformMath.lookAt(smooth, 1_400_000).scale < TransformMath.lookAt(keys, 1_400_000).scale)
    }

    @Test
    fun lookFollowsClipSpeed() {
        // At 2×, 1 s into the clip on the timeline is 2 s into the footage.
        val c = clip(speed = 2f)
        val f = ClipFraming(keys = listOf(MotionKey(0, x = 0f, ease = SpeedEase.LINEAR), MotionKey(4_000_000, x = 1f)))
        assertEquals(0.5f, TransformMath.lookAtTimeline(c, f, 1_000_000).x, 1e-3f)
    }

    @Test
    fun setLookStaticReplacesAllKeys() {
        val f = ClipFraming(keys = listOf(MotionKey(0), MotionKey(1_000_000, scale = 2f)))
        val out = TransformMath.setLook(f, 500_000, MotionLook(scale = 1.3f), animate = false)
        assertEquals(1, out.keys.size)
        assertEquals(1.3f, out.keys.single().scale, 1e-4f)
    }

    @Test
    fun setLookAnimatedAddsOrReplacesNearKey() {
        var f = ClipFraming()
        f = TransformMath.setLook(f, 0, MotionLook(scale = 1f), animate = true)
        f = TransformMath.setLook(f, 2_000_000, MotionLook(scale = 1.5f), animate = true)
        assertEquals(2, f.keys.size)
        assertTrue(f.hasMotion)
        f = TransformMath.setLook(f, 2_050_000, MotionLook(scale = 1.8f), animate = true)
        assertEquals(2, f.keys.size)
        assertEquals(2_000_000L, f.keys[1].sourceUs)
        assertEquals(1.8f, f.keys[1].scale, 1e-4f)
        f = TransformMath.removeKeyNear(f, 1_990_000)
        assertEquals(1, f.keys.size)
    }

    @Test
    fun presetsSpanTheClip() {
        val c = clip(1_000_000, 5_000_000)
        val keys = MotionPreset.ZOOM_IN.keysFor(c)
        assertEquals(1_000_000L, keys.first().sourceUs)
        assertEquals(5_000_000L, keys.last().sourceUs)
        MotionPreset.entries.forEach { p -> assertTrue(p.name, ClipFraming(keys = p.keysFor(c)).hasMotion) }
    }

    @Test
    fun shownAspectAfterTurnAndCrop() {
        assertEquals(1080f / 1920f, TransformMath.shownAspect(1920, 1080, ClipFraming(quarterTurns = 1)), 1e-4f)
        assertEquals(1f, TransformMath.shownAspect(2000, 1000, ClipFraming(crop = CropRect(left = 0.25f, right = 0.25f))), 1e-4f)
    }
}
