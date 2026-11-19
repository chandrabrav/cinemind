package com.cinemind.feature.ai.generation.smartedit

import com.cinemind.core.domain.editing.ClipTransition
import com.cinemind.core.domain.model.EditBrief
import com.cinemind.core.domain.model.Project
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartEditPlannerTest {

    /** A video whose samples are good except inside [badFrom, badTo). */
    private fun video(id: String, order: Int, durS: Int, badFrom: Long = -1, badTo: Long = -1) = AssetAnalysis(
        assetId = id,
        isVideo = true,
        durationUs = durS * 1_000_000L,
        order = order,
        samples = (0 until durS * 2).map { k ->
            val t = k * 500_000L
            val bad = t in badFrom until badTo
            SamplePoint(t, sharpness = if (bad) 20f else 900f, brightness = if (bad) 0.05f else 0.5f, motion = 0.08f)
        },
    )

    private fun photo(id: String, order: Int) =
        AssetAnalysis(id, isVideo = false, durationUs = 0, order = order, samples = listOf(SamplePoint(0, 800f, 0.5f, 0f)))

    private fun intent(prompt: String, target: Int? = null) =
        BriefInterpreter.interpret(EditBrief(projectId = "p", prompt = prompt, targetDurationSec = target))

    private val footage = listOf(
        video("a", 0, 20, badFrom = 0, badTo = 8_000_000),
        video("b", 1, 15),
        photo("c", 2),
        video("d", 3, 10),
    )

    @Test
    fun hitsTheTargetLengthExactly() {
        val plan = SmartEditPlanner.plan(footage, intent("travel", target = 20))
        assertEquals(20_000_000L, plan.shots.sumOf { it.lengthUs })
    }

    @Test
    fun avoidsTheBadSectionAndNeverOverlaps() {
        val plan = SmartEditPlanner.plan(footage, intent("travel, skip blurry", target = 25))
        // No chosen window from "a" may lie mostly inside its bad first 8 s.
        assertTrue(plan.shots.filter { it.assetId == "a" }.all { it.sourceOutUs > 8_000_000L + it.lengthUs / 2 })
        plan.shots.groupBy { it.assetId }.values.forEach { shots ->
            shots.sortedBy { it.sourceInUs }.zipWithNext().forEach { (x, y) -> assertTrue(x.sourceOutUs <= y.sourceInUs) }
        }
    }

    @Test
    fun usesSeveralSourcesAndStaysChronological() {
        val plan = SmartEditPlanner.plan(footage, intent("story", target = 20))
        assertTrue(plan.shots.map { it.assetId }.distinct().size >= 3)
        val keys = plan.shots.map { it.order to it.sourceInUs }
        assertEquals(keys.sortedWith(compareBy({ it.first }, { it.second })), keys)
    }

    @Test
    fun hookFirstPutsTheBestShotFirst() {
        val plan = SmartEditPlanner.plan(footage, intent("gym hype", target = 12))
        assertEquals(plan.shots.maxOf { it.score }, plan.shots.first().score, 0.0001f)
    }

    @Test
    fun neverLongerThanTheFootage() {
        val plan = SmartEditPlanner.plan(listOf(video("x", 0, 4)), intent("cinematic", target = 90))
        assertTrue(plan.shots.sumOf { it.lengthUs } <= 4_000_000L)
        assertFalse(plan.shots.isEmpty())
    }

    @Test
    fun timelineHasTransitionsOnlyWhereTheSourceChanges() {
        val plan = SmartEditPlanner.plan(footage, intent("travel", target = 20))
        val source = Project(name = "Goa", aspectRatio = "9:16")
        val t = SmartEditPlanner.toTimeline(plan, source, "Goa smart edit", now = 5)
        assertEquals("Goa smart edit", t.project.name)
        assertEquals("9:16", t.project.aspectRatio)
        assertEquals(t.clips.last().timelineOutUs, t.project.durationUs)
        t.clips.zipWithNext().forEach { (prev, cur) ->
            val expected = if (prev.assetId == cur.assetId) ClipTransition.CUT else ClipTransition.ZOOM_IN
            assertEquals(expected.name, cur.transitionInType)
        }
        assertEquals(ClipTransition.FADE.name, t.clips.first().transitionInType)
        assertTrue(t.clips.last().transitionOutMs > 0)
        assertTrue(t.clips.all { it.trackId == t.tracks.single().id })
    }

    @Test
    fun autoTargetIsBounded() {
        assertEquals(8_000_000L, SmartEditPlanner.autoTargetUs(10_000_000L))
        assertEquals(45_000_000L, SmartEditPlanner.autoTargetUs(600_000_000L))
        assertEquals(5_000_000L, SmartEditPlanner.autoTargetUs(5_000_000L))
    }
}
