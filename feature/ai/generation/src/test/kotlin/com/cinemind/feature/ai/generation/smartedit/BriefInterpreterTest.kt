package com.cinemind.feature.ai.generation.smartedit

import com.cinemind.core.domain.editing.ClipTransition
import com.cinemind.core.domain.model.EditBrief
import com.cinemind.core.domain.model.EditStyle
import com.cinemind.core.domain.model.Pacing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BriefInterpreterTest {

    private fun brief(prompt: String = "", style: EditStyle = EditStyle.AUTO, pacing: Pacing = Pacing.BALANCED, target: Int? = null) =
        EditBrief(projectId = "p", prompt = prompt, style = style, pacing = pacing, targetDurationSec = target)

    @Test
    fun keywordsFillInStylePaceAndLength() {
        val i = BriefInterpreter.interpret(brief("A fast, energetic gym reel, 30 seconds, skip shaky shots"))
        assertEquals(EditStyle.GYM, i.style)
        assertEquals(Pacing.FAST, i.pacing)
        assertEquals(30_000_000L, i.targetUs)
        assertTrue(i.strictQuality)
        assertEquals(ShotOrder.HOOK_FIRST, i.order)
        assertEquals(ClipTransition.ZOOM_IN, i.transition)
    }

    @Test
    fun explicitChoicesBeatKeywords() {
        val i = BriefInterpreter.interpret(brief("fast gym edit 30s", style = EditStyle.CINEMATIC, pacing = Pacing.CALM, target = 60))
        assertEquals(EditStyle.CINEMATIC, i.style)
        assertEquals(Pacing.CALM, i.pacing)
        assertEquals(60_000_000L, i.targetUs)
    }

    @Test
    fun travelStoryPunchesInOrder() {
        val i = BriefInterpreter.interpret(brief("Our Goa trip as a story from morning to night"))
        assertEquals(EditStyle.TRAVEL, i.style)
        assertEquals(ShotOrder.CHRONOLOGICAL, i.order)
        assertEquals(ClipTransition.ZOOM_IN, i.transition)
        assertNull(i.targetUs)
    }

    @Test
    fun transitionWordsOverrideStyleDefault() {
        assertEquals(ClipTransition.CUT, BriefInterpreter.interpret(brief("cinematic, only cuts")).transition)
        assertEquals(0L, BriefInterpreter.interpret(brief("vlog")).transitionMs)
        assertEquals(ClipTransition.DISSOLVE, BriefInterpreter.interpret(brief("travel with fades")).transition)
        assertEquals(ClipTransition.FADE, BriefInterpreter.interpret(brief("travel, fade to black between places")).transition)
    }

    @Test
    fun durationParsing() {
        assertEquals(45_000_000L, BriefInterpreter.parseDurationUs("about 45 sec please"))
        assertEquals(120_000_000L, BriefInterpreter.parseDurationUs("2 minutes"))
        assertNull(BriefInterpreter.parseDurationUs("2 shots of the beach"))
        assertNull(BriefInterpreter.parseDurationUs("3 s"))
    }

    @Test
    fun calmerPaceAndCinematicStyleMeanLongerShots() {
        assertTrue(
            BriefInterpreter.shotLengthUs(EditStyle.CINEMATIC, Pacing.CALM) >
                BriefInterpreter.shotLengthUs(EditStyle.GYM, Pacing.FAST),
        )
    }
}
