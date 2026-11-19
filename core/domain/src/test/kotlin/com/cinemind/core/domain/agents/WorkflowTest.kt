package com.cinemind.core.domain.agents

import com.cinemind.core.domain.aigen.AiMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

class WorkflowTest {

    private val gemma = ModelDescriptor("gemma", RuntimeKind.ON_DEVICE, setOf(ModelSkill.TEXT_REASONING, ModelSkill.STRUCTURED_OUTPUT, ModelSkill.VISION, ModelSkill.AUDIO, ModelSkill.TRANSCRIPTION), 3_200, 25f, 2, true)
    private val spark = ModelDescriptor("spark", RuntimeKind.ON_DEVICE, setOf(ModelSkill.TEXT_REASONING, ModelSkill.STRUCTURED_OUTPUT), 3_100, 5f, 6, true)
    private val whisper = ModelDescriptor("whisper-tiny", RuntimeKind.ON_DEVICE, setOf(ModelSkill.TRANSCRIPTION), 200, 50f, 4, true)
    private val budget = ResourcePolicy.budget(ResourceSnapshot(availMemMb = 9_000, totalMemMb = 16_000))
    private val policy = Policy(AiMode.HYBRID, cloudConsent = false)
    private val talky = ProjectContext(clipCount = 6, videoCount = 6, totalSeconds = 120.0, hasSpeech = true, hasMusic = true)

    @Test
    fun cinematicTravelWithClearVoicesAndSubtitles() {
        val intent = RequestIntent(setOf(Goal.NEW_EDIT, Goal.CLEAN_AUDIO, Goal.CAPTIONS), creative = true)
        val p = DecisionEngine.plan(intent, talky, listOf(gemma, spark, whisper), budget, policy)
        val expected = setOf(
            AgentId.CREATIVE_DIRECTOR, AgentId.VISUAL_INTELLIGENCE, AgentId.VIDEO_ANALYSIS, AgentId.AUDIO_ENHANCEMENT,
            AgentId.AUDIO_INTELLIGENCE, AgentId.MUSIC_DIRECTOR, AgentId.EDIT_PLANNER, AgentId.CAPTIONS,
            AgentId.QUALITY_ASSURANCE, AgentId.RENDERING,
        )
        assertEquals(expected, p.agents)
        // Whisper transcribes (better timings) rather than Gemma; Spark plans; Gemma looks.
        assertEquals("whisper-tiny", p.step(AgentId.AUDIO_INTELLIGENCE)!!.modelId)
        assertEquals("spark", p.step(AgentId.EDIT_PLANNER)!!.modelId)
        assertEquals("gemma", p.step(AgentId.VISUAL_INTELLIGENCE)!!.modelId)
        // Voice clean-up and transcription don't wait for each other.
        assertEquals(p.step(AgentId.AUDIO_ENHANCEMENT)!!.stage, p.step(AgentId.AUDIO_INTELLIGENCE)!!.stage)
        assertTrue(p.step(AgentId.CAPTIONS)!!.stage > p.step(AgentId.AUDIO_INTELLIGENCE)!!.stage)
    }

    @Test
    fun switchedOffAgentsAreNotUsed() {
        val p = DecisionEngine.plan(RequestIntent(setOf(Goal.NEW_EDIT), creative = true), talky, listOf(gemma, spark), budget, policy.copy(disabled = setOf(AgentId.VISUAL_INTELLIGENCE)))
        assertTrue(AgentId.VISUAL_INTELLIGENCE !in p.agents)
        assertEquals("switched off in developer settings", p.skipped[AgentId.VISUAL_INTELLIGENCE])
    }

    @Test
    fun runnerRunsInParallelRetriesTimesOutSkipsAndAddsWork() = runBlocking {
        val plan = DecisionEngine.plan(RequestIntent(setOf(Goal.NEW_EDIT, Goal.CLEAN_AUDIO, Goal.CAPTIONS), creative = true), talky, listOf(gemma, spark, whisper), budget, policy)
        val running = ConcurrentHashMap<AgentId, Boolean>()
        var maxConcurrent = 0
        val runner = WorkflowRunner(
            run = { task ->
                running[task.agent] = true
                maxConcurrent = maxOf(maxConcurrent, running.size)
                try {
                    when {
                        // The planner model fails once; its fallback (the other model) succeeds.
                        task.agent == AgentId.EDIT_PLANNER && task.step.modelId == "spark" -> AgentOutcome.Fail("bad JSON")
                        // Vision hangs: the timeout stops it.
                        task.agent == AgentId.VISUAL_INTELLIGENCE -> { delay(5_000); AgentOutcome.Done() }
                        // Conditional: no music to work with.
                        task.agent == AgentId.MUSIC_DIRECTOR -> AgentOutcome.Skip("no song")
                        // Dynamic: QA asks for another look at the audio.
                        task.agent == AgentId.QUALITY_ASSURANCE -> AgentOutcome.Done(addAgents = setOf(AgentId.AUDIO_ENHANCEMENT))
                        else -> { delay(20); AgentOutcome.Done() }
                    }
                } finally {
                    running.remove(task.agent)
                }
            },
            timeoutFor = { if (it == AgentId.VISUAL_INTELLIGENCE) 100 else 2_000 },
        )
        val trace = runner.execute(plan)
        val planner = trace.filter { it.agent == AgentId.EDIT_PLANNER }
        assertEquals(TaskStatus.FAILED, planner.first().status)
        assertEquals(TaskStatus.SUCCEEDED, planner.last().status)
        assertEquals(2, planner.last().attempt)
        assertEquals(TaskStatus.TIMED_OUT, trace.first { it.agent == AgentId.VISUAL_INTELLIGENCE }.status)
        assertEquals(TaskStatus.SKIPPED, trace.single { it.agent == AgentId.MUSIC_DIRECTOR }.status)
        // Independent work ran side by side.
        assertTrue("max $maxConcurrent", maxConcurrent >= 2)
        // AUDIO_ENHANCEMENT was already done, so the dynamic request doesn't run it twice.
        assertEquals(1, trace.count { it.agent == AgentId.AUDIO_ENHANCEMENT })
        // Every entry is traced with its runtime.
        assertTrue(trace.all { TraceEntry.decode(it.encode()) == it })
    }
}
