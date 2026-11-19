package com.cinemind.core.domain.agents

import com.cinemind.core.domain.aigen.AiMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DecisionEngineTest {

    private val gemma = ModelDescriptor("gemma-4-e2b", RuntimeKind.ON_DEVICE, setOf(ModelSkill.TEXT_REASONING, ModelSkill.STRUCTURED_OUTPUT, ModelSkill.VISION, ModelSkill.AUDIO), 3_200, 25f, 2, true)
    private val spark = ModelDescriptor("spark-x2.6-1.7b", RuntimeKind.ON_DEVICE, setOf(ModelSkill.TEXT_REASONING, ModelSkill.STRUCTURED_OUTPUT), 2_100, 27f, 3, true)
    private val claude = ModelDescriptor("claude", RuntimeKind.CLOUD, setOf(ModelSkill.TEXT_REASONING, ModelSkill.STRUCTURED_OUTPUT), 0, 60f, 5, true)
    private val roomy = ResourcePolicy.budget(ResourceSnapshot(availMemMb = 9_000, totalMemMb = 16_000))
    private val hybrid = Policy(AiMode.HYBRID, cloudConsent = false)
    private val travel = ProjectContext(clipCount = 12, videoCount = 10, totalSeconds = 240.0, hasSpeech = false, hasMusic = true)

    @Test
    fun plainTimelineOperationsUseNoAgentsButTheEditor() {
        val p = DecisionEngine.plan(RequestIntent(setOf(Goal.DIRECT_OPERATION)), travel, listOf(gemma, spark), roomy, hybrid)
        assertEquals(setOf(AgentId.RENDERING), p.agents)
        assertEquals(RuntimeKind.DETERMINISTIC, p.step(AgentId.RENDERING)!!.runtime)
    }

    @Test
    fun emotionalTravelFilmUsesTheAgentsItNeedsInOrder() {
        val p = DecisionEngine.plan(RequestIntent(setOf(Goal.NEW_EDIT), creative = true), travel, listOf(gemma, spark), roomy, hybrid)
        val agents = p.agents
        assertTrue(AgentId.CREATIVE_DIRECTOR in agents)
        assertTrue(AgentId.VISUAL_INTELLIGENCE in agents)
        assertTrue(AgentId.MUSIC_DIRECTOR in agents)
        assertTrue(AgentId.EDIT_PLANNER in agents && AgentId.QUALITY_ASSURANCE in agents)
        // No speech in the project: no audio work, no captions.
        assertFalse(AgentId.AUDIO_INTELLIGENCE in agents)
        assertFalse(AgentId.CAPTIONS in agents)
        // Vision on Gemma (the only model that sees), planning on the planner model.
        assertEquals("gemma-4-e2b", p.step(AgentId.VISUAL_INTELLIGENCE)!!.modelId)
        assertEquals("spark-x2.6-1.7b", p.step(AgentId.EDIT_PLANNER)!!.modelId)
        // Order follows the data: analysis → vision → planning → QA → timeline.
        val s = { id: AgentId -> p.step(id)!!.stage }
        assertTrue(s(AgentId.VIDEO_ANALYSIS) < s(AgentId.VISUAL_INTELLIGENCE))
        assertTrue(s(AgentId.VISUAL_INTELLIGENCE) < s(AgentId.EDIT_PLANNER))
        assertTrue(s(AgentId.EDIT_PLANNER) < s(AgentId.QUALITY_ASSURANCE))
        assertTrue(s(AgentId.QUALITY_ASSURANCE) < s(AgentId.RENDERING))
        // Two model agents never share a parallel group.
        p.stages.forEach { stage -> p.parallelGroups(stage).forEach { g -> assertTrue(g.count { it.runtime == RuntimeKind.ON_DEVICE } <= 1) } }
    }

    @Test
    fun simpleShortEditSkipsVisionAndCreativeWork() {
        val ctx = ProjectContext(clipCount = 1, videoCount = 1, totalSeconds = 12.0, hasSpeech = true)
        val p = DecisionEngine.plan(RequestIntent(setOf(Goal.NEW_EDIT)), ctx, listOf(gemma, spark), roomy, hybrid)
        assertFalse(AgentId.VISUAL_INTELLIGENCE in p.agents)
        assertFalse(AgentId.CREATIVE_DIRECTOR in p.agents)
        assertTrue(AgentId.AUDIO_INTELLIGENCE in p.agents)
    }

    @Test
    fun subtitlesNeedOnlyListeningAndCaptions() {
        val ctx = ProjectContext(clipCount = 3, videoCount = 3, totalSeconds = 60.0, hasSpeech = true)
        val p = DecisionEngine.plan(RequestIntent(setOf(Goal.CAPTIONS)), ctx, listOf(gemma, spark), roomy, hybrid)
        assertEquals(setOf(AgentId.AUDIO_INTELLIGENCE, AgentId.CAPTIONS), p.agents)
        assertEquals("gemma-4-e2b", p.step(AgentId.AUDIO_INTELLIGENCE)!!.modelId)
    }

    @Test
    fun offlineNeverPlansCloudAndAHotPhoneFallsBackToRules() {
        val offline = DecisionEngine.plan(RequestIntent(setOf(Goal.NEW_EDIT), creative = true), travel, listOf(gemma, claude), roomy, Policy(AiMode.OFFLINE, cloudConsent = true))
        assertTrue(offline.steps.none { it.runtime == RuntimeKind.CLOUD || it.fallbacks.any { f -> f.first == RuntimeKind.CLOUD } })

        val hot = ResourcePolicy.budget(ResourceSnapshot(availMemMb = 9_000, totalMemMb = 16_000, thermal = 3))
        val p = DecisionEngine.plan(RequestIntent(setOf(Goal.NEW_EDIT), creative = true), travel, listOf(gemma, spark), hot, hybrid)
        assertEquals(RuntimeKind.DETERMINISTIC, p.step(AgentId.EDIT_PLANNER)!!.runtime)
        assertNull(p.step(AgentId.VISUAL_INTELLIGENCE))
        assertTrue(p.skipped.containsKey(AgentId.VISUAL_INTELLIGENCE))
    }

    @Test
    fun cloudIsUsedOnlyWithConsentAndNeverForMedia() {
        val consent = Policy(AiMode.CLOUD, cloudConsent = true)
        val p = DecisionEngine.plan(RequestIntent(setOf(Goal.NEW_EDIT), creative = true), travel, listOf(gemma, spark, claude), roomy, consent)
        assertEquals(RuntimeKind.CLOUD, p.step(AgentId.EDIT_PLANNER)!!.runtime)
        // Seeing frames stays on the phone.
        assertEquals(RuntimeKind.ON_DEVICE, p.step(AgentId.VISUAL_INTELLIGENCE)!!.runtime)
        val noConsent = DecisionEngine.plan(RequestIntent(setOf(Goal.NEW_EDIT)), travel, listOf(gemma, spark, claude), roomy, Policy(AiMode.CLOUD, cloudConsent = false))
        assertTrue(noConsent.steps.none { it.runtime == RuntimeKind.CLOUD })
    }

    @Test
    fun failedPlannerIsRetriedThenFallsBackToRules() {
        val p = DecisionEngine.plan(RequestIntent(setOf(Goal.NEW_EDIT)), travel, listOf(gemma, spark), roomy, hybrid)
        val first = p.step(AgentId.EDIT_PLANNER)!!
        val second = DecisionEngine.recover(first)
        assertNotNull(second)
        assertEquals("gemma-4-e2b", second!!.modelId)
        val third = DecisionEngine.recover(second)!!
        assertEquals(RuntimeKind.DETERMINISTIC, third.runtime)
        assertNull(DecisionEngine.recover(third))
    }

    @Test
    fun revisionsRerunOnlyWhatChangedAndKeepTheMusic() {
        val done = travel.copy(available = setOf(DataKind.FOOTAGE_REPORT, DataKind.SHOT_DESCRIPTIONS, DataKind.CREATIVE_BRIEF, DataKind.MUSIC_PLAN, DataKind.EDIT_PLAN))
        val shorter = RequestIntent(setOf(Goal.REVISE_EDIT), change = setOf(Aspect.DURATION), keep = setOf(Aspect.MUSIC), targetSeconds = 30)
        val p = DecisionEngine.plan(shorter, done, listOf(gemma, spark), roomy, hybrid)
        assertTrue(AgentId.EDIT_PLANNER in p.agents)
        assertFalse(AgentId.VIDEO_ANALYSIS in p.agents)
        assertFalse(AgentId.VISUAL_INTELLIGENCE in p.agents)
        assertFalse(AgentId.MUSIC_DIRECTOR in p.agents)
        assertTrue(DataKind.MUSIC_PLAN in p.reused && DataKind.FOOTAGE_REPORT in p.reused)

        val newMusic = DecisionEngine.plan(RequestIntent(setOf(Goal.REVISE_EDIT), change = setOf(Aspect.MUSIC)), done, listOf(gemma, spark), roomy, hybrid)
        assertTrue(AgentId.MUSIC_DIRECTOR in newMusic.agents)
        assertFalse(DataKind.MUSIC_PLAN in newMusic.reused)
    }

    @Test
    fun resourceBudgetIsConservative() {
        val ok = ResourcePolicy.budget(ResourceSnapshot(availMemMb = 7_000, totalMemMb = 16_000))
        assertTrue(ok.localModelsAllowed && ok.maxModelMb in 3_000..4_100)
        val low = ResourcePolicy.budget(ResourceSnapshot(availMemMb = 2_500, totalMemMb = 8_000))
        assertFalse(low.localModelsAllowed)
        val battery = ResourcePolicy.budget(ResourceSnapshot(availMemMb = 9_000, totalMemMb = 16_000, batteryPercent = 10))
        assertFalse(battery.optionalAiAllowed)
        assertTrue(battery.maxModelMb <= 2_200)
    }
}
