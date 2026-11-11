package com.cinemind.core.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectFactoryTest {

    private fun video(w: Int, h: Int, durUs: Long, fps: Float = 30f) =
        MediaAsset(uri = "content://v/$w$h$durUs", mimeType = "video/mp4", width = w, height = h, durationUs = durUs, frameRate = fps)

    private fun image(w: Int = 4000, h: Int = 3000) =
        MediaAsset(uri = "content://i/$w$h", mimeType = "image/jpeg", width = w, height = h, durationUs = 0, frameRate = 0f)

    @Test
    fun clipsArePlacedBackToBackInSelectionOrder() {
        val a = video(1080, 1920, 2_000_000)
        val b = image()
        val c = video(1080, 1920, 5_500_000)

        val t = ProjectFactory.fromAssets("P", listOf(a, b, c), now = 42)

        assertEquals(listOf(a.id, b.id, c.id), t.clips.map { it.assetId })
        assertEquals(listOf(0L, 2_000_000L, 5_000_000L), t.clips.map { it.timelineInUs })
        assertEquals(listOf(2_000_000L, 5_000_000L, 10_500_000L), t.clips.map { it.timelineOutUs })
        assertEquals(10_500_000L, t.project.durationUs)
    }

    @Test
    fun sourceRangeCoversWholeClipAndMatchesTimelineLength() {
        val t = ProjectFactory.fromAssets("P", listOf(video(1920, 1080, 7_000_000), image()))
        t.clips.forEach { clip ->
            assertEquals(0L, clip.sourceInUs)
            assertEquals(clip.timelineOutUs - clip.timelineInUs, clip.sourceOutUs - clip.sourceInUs)
        }
    }

    @Test
    fun imagesGetDefaultDuration() {
        val t = ProjectFactory.fromAssets("P", listOf(image()))
        assertEquals(ProjectFactory.DEFAULT_IMAGE_DURATION_US, t.clips.single().timelineOutUs)
    }

    @Test
    fun allClipsLiveOnOneVideoTrackOfTheProject() {
        val t = ProjectFactory.fromAssets("P", listOf(video(1080, 1920, 1_000_000), image()))
        val track = t.tracks.single()
        assertEquals(TrackType.VIDEO, track.type)
        assertEquals(t.project.id, track.projectId)
        assertTrue(t.clips.all { it.trackId == track.id })
    }

    @Test
    fun portraitFirstClipGivesVerticalProject() {
        val p = ProjectFactory.fromAssets("P", listOf(video(1080, 1920, 1_000_000))).project
        assertEquals("9:16", p.aspectRatio)
        assertEquals(1080, p.resolutionWidth)
        assertEquals(1920, p.resolutionHeight)
    }

    @Test
    fun landscapeFirstClipGivesHorizontalProject() {
        val p = ProjectFactory.fromAssets("P", listOf(video(3840, 2160, 1_000_000))).project
        assertEquals("16:9", p.aspectRatio)
        assertEquals(1920, p.resolutionWidth)
        assertEquals(1080, p.resolutionHeight)
    }

    @Test
    fun frameRateComesFromFirstVideoAndIsNormalized() {
        val p = ProjectFactory.fromAssets("P", listOf(image(), video(1080, 1920, 1_000_000, fps = 59.94f))).project
        assertEquals(60, p.frameRate)
    }

    @Test
    fun normalizeFrameRateSnapsToStandardRates() {
        assertEquals(30, ProjectFactory.normalizeFrameRate(null))
        assertEquals(30, ProjectFactory.normalizeFrameRate(Float.NaN))
        assertEquals(24, ProjectFactory.normalizeFrameRate(23.976f))
        assertEquals(25, ProjectFactory.normalizeFrameRate(25f))
        assertEquals(30, ProjectFactory.normalizeFrameRate(29.97f))
        assertEquals(60, ProjectFactory.normalizeFrameRate(59.94f))
        assertEquals(60, ProjectFactory.normalizeFrameRate(120f))
    }

    @Test
    fun emptySelectionIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            ProjectFactory.fromAssets("P", emptyList())
        }
    }
}
