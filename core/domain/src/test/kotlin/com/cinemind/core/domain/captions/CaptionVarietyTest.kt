package com.cinemind.core.domain.captions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptionVarietyTest {

    private val base = CaptionPresets.REELS.applyTo("p")

    @Test
    fun offMeansTheProjectStyleEverywhere() {
        val look = CaptionVariety.look(base, "c1", listOf("hello", "everyone"))
        assertSame(base, look.style)
        assertEquals(-1, look.emphasis)
    }

    @Test
    fun onMixesDesignsAndIsStablePerPhrase() {
        val on = base.copy(variety = true)
        val phrases = (1..40).map { i -> "c$i" to listOf("phrase", "number", "$i", "here") }
        val looks = phrases.map { (id, w) -> CaptionVariety.look(on, id, w) }
        // Several different designs appear, and some phrases keep the project's own look.
        assertTrue(looks.map { it.style.font to it.style.textColor }.distinct().size >= 4)
        assertTrue(looks.any { it.style == on })
        // Same phrase, same look (preview and export agree).
        assertEquals(looks[5], CaptionVariety.look(on, phrases[5].first, phrases[5].second))
    }

    @Test
    fun keyWordIsEmphasisedAndHindiKeepsItsFont() {
        assertEquals(2, CaptionVariety.emphasisOf(listOf("it", "takes", "30", "seconds")))
        assertEquals(-1, CaptionVariety.emphasisOf(listOf("hai")))
        val on = base.copy(variety = true, font = CaptionFont.MUKTA.name)
        val hindi = (1..30).map { CaptionVariety.look(on, "h$it", listOf("नमस्ते", "दोस्तों")) }
        assertTrue(hindi.all { it.style.font == CaptionFont.MUKTA.name })
    }
}
