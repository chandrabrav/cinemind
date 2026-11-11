package com.cinemind.core.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectNamesTest {

    @Test
    fun sanitizeTrimsAndCollapsesWhitespace() {
        assertEquals("Goa trip", ProjectNames.sanitize("  Goa \n  trip  "))
    }

    @Test
    fun blankNamesAreRejected() {
        assertNull(ProjectNames.sanitize(""))
        assertNull(ProjectNames.sanitize("   \t "))
    }

    @Test
    fun longNamesAreCut() {
        assertEquals(ProjectNames.MAX_LENGTH, ProjectNames.sanitize("a".repeat(200))!!.length)
    }

    @Test
    fun copyNameStaysWithinLimit() {
        assertEquals("Trip (copy)", ProjectNames.copyOf("Trip"))
        val long = ProjectNames.copyOf("b".repeat(ProjectNames.MAX_LENGTH))
        assertEquals(ProjectNames.MAX_LENGTH, long.length)
        assertTrue(long.endsWith(" (copy)"))
    }
}

class ProjectCopierTest {

    private val video = MediaAsset(uri = "content://v", mimeType = "video/mp4", width = 1080, height = 1920, durationUs = 4_000_000)
    private val photo = MediaAsset(uri = "content://p", mimeType = "image/jpeg", width = 3000, height = 4000, durationUs = 0)
    private val source = ProjectFactory.fromAssets("Original", listOf(video, photo), now = 1)

    @Test
    fun allIdsAreNewAndReferencesPointAtTheCopy() {
        val copy = ProjectCopier.duplicate(source, "Copy", now = 99)

        val oldIds = (listOf(source.project.id) + source.tracks.map { it.id } + source.clips.map { it.id }).toSet()
        val newIds = listOf(copy.project.id) + copy.tracks.map { it.id } + copy.clips.map { it.id }
        assertTrue(newIds.none { it in oldIds })
        assertEquals(newIds.size, newIds.toSet().size)

        assertTrue(copy.tracks.all { it.projectId == copy.project.id })
        val trackIds = copy.tracks.map { it.id }.toSet()
        assertTrue(copy.clips.all { it.trackId in trackIds })
    }

    @Test
    fun mediaTimingAndSettingsArePreserved() {
        val copy = ProjectCopier.duplicate(source, "Copy", now = 99)

        assertEquals(source.clips.map { it.assetId }, copy.clips.map { it.assetId })
        assertEquals(source.clips.map { it.timelineInUs to it.timelineOutUs }, copy.clips.map { it.timelineInUs to it.timelineOutUs })
        assertEquals(source.clips.map { it.sourceInUs to it.sourceOutUs }, copy.clips.map { it.sourceInUs to it.sourceOutUs })
        assertEquals(source.project.durationUs, copy.project.durationUs)
        assertEquals(source.project.aspectRatio, copy.project.aspectRatio)
        assertEquals(source.project.frameRate, copy.project.frameRate)
    }

    @Test
    fun copyGetsNewNameAndTimestamps() {
        val copy = ProjectCopier.duplicate(source, "Copy", now = 99)
        assertEquals("Copy", copy.project.name)
        assertEquals(99L, copy.project.createdAt)
        assertEquals(99L, copy.project.updatedAt)
    }

    @Test
    fun orphanClipsAreDropped() {
        val orphan = source.clips.first().copy(id = "orphan", trackId = "no-such-track")
        val copy = ProjectCopier.duplicate(source.copy(clips = source.clips + orphan), "Copy")
        assertEquals(source.clips.size, copy.clips.size)
    }
}
