package com.cinemind.core.domain.aiedit

import com.cinemind.core.domain.model.Pacing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StyleReferenceTest {

    private fun frames(seconds: Int, everyUs: Long, cutEverySamples: Int, motion: Float = 0.03f, warm: Float = 0.2f, bright: Float = 0.7f) =
        (0 until (seconds * 1_000_000L / everyUs).toInt()).map { i ->
            ReferenceFrame(
                timeUs = i * everyUs,
                brightness = bright,
                saturation = 0.55f,
                warmth = warm,
                contrast = 0.3f,
                change = if (i > 0 && i % cutEverySamples == 0) 0.6f else motion,
            )
        }

    @Test
    fun fastCuttingVideoReadsAsFastWarmAndBright() {
        // Sampled every 0.5 s, a cut every 2 samples = 1 s shots.
        val r = StyleReferences.from(ReferenceKind.VIDEO, "trip.mp4", 20_000_000, frames(20, 500_000, 2))
        assertEquals(1.0f, r.shotSec!!, 0.15f)
        assertEquals(Pacing.FAST, r.pacing)
        val text = r.describe()
        assertTrue(text, text.contains("fast cuts"))
        assertTrue(text, text.contains("warm tones"))
        assertTrue(text, text.contains("bright and airy"))
        assertTrue(text, text.contains("vivid colours"))
        assertTrue(text, text.contains("high contrast"))
    }

    @Test
    fun slowVideoIsCalmAndStillImageHasNoPace() {
        val slow = StyleReferences.from(ReferenceKind.VIDEO, "film.mp4", 30_000_000, frames(30, 500_000, 12, motion = 0.01f, warm = -0.2f, bright = 0.25f))
        assertEquals(Pacing.CALM, slow.pacing)
        assertTrue(slow.describe().contains("mostly still shots"))
        assertTrue(slow.describe().contains("cool tones"))
        assertTrue(slow.describe().contains("dark and moody"))

        val image = StyleReferences.from(ReferenceKind.IMAGE, "look.jpg", 0, listOf(ReferenceFrame(0, 0.5f, 0.1f, 0f, 0.1f)), seen = "a sunset over the sea")
        assertNull(image.shotSec)
        assertNull(image.pacing)
        val text = image.describe()
        assertTrue(text, text.startsWith("Reference image “look.jpg”"))
        assertTrue(text, text.contains("muted colours"))
        assertTrue(text, text.contains("It shows: a sunset over the sea"))
    }

    @Test
    fun encodesPromptBlockAndMajorityPacing() {
        val a = StyleReferences.from(ReferenceKind.VIDEO, "a\tb.mp4", 10_000_000, frames(10, 500_000, 2), seen = "beach\nparty")
        val back = StyleReference.decode(a.encode())!!
        assertEquals(a.copy(name = "a b.mp4", seen = "beach party"), back)
        assertNull(StyleReference.decode("junk"))

        assertEquals("", StyleReferences.promptBlock(emptyList()))
        val block = StyleReferences.promptBlock(listOf(a, a.copy(kind = ReferenceKind.IMAGE, shotSec = null)))
        assertTrue(block.contains("Style references from the user"))
        assertTrue(block.contains("\n1. Sample video"))
        assertTrue(block.contains("\n2. Reference image"))
        assertEquals(Pacing.FAST, StyleReferences.pacing(listOf(a, a, a.copy(shotSec = 6f))))
    }

    @Test
    fun histogramChangeIsHalfL1() {
        val a = FloatArray(16).also { it[0] = 1f }
        val b = FloatArray(16).also { it[15] = 1f }
        assertEquals(1f, StyleReferences.histogramChange(a, b), 1e-6f)
        assertEquals(0f, StyleReferences.histogramChange(a, a), 1e-6f)
    }
}
