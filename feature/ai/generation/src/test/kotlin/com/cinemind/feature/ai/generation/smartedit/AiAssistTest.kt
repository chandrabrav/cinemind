package com.cinemind.feature.ai.generation.smartedit

import com.cinemind.core.domain.editing.ClipTransition
import com.cinemind.core.domain.model.EditBrief
import com.cinemind.core.domain.model.EditStyle
import com.cinemind.core.domain.model.Pacing
import com.cinemind.feature.ai.llm.ondevice.BriefAnswer
import com.cinemind.feature.ai.llm.ondevice.ShotInsight
import com.cinemind.feature.ai.llm.ondevice.ShotSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiAssistTest {

    private fun insight(subject: String, scene: String = "beach", quality: Int = 8, interest: Int = 8) =
        ShotInsight(subject, scene, ShotSize.WIDE, people = 0, quality = quality, interest = interest)

    private fun video(id: String, order: Int, durS: Int) = AssetAnalysis(
        assetId = id, isVideo = true, durationUs = durS * 1_000_000L, order = order,
        samples = (0 until durS * 2).map { k -> SamplePoint(k * 500_000L, 900f, 0.5f, 0.08f) },
    )

    @Test
    fun keyframesCoverEachPartOfTheClip() {
        val times = AiAssist.keyframes(video("a", 0, 9), perSource = 3, sharpRef = 900f, strict = false)
        assertEquals(3, times.size)
        assertTrue(times[0] < 3_000_000 && times[1] in 3_000_000 until 6_000_000 && times[2] >= 6_000_000)
    }

    @Test
    fun photoGetsOneKeyframe() {
        val photo = AssetAnalysis("p", isVideo = false, durationUs = 0, order = 0, samples = listOf(SamplePoint(0, 800f, 0.5f, 0f)))
        assertEquals(listOf(0L), AiAssist.keyframes(photo, 3, 800f, false))
    }

    @Test
    fun weightRewardsInterestingSharpShots() {
        assertEquals(1f, AiAssist.weight(insight("x", quality = 10, interest = 10)), 0.0001f)
        assertTrue(AiAssist.weight(insight("x", quality = 2, interest = 2)) < 0.7f)
    }

    @Test
    fun repeatsAreDetectedBySubjectAndScene() {
        assertTrue(AiAssist.isRepeat(insight("sunset over the sea"), insight("sea at sunset")))
        assertFalse(AiAssist.isRepeat(insight("friends at dinner", "restaurant"), insight("sunset over the sea")))
        assertFalse(AiAssist.isRepeat(null, insight("x")))
    }

    @Test
    fun aiInsightsSteerShotChoiceAndAvoidRepeats() {
        // Two sources of equal measured quality; the model finds "b" far more interesting.
        val footage = listOf(video("a", 0, 10), video("b", 1, 10))
        val insights = mapOf(
            "a" to (0 until 10).map { TimedInsight(it * 1_000_000L, insight("empty parking lot", "street", 4, 2)) },
            "b" to (0 until 10).map { TimedInsight(it * 1_000_000L, insight("surfer riding a wave", "beach", 9, 10)) },
        )
        val intent = BriefInterpreter.interpret(EditBrief(projectId = "p", prompt = "travel", targetDurationSec = 8))
        val withAi = SmartEditPlanner.plan(footage, intent, insights)
        val fromB = withAi.shots.filter { it.assetId == "b" }.sumOf { it.lengthUs }
        val fromA = withAi.shots.filter { it.assetId == "a" }.sumOf { it.lengthUs }
        assertTrue("b preferred ($fromB vs $fromA)", fromB > fromA)
        assertTrue(withAi.decisions.any { it.startsWith("On-device AI saw") })
    }

    @Test
    fun explicitBriefChoicesBeatTheModel() {
        val brief = EditBrief(projectId = "p", prompt = "x", style = EditStyle.CINEMATIC, pacing = Pacing.CALM, targetDurationSec = 20)
        val keywords = BriefInterpreter.interpret(brief)
        val ai = BriefAnswer(style = "gym", pacing = "fast", targetSeconds = 60, order = "hook_first", transition = "zoom_in", strictQuality = true)
        val merged = AiAssist.mergeBrief(brief, keywords, ai)
        assertEquals(EditStyle.CINEMATIC, merged.style)
        assertEquals(Pacing.CALM, merged.pacing)
        assertEquals(20_000_000L, merged.targetUs)
        assertEquals(ShotOrder.HOOK_FIRST, merged.order)
        assertEquals(ClipTransition.ZOOM_IN, merged.transition)
        assertTrue(merged.strictQuality)
    }

    @Test
    fun modelFillsWhatTheUserLeftOnAuto() {
        val brief = EditBrief(projectId = "p", prompt = "something nice")
        val merged = AiAssist.mergeBrief(brief, BriefInterpreter.interpret(brief), BriefAnswer("travel", "fast", 30, null, "cut", null))
        assertEquals(EditStyle.TRAVEL, merged.style)
        assertEquals(Pacing.FAST, merged.pacing)
        assertEquals(30_000_000L, merged.targetUs)
        assertEquals(ClipTransition.CUT, merged.transition)
        assertEquals(0L, merged.transitionMs)
        assertEquals(BriefInterpreter.shotLengthUs(EditStyle.TRAVEL, Pacing.FAST), merged.shotUs)
    }

    @Test
    fun noAnswerKeepsKeywordIntent() {
        val brief = EditBrief(projectId = "p", prompt = "gym")
        val keywords = BriefInterpreter.interpret(brief)
        assertEquals(keywords, AiAssist.mergeBrief(brief, keywords, null))
    }
}
