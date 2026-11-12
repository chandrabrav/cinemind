package com.cinemind.core.domain.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GraphicsAdvisorTest {

    @Test
    fun themePicksTheLook() {
        val s = GraphicsAdvisor.suggest("Riya's shaadi", "", 20_000_000)
        assertEquals(TextPreset.CINEMA, s.first { it.slot == GraphicSlot.OPENING_TITLE }.preset)
        assertEquals(TextPreset.IMPACT, GraphicsAdvisor.suggest("Leg day", "gym session", 20_000_000).first().preset)
        assertTrue(GraphicsAdvisor.suggest("Leg day", "gym session", 20_000_000).any { it.slot == GraphicSlot.STICKER && it.text == "💪" })
    }

    @Test
    fun genericNamesBecomeTitlesFromTheBrief() {
        assertEquals("Goa Beach Sunset", GraphicsAdvisor.titleFrom("VID_20261112_183022", "goa beach sunset with friends"))
        assertEquals("My story", GraphicsAdvisor.titleFrom("Project 3", ""))
        assertEquals("Manali 2026", GraphicsAdvisor.titleFrom("Manali 2026", "anything"))
    }

    @Test
    fun timingFitsTheVideo() {
        val s = GraphicsAdvisor.suggest("Trip", "travel", 6_000_000, filmedAt = 1_762_000_000_000L, place = "Manali")
        s.forEach { assertTrue(it.toString(), it.startUs >= 0 && it.endUs <= 6_000_000 && it.endUs > it.startUs) }
        val title = s.first { it.slot == GraphicSlot.OPENING_TITLE }
        assertEquals(2_000_000L, title.endUs)
        val lower = s.first { it.slot == GraphicSlot.LOWER_THIRD }
        assertTrue(lower.text, lower.text.startsWith("Manali · "))
        assertTrue(GraphicsAdvisor.suggest("x", "", 2_000_000).none { it.slot == GraphicSlot.END_CARD })
    }

    @Test
    fun aiTitlesAreCleaned() {
        val t = GraphicsAdvisor.parseTitles("Here are some titles:\n1. Mountains Called\n- \"Into The Wild\"\n* Chasing Clouds Above Manali Valley Peaks Forever Today\nInto The Wild")
        assertEquals(listOf("Mountains Called", "Into The Wild"), t)
    }

    @Test
    fun layersAreEditableTextOnTop() {
        val existing = listOf(TextLayer(projectId = "p", startUs = 0, endUs = 1, text = "a", lane = 4))
        val s = GraphicsAdvisor.suggest("Trip", "travel", 10_000_000).first()
        val l = GraphicsAdvisor.toLayer("p", s, existing)
        assertEquals(5, l.lane)
        assertEquals(s.text, l.text)
        assertEquals(TextKind.TEXT, l.kindEnum)
    }
}
