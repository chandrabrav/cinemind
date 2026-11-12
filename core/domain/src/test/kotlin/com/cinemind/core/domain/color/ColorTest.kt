package com.cinemind.core.domain.color

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.math.abs

class CubeLutTest {

    @Test fun `identity samples back the input`() {
        val lut = CubeLut.identity(17)
        val out = lut.sample(0.123f, 0.5f, 0.987f)
        assertEquals(0.123f, out[0], 1e-4f)
        assertEquals(0.5f, out[1], 1e-4f)
        assertEquals(0.987f, out[2], 1e-4f)
    }

    @Test fun `parse handles comments, title, domain and red-fastest order`() {
        val text = """
            # comment
            TITLE "Swap"
            LUT_3D_SIZE 2
            DOMAIN_MIN 0 0 0
            DOMAIN_MAX 2 2 2
            0 0 0
            2 0 0
            0 2 0
            2 2 0
            0 0 2
            2 0 2
            0 2 2
            2 2 2
        """.trimIndent()
        val lut = CubeLut.parse(text)
        assertEquals(2, lut.size)
        assertEquals("Swap", lut.title)
        assertEquals(1f, lut.at(1, 0, 0, 0), 0f) // red corner, red channel, rescaled from domain 2
        assertEquals(0f, lut.at(1, 0, 0, 1), 0f)
        val mid = lut.sample(0.5f, 0.5f, 0.5f)
        assertEquals(0.5f, mid[0], 1e-5f)
    }

    @Test fun `round trip through text`() {
        val lut = BuiltInLooks.byKey("teal_orange")!!.toLut(9)
        val back = CubeLut.parse(lut.toCube())
        for (i in lut.data.indices) assertEquals(lut.data[i], back.data[i], 1e-5f)
    }

    @Test fun `bad files are rejected with a reason`() {
        fun rejects(text: String, part: String) = try {
            CubeLut.parse(text); fail("should reject")
        } catch (e: LutFormatException) {
            assertTrue("'${e.message}' should mention '$part'", e.message!!.contains(part, ignoreCase = true))
        }
        rejects("LUT_1D_SIZE 4\n0 0 0", "1D")
        rejects("0 0 0\n1 1 1", "LUT_3D_SIZE")
        rejects("LUT_3D_SIZE 2\n0 0 0", "Expected 8")
        rejects("LUT_3D_SIZE 99", "not supported")
        rejects("LUT_3D_SIZE 2\n0 0 x", "Not a number")
    }
}

class ColorGradeTest {

    @Test fun `encode and decode round trip, neutral is empty`() {
        val g = ColorGrade(lutId = "builtin:golden_hour", lutIntensity = 0.7f, exposure = 0.5f, contrast = -0.2f, fade = 0.3f)
        assertEquals(g, ColorGrade.decode(g.encode()))
        assertEquals("", ColorGrade.NONE.encode())
        assertTrue(ColorGrade.decode("").isNeutral)
        assertEquals(2f, ColorGrade.decode("exp=9").exposure, 0f)
        assertEquals(ColorGrade.NONE, ColorGrade.decode("garbage;;=;x=1"))
    }

    @Test fun `bake is null for neutral and identity-like for intensity zero`() {
        assertNull(ColorMath.bake(ColorGrade.NONE, null))
        val look = BuiltInLooks.byKey("classic_mono")!!.toLut(9)
        assertNull(ColorMath.bake(ColorGrade(lutId = "builtin:classic_mono", lutIntensity = 0f), look))
        val half = ColorMath.bake(ColorGrade(lutId = "builtin:classic_mono", lutIntensity = 0.5f), look, size = 9)!!
        val red = half.sample(1f, 0f, 0f)
        val full = look.sample(1f, 0f, 0f)
        // Half intensity lands half way between the original red and the mono version.
        assertEquals((1f + full[0]) / 2f, red[0], 0.02f)
    }

    @Test fun `sliders move colour the right way`() {
        fun after(g: ColorGrade, r: Float, gr: Float, b: Float) = floatArrayOf(r, gr, b).also { ColorMath.adjust(it, g) }
        assertTrue(after(ColorGrade(exposure = 1f), 0.4f, 0.4f, 0.4f)[0] > 0.4f)
        val warm = after(ColorGrade(temperature = 1f), 0.5f, 0.5f, 0.5f)
        assertTrue(warm[0] > warm[2])
        val desat = after(ColorGrade(saturation = -1f), 0.9f, 0.1f, 0.1f)
        assertTrue(abs(desat[0] - desat[1]) < 1e-4f)
        val faded = after(ColorGrade(fade = 1f), 0f, 0f, 0f)
        assertTrue(faded[0] > 0.15f)
        val contrast = after(ColorGrade(contrast = 1f), 0.8f, 0.8f, 0.8f)
        assertTrue(contrast[0] > 0.8f)
    }
}

class BuiltInLooksTest {

    @Test fun `every look is valid, unique and tagged`() {
        assertTrue(BuiltInLooks.ALL.size >= 25)
        assertEquals(BuiltInLooks.ALL.size, BuiltInLooks.ALL.map { it.key }.toSet().size)
        BuiltInLooks.ALL.forEach { look ->
            assertTrue("${look.name} has tags", look.tags.size >= 3)
            val lut = look.toLut(5)
            assertTrue(lut.data.all { it in 0f..1f })
        }
        assertNotNull(BuiltInLooks.byId("builtin:teal_orange"))
        assertNull(BuiltInLooks.byId("gmic:x"))
    }

    @Test fun `mono looks remove colour`() {
        val c = BuiltInLooks.byKey("classic_mono")!!.toLut(17).sample(0.9f, 0.2f, 0.1f)
        assertEquals(c[0], c[1], 1e-3f)
        assertEquals(c[1], c[2], 1e-3f)
    }
}

class LutAdvisorTest {

    private val candidates = BuiltInLooks.ALL.map { LutCandidate(it.id, it.name, it.category, it.tags) }

    @Test fun `scene words pick fitting looks with reasons`() {
        val s = LutAdvisor.suggest(SceneProfile(words = LutAdvisor.keywords("Cinematic travel reel of our Goa beach trip at sunset")), candidates)
        assertTrue(s.isNotEmpty())
        val top = s.take(3).map { it.id }
        assertTrue("$top", top.any { it in setOf("builtin:teal_orange", "builtin:golden_hour", "builtin:tropical") })
        assertTrue(s.first().reason.contains("matches", ignoreCase = true))
    }

    @Test fun `measured footage nudges the choice`() {
        val dark = LutAdvisor.suggest(SceneProfile(words = setOf("city"), brightness = 0.2f), candidates)
        assertTrue(dark.take(3).any { "night" in (BuiltInLooks.byId(it.id)?.tags ?: emptySet()) })
        val flat = LutAdvisor.suggest(SceneProfile(saturation = 0.1f), candidates)
        assertTrue(flat.first().reason.contains("flat", ignoreCase = true))
    }

    @Test fun `keywords expand Indian and everyday words`() {
        val k = LutAdvisor.keywords("Shaadi highlights and Diwali night, gym workouts")
        assertTrue("wedding" in k)
        assertTrue("festival" in k)
        assertTrue("fitness" in k)
    }

    @Test fun `nothing known gives safe defaults`() {
        val s = LutAdvisor.suggest(SceneProfile(), candidates)
        assertEquals("builtin:teal_orange", s.first().id)
        assertFalse(s.isEmpty())
    }
}
