package com.cinemind.feature.media.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaLabelsTest {

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
