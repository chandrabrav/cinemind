package com.cinemind.core.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectFormatTest {

    @Test
    fun dimensionsMatchTheirRatio() {
        ProjectFormat.entries.forEach { f ->
            val (rw, rh) = f.ratio.split(":").map { it.toInt() }
            assertEquals("${f.ratio} width/height", f.width.toLong() * rh, f.height.toLong() * rw)
        }
    }

    @Test
    fun dimensionsAreEvenForVideoEncoders() {
        assertTrue(ProjectFormat.entries.all { it.width % 2 == 0 && it.height % 2 == 0 })
    }

    @Test
    fun fromRatioRoundTrips() {
        ProjectFormat.entries.forEach { assertEquals(it, ProjectFormat.fromRatio(it.ratio)) }
        assertNull(ProjectFormat.fromRatio("21:9"))
    }
}

class EditBriefRulesTest {

    private fun brief(prompt: String = "", style: EditStyle = EditStyle.AUTO, target: Int? = null) =
        EditBrief(projectId = "p", prompt = prompt, style = style, targetDurationSec = target)

    @Test
    fun emptyBriefAsksForDirection() {
        assertEquals(1, EditBriefRules.notices(brief(), footageUs = 60_000_000).size)
        assertTrue(EditBriefRules.notices(brief(style = EditStyle.TRAVEL), 60_000_000).isEmpty())
        assertTrue(EditBriefRules.notices(brief(prompt = "Goa trip"), 60_000_000).isEmpty())
    }

    @Test
    fun targetLongerThanFootageIsFlagged() {
        val n = EditBriefRules.notices(brief(prompt = "x", target = 60), footageUs = 42_000_000)
        assertEquals(1, n.size)
        assertTrue(n.single().contains("42 s"))
        assertTrue(EditBriefRules.notices(brief(prompt = "x", target = 30), 42_000_000).isEmpty())
    }

    @Test
    fun overlongPromptIsFlaggedAndClampCutsIt() {
        val long = "a".repeat(EditBriefRules.MAX_PROMPT_LENGTH + 5)
        assertEquals(1, EditBriefRules.notices(brief(prompt = long), 60_000_000).size)
        assertEquals(EditBriefRules.MAX_PROMPT_LENGTH, EditBriefRules.clampPrompt(long).length)
    }

    @Test
    fun suggestionsAreAppendedAsSentencesWithoutDuplicates() {
        assertEquals("Fast cuts", EditBriefRules.appendSuggestion("", "Fast cuts"))
        assertEquals("Goa trip. Fast cuts", EditBriefRules.appendSuggestion("Goa trip  ", "Fast cuts"))
        assertEquals("Goa trip! Fast cuts", EditBriefRules.appendSuggestion("Goa trip!", "Fast cuts"))
        assertEquals("Goa. fast cuts", EditBriefRules.appendSuggestion("Goa. fast cuts", "Fast cuts"))
    }

    @Test
    fun appendingNeverExceedsLimit() {
        val nearlyFull = "b".repeat(EditBriefRules.MAX_PROMPT_LENGTH - 3)
        assertEquals(
            EditBriefRules.MAX_PROMPT_LENGTH,
            EditBriefRules.appendSuggestion(nearlyFull, EditBriefRules.SUGGESTIONS.first()).length,
        )
    }

    @Test
    fun newBriefsCarryCurrentSchemaVersion() {
        assertEquals(EditBrief.CURRENT_SCHEMA_VERSION, brief().schemaVersion)
    }
}
