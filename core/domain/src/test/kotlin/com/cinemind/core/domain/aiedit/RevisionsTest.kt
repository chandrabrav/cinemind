package com.cinemind.core.domain.aiedit

import com.cinemind.core.domain.agents.Aspect
import com.cinemind.core.domain.editing.ClipTransition
import com.cinemind.core.domain.model.EditBrief
import com.cinemind.core.domain.model.EditStyle
import com.cinemind.core.domain.model.Pacing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RevisionsTest {

    private val brief = EditBrief(projectId = "p", prompt = "Goa trip reel", style = EditStyle.TRAVEL, targetDurationSec = 60)

    @Test
    fun lengthAndKeepMusicAreUnderstood() {
        val r = RevisionReader.read("Reduce the video to 30 seconds and keep the same music", brief, 58.0)
        assertEquals(30, r.brief.targetDurationSec)
        assertTrue(Aspect.DURATION in r.intent.change)
        assertTrue(Aspect.MUSIC in r.intent.keep)
        assertFalse(Aspect.MUSIC in r.intent.change)
        assertTrue(r.brief.prompt.startsWith("Goa trip reel"))
        assertTrue(r.brief.prompt.contains("Revision: Reduce the video"))
    }

    @Test
    fun styleTransitionsAndSlowMotion() {
        val r = RevisionReader.read("Make it more cinematic, smoother transitions, remove the slow-motion section", brief, 40.0)
        assertEquals(EditStyle.CINEMATIC, r.brief.style)
        assertEquals(ClipTransition.DISSOLVE, r.transition)
        assertTrue(r.noSpeedEffects)
        assertTrue(r.intent.creative)
        val plan = EditPlan(shots = listOf(PlanShot(1, 0.0, 2.0, speed = 0.5, ramp = "HERO"), PlanShot(1, 3.0, 5.0)))
        val c = RevisionReader.constrain(plan, r)
        assertTrue(c.shots.all { it.speed == 1.0 && it.ramp.isEmpty() })
        assertEquals(ClipTransition.CUT.name, c.shots[0].transitionIn)
        assertEquals(ClipTransition.DISSOLVE.name, c.shots[1].transitionIn)
    }

    @Test
    fun shorterAndFasterAreRelative() {
        val r = RevisionReader.read("shorter and faster please", brief, 50.0)
        assertEquals(35, r.brief.targetDurationSec)
        assertEquals(Pacing.FAST, r.brief.pacing)
    }

    @Test
    fun unknownNotesStillReplan() {
        val r = RevisionReader.read("put the sunset shot first", brief, 30.0)
        assertTrue(Aspect.SHOTS in r.intent.change)
        assertTrue(r.brief.prompt.endsWith("Revision: put the sunset shot first"))
    }

    @Test
    fun libraryHasEveryGenreAndSearchesAndStores() {
        Genre.entries.forEach { g -> assertTrue("no templates for $g", PromptLibrary.BUILT_IN.any { it.genre == g }) }
        assertEquals(PromptLibrary.BUILT_IN.size, PromptLibrary.BUILT_IN.map { it.id }.toSet().size)
        assertTrue(PromptLibrary.search("gym").isNotEmpty())
        assertTrue(PromptLibrary.search("sunset", Genre.TRAVEL).all { it.genre == Genre.TRAVEL })
        val mine = PromptTemplate("me-1", Genre.VLOG, "My style", "Cozy vlog; keep it calm", EditStyle.VLOG, Pacing.CALM, 45, custom = true)
        val back = PromptLibrary.decodeCustom(PromptLibrary.encodeCustom(listOf(mine)))
        assertEquals(listOf(mine), back)
        assertEquals(listOf("b", "a"), PromptLibrary.pushRecent(listOf("a", "b"), "b"))
    }
}
