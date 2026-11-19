package com.cinemind.core.domain.aigen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AiGenTest {

    private val both = AiReadiness(gemmaReady = true, claudeReady = true)
    private val none = AiReadiness(gemmaReady = false, claudeReady = false)

    @Test
    fun timelineAndRenderAlwaysStayNative() {
        for (mode in AiMode.entries) for (r in listOf(both, none)) {
            assertEquals(AiEngine.NATIVE, AiRouter.route(AiTask.EDIT_TIMELINE, mode, r))
            assertEquals(AiEngine.NATIVE, AiRouter.route(AiTask.RENDER, mode, r))
        }
    }

    @Test
    fun offlineNeverReachesTheCloud() {
        assertEquals(AiEngine.GEMMA, AiRouter.route(AiTask.PLAN_EDIT, AiMode.OFFLINE, both))
        assertEquals(AiEngine.HEURISTIC, AiRouter.route(AiTask.PLAN_EDIT, AiMode.OFFLINE, none))
        assertEquals(AiEngine.UNAVAILABLE, AiRouter.route(AiTask.REFERENCE_REMAKE, AiMode.OFFLINE, both))
        assertEquals(AiEngine.UNAVAILABLE, AiRouter.route(AiTask.GENERATE_MEDIA, AiMode.OFFLINE, both))
        assertFalse(AiRouter.cloudPlanningAllowed(AiMode.OFFLINE, claudeReady = true))
    }

    @Test
    fun gemmaStaysPrimaryAndCloudOnlyWhenReady() {
        assertEquals(AiEngine.GEMMA, AiRouter.route(AiTask.UNDERSTAND_PROMPT, AiMode.CLOUD, both))
        assertEquals(AiEngine.GEMMA, AiRouter.route(AiTask.ANALYSE_FOOTAGE, AiMode.CLOUD, both))
        assertEquals(AiEngine.CLAUDE, AiRouter.route(AiTask.PLAN_EDIT, AiMode.HYBRID, both))
        assertEquals(AiEngine.GEMMA, AiRouter.route(AiTask.PLAN_EDIT, AiMode.CLOUD, both.copy(claudeReady = false)))
        assertEquals(AiEngine.HANDOFF, AiRouter.route(AiTask.REFERENCE_REMAKE, AiMode.HYBRID, none))
        assertTrue(AiRouter.cloudPlanningAllowed(AiMode.HYBRID, claudeReady = true))
        assertFalse(AiRouter.cloudPlanningAllowed(AiMode.CLOUD, claudeReady = false))
    }

    @Test
    fun unknownStoredModeFallsBackToHybrid() {
        assertEquals(AiMode.HYBRID, AiMode.from(null))
        assertEquals(AiMode.HYBRID, AiMode.from("SOMETHING_OLD"))
        assertEquals(AiMode.OFFLINE, AiMode.from("OFFLINE"))
    }

    @Test
    fun katanaBriefUsesTheSkillCommandAndStatesOnlyWhatWasAsked() {
        val b = HandoffBriefs.compose(GenerationProviders.KATANA, "  keep the camera moves,\n put me in red  ", 8_000_000L, 2, "9:16")
        assertTrue(b.ready)
        assertTrue(b.text.startsWith("/katana "))
        assertTrue(b.text.contains("subjects in the attached photos"))
        assertTrue(b.text.contains("keep the camera moves, put me in red."))
        assertTrue(b.text.contains("9:16"))
    }

    @Test
    fun briefProblemsBlockSending() {
        val noRef = HandoffBriefs.compose(GenerationProviders.KATANA, "", null, 0, null)
        assertFalse(noRef.ready)
        assertTrue(noRef.problems.any { it.contains("reference") })

        val tooMany = HandoffBriefs.compose(GenerationProviders.KATANA, "", 5_000_000L, 6, null)
        assertTrue(tooMany.problems.any { it.contains("5 photos") })

        val tooShort = HandoffBriefs.compose(GenerationProviders.KATANA, "", 300_000L, 1, null)
        assertTrue(tooShort.problems.any { it.contains("too short") })
    }

    @Test
    fun jobsOnlyReportWhatTheAppCanSee() {
        val job = GenerationJob(projectId = "p", providerId = GenerationProviders.KATANA.id, task = AiTask.REFERENCE_REMAKE.name,
            instructions = "", brief = "/katana x", referenceUri = "content://r", photoUris = "content://a\ncontent://b", createdAt = 1)
        assertEquals(listOf("content://a", "content://b"), job.photos)

        val sent = job.moveTo(JobStatus.HANDED_OFF, 2)
        assertEquals(JobStatus.HANDED_OFF, sent.statusEnum)

        // Can't claim a result without the imported file.
        try {
            sent.moveTo(JobStatus.IMPORTED, 3)
            fail("imported without an asset")
        } catch (_: IllegalArgumentException) {
        }
        val done = sent.moveTo(JobStatus.IMPORTED, 3, outputAssetId = "asset-1")
        assertEquals("asset-1", done.outputAssetId)
        assertTrue(done.statusEnum.terminal)

        // Finished jobs never change again.
        for (next in JobStatus.entries) assertFalse(JobStatus.IMPORTED.canMoveTo(next))
        try {
            job.moveTo(JobStatus.IMPORTED, 2, outputAssetId = "x")
            fail("skipped the handoff")
        } catch (_: IllegalArgumentException) {
        }
    }
}
