package com.cinemind.core.domain.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicLayoutTest {

    @Test
    fun longSongIsCutToTheVideo() {
        assertEquals(
            listOf(MusicSegment(10_000_000, 40_000_000, 0)),
            MusicLayout.segments(musicDurationUs = 180_000_000, startOffsetUs = 10_000_000, totalUs = 30_000_000),
        )
    }

    @Test
    fun shortSongLoopsFromTheTop() {
        val s = MusicLayout.segments(musicDurationUs = 10_000_000, startOffsetUs = 4_000_000, totalUs = 25_000_000)
        assertEquals(
            listOf(
                MusicSegment(4_000_000, 10_000_000, 0),
                MusicSegment(0, 10_000_000, 6_000_000),
                MusicSegment(0, 9_000_000, 16_000_000),
            ),
            s,
        )
        assertEquals(25_000_000L, s.sumOf { it.lengthUs })
    }

    @Test
    fun offsetIsClampedInsideTheSong() {
        val s = MusicLayout.segments(musicDurationUs = 5_000_000, startOffsetUs = 99_000_000, totalUs = 1_000_000)
        assertTrue(s.first().sourceInUs < 5_000_000)
        assertEquals(1_000_000L, s.sumOf { it.lengthUs })
    }

    @Test
    fun emptyInputsGiveNoSegments() {
        assertTrue(MusicLayout.segments(0, 0, 10_000_000).isEmpty())
        assertTrue(MusicLayout.segments(10_000_000, 0, 0).isEmpty())
    }

    @Test
    fun fadesRampVolumeAtBothEnds() {
        val total = 10_000_000L
        assertEquals(0f, MusicLayout.gainAt(0, total, 0.8f, 1_000_000, 2_000_000), 0.0001f)
        assertEquals(0.4f, MusicLayout.gainAt(500_000, total, 0.8f, 1_000_000, 2_000_000), 0.0001f)
        assertEquals(0.8f, MusicLayout.gainAt(5_000_000, total, 0.8f, 1_000_000, 2_000_000), 0.0001f)
        assertEquals(0.4f, MusicLayout.gainAt(9_000_000, total, 0.8f, 1_000_000, 2_000_000), 0.0001f)
        assertEquals(0f, MusicLayout.gainAt(total, total, 0.8f, 1_000_000, 2_000_000), 0.0001f)
        assertEquals(0.8f, MusicLayout.gainAt(0, total, 0.8f, 0, 0), 0.0001f)
    }
}
