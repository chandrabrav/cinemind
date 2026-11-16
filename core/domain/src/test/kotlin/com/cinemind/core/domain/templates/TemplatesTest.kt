package com.cinemind.core.domain.templates

import com.cinemind.core.domain.color.BuiltInLooks
import com.cinemind.core.domain.color.ColorGrade
import com.cinemind.core.domain.editing.ClipFraming
import com.cinemind.core.domain.editing.ClipTransition
import com.cinemind.core.domain.editing.TimelineEditing
import com.cinemind.core.domain.model.MediaAsset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TemplatesTest {

    private fun video(id: String, durUs: Long) = MediaAsset(id = id, uri = "u$id", mimeType = "video/mp4", width = 1080, height = 1920, durationUs = durUs, frameRate = 30f)
    private fun photo(id: String) = MediaAsset(id = id, uri = "p$id", mimeType = "image/jpeg", width = 1200, height = 900, durationUs = 0)

    @Test
    fun everyTemplateIsWellFormed() {
        assertEquals(Templates.all.size, Templates.all.map { it.id }.toSet().size)
        Templates.all.forEach { t ->
            assertTrue(t.id, t.slots.isNotEmpty())
            t.look?.let { key -> assertNotNull("${t.id} look $key", BuiltInLooks.byKey(key)) }
            t.texts.forEach { assertTrue("${t.id} text ${it.text}", it.endUs < 0 || it.endUs > it.startUs) }
        }
    }

    @Test
    fun clipsFollowSlotsFromTheMiddleOfVideos() {
        val t = Templates.byId("travel_montage")!!
        val assets = (1..8).map { video("$it", 10_000_000) }
        val (timeline, texts) = Templates.apply(t, assets, "Trip")
        assertEquals(8, timeline.clips.size)
        val first = timeline.clips.first()
        assertEquals(t.slots[0].lengthUs, first.timelineOutUs - first.timelineInUs)
        assertEquals((10_000_000 - 2_000_000) / 2, first.sourceInUs)
        assertEquals(ClipTransition.WHIP_LEFT.name, timeline.clips[1].transitionInType)
        assertTrue(ClipFraming.decode(first.framing).hasMotion)
        assertEquals("builtin:teal_orange", ColorGrade.decode(first.grade).lutId)
        assertEquals(t.durationUs, TimelineEditing.durationUs(timeline.clips))
        assertEquals(1080, timeline.project.resolutionWidth)
        assertEquals(1920, timeline.project.resolutionHeight)
        assertEquals(2, texts.size)
        assertEquals(TimelineEditing.durationUs(timeline.clips), texts.last().endUs)
    }

    @Test
    fun speedSlotsUseMoreFootageAndShortVideosKeepTheirLength() {
        val t = Templates.byId("gym_hype")!!
        // Slot 2 is 0.7 s at 1.5x -> 1.05 s of footage; slot 4 is 1.5 s at 0.5x -> 0.75 s.
        val assets = listOf(video("a", 5_000_000), video("b", 5_000_000), video("c", 400_000), video("d", 5_000_000))
        val (timeline, _) = Templates.apply(t, assets, "Gym")
        assertEquals(1_050_000L, timeline.clips[1].sourceOutUs - timeline.clips[1].sourceInUs)
        assertEquals(400_000L, timeline.clips[2].sourceOutUs - timeline.clips[2].sourceInUs)
        assertEquals(750_000L, timeline.clips[3].sourceOutUs - timeline.clips[3].sourceInUs)
        assertEquals(1_500_000L, timeline.clips[3].timelineOutUs - timeline.clips[3].timelineInUs)
    }

    @Test
    fun photosAndExtraMediaRepeatThePattern() {
        val t = Templates.byId("food_reel")!!
        val assets = (1..7).map { photo("$it") }
        val (timeline, _) = Templates.apply(t, assets, "Food")
        assertEquals(7, timeline.clips.size)
        assertEquals(t.slots[0].lengthUs, timeline.clips[5].timelineOutUs - timeline.clips[5].timelineInUs)
        assertEquals(1f, timeline.clips[2].speed)
        assertEquals(ClipTransition.CUT.name, timeline.clips[0].transitionInType)
    }
}
