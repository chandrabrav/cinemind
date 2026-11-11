package com.cinemind.feature.media.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaLabelsTest {

    @Test
    fun durationFormatting() {
        assertEquals("0:00", formatDuration(0))
        assertEquals("0:09", formatDuration(9_999_999))
        assertEquals("2:05", formatDuration(125_000_000))
        assertEquals("1:00:01", formatDuration(3_601_000_000))
    }

    @Test
    fun qualityUsesShorterSideSoPortraitAndLandscapeMatch() {
        assertEquals("1080p", qualityLabel(1080, 1920))
        assertEquals("1080p", qualityLabel(1920, 1080))
        assertEquals("4K", qualityLabel(2160, 3840))
        assertEquals("720p", qualityLabel(1280, 720))
        assertEquals("480p", qualityLabel(854, 480))
        assertEquals("?", qualityLabel(0, 0))
    }
}
