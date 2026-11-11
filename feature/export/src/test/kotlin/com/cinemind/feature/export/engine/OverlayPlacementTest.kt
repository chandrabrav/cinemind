package com.cinemind.feature.export.engine

import com.cinemind.core.domain.model.Clip
import com.cinemind.feature.export.plan.OutputSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OverlayPlacementTest {

    private val out = OutputSize(1080, 1920)
    private fun clip(x: Float = 0.5f, y: Float = 0.5f, scale: Float = 0.4f, rot: Float = 0f, opacity: Float = 1f, inUs: Long = 0, outUs: Long = 1_000_000) =
        Clip(id = "o", trackId = "t", assetId = "a", timelineInUs = inUs, timelineOutUs = outUs, sourceInUs = 0, sourceOutUs = outUs - inUs,
            transformX = x, transformY = y, scale = scale, rotation = rot, opacity = opacity)

    @Test fun `centre maps to the middle and corners to NDC edges with y up`() {
        val p = OverlayPlacement.of(clip(), 1920, 1080, out)
        assertEquals(0f, p.anchorX, 1e-6f)
        assertEquals(0f, p.anchorY, 1e-6f)
        val tl = OverlayPlacement.of(clip(x = 0f, y = 0f), 1920, 1080, out)
        assertEquals(-1f, tl.anchorX, 1e-6f)
        assertEquals(1f, tl.anchorY, 1e-6f)
    }

    @Test fun `scale makes the overlay the requested fraction of the frame width, keeping aspect`() {
        // A 1920-wide overlay at 40 % of a 1080-wide frame: compositor size 1920/1080 × s = 0.4.
        val p = OverlayPlacement.of(clip(scale = 0.4f), 1920, 1080, out)
        assertEquals(0.4f * 1080 / 1920, p.scaleX, 1e-6f)
        assertEquals(p.scaleX, p.scaleY, 0f)
        assertEquals(0.4f, (1920f / 1080f) * p.scaleX, 1e-6f)
    }

    @Test fun `rotation flips to counter-clockwise and alpha follows opacity`() {
        val p = OverlayPlacement.of(clip(rot = 30f, opacity = 0.6f), 100, 100, out)
        assertEquals(330f, p.rotationCcw, 1e-4f)
        assertEquals(0.6f, p.alpha, 1e-6f)
        assertEquals(0f, OverlayPlacement.of(clip(rot = 0f), 100, 100, out).rotationCcw, 0f)
    }

    @Test fun `clip lookup by time`() {
        val src = RenderSource("u", true, false, 30f, 1_000_000)
        val track = listOf(RenderOverlay(clip(inUs = 1_000_000, outUs = 2_000_000), src, 100, 100))
        assertNull(OverlayPlacement.clipAt(track, 500_000))
        assertEquals(track[0], OverlayPlacement.clipAt(track, 1_500_000))
        assertNull(OverlayPlacement.clipAt(track, 2_000_000))
    }
}
