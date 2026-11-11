package com.cinemind.feature.ai.llm.ondevice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Decides, from what is on disk after an app update, whether a model is reused or downloaded. */
class ModelDiskRulesTest {

    private val spec = ModelCatalog.GEMMA_4_E2B
    private fun record(sha: String = spec.sha256, format: String = spec.format) =
        ModelRecord(spec.id, spec.fileName, spec.sizeBytes, sha, format, verifiedAtMs = 1, verifiedByAppVersion = 80)

    @Test fun `verified file from a previous app version is reused`() {
        assertEquals(DiskStatus.READY, ModelDiskRules.status(spec, spec.sizeBytes, record(), null, 0))
    }

    @Test fun `older builds' verified marker without a record still counts`() {
        assertEquals(DiskStatus.READY, ModelDiskRules.status(spec, spec.sizeBytes, null, spec.sha256, 0))
    }

    @Test fun `right file but no verification info is re-checked, not downloaded`() {
        assertEquals(DiskStatus.NEEDS_CHECK, ModelDiskRules.status(spec, spec.sizeBytes, null, null, 0))
        assertEquals(DiskStatus.NEEDS_CHECK, ModelDiskRules.status(spec, spec.sizeBytes, record(sha = "00ff"), null, 0))
    }

    @Test fun `wrong size means damaged`() {
        assertEquals(DiskStatus.DAMAGED, ModelDiskRules.status(spec, spec.sizeBytes - 1, record(), spec.sha256, 0))
    }

    @Test fun `interrupted download resumes`() {
        assertEquals(DiskStatus.PARTIAL, ModelDiskRules.status(spec, null, null, null, 1_000))
        assertEquals(DiskStatus.ABSENT, ModelDiskRules.status(spec, null, null, null, 0))
    }

    @Test fun `file verified for another runtime format is kept but flagged`() {
        assertEquals(DiskStatus.INCOMPATIBLE, ModelDiskRules.status(spec, spec.sizeBytes, record(format = "litertlm-0"), null, 0))
        // Records from before formats were tracked are treated as current.
        assertEquals(DiskStatus.READY, ModelDiskRules.status(spec, spec.sizeBytes, record(format = ""), null, 0))
    }

    @Test fun `record round-trips through properties and rejects junk`() {
        val r = record()
        assertEquals(r, ModelRecord.fromProperties(r.toProperties()))
        assertNull(ModelRecord.fromProperties(java.util.Properties()))
    }
}
