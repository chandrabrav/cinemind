package com.cinemind.feature.media.importer

import com.cinemind.feature.media.importer.MediaMetadataParsing.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaMetadataParsingTest {

    @Test
    fun rotationOf90Or270SwapsDimensions() {
        // Phones record portrait video as landscape frames + a 90° rotation flag.
        assertEquals(Size(1080, 1920), MediaMetadataParsing.orientedSize(1920, 1080, 90))
        assertEquals(Size(1080, 1920), MediaMetadataParsing.orientedSize(1920, 1080, 270))
        assertEquals(Size(1080, 1920), MediaMetadataParsing.orientedSize(1920, 1080, -90))
    }

    @Test
    fun rotationOf0Or180KeepsDimensions() {
        assertEquals(Size(1920, 1080), MediaMetadataParsing.orientedSize(1920, 1080, 0))
        assertEquals(Size(1920, 1080), MediaMetadataParsing.orientedSize(1920, 1080, 180))
        assertEquals(Size(1920, 1080), MediaMetadataParsing.orientedSize(1920, 1080, 360))
    }

    @Test
    fun parseIntToleratesMissingAndGarbage() {
        assertEquals(0, MediaMetadataParsing.parseIntOr(null))
        assertEquals(7, MediaMetadataParsing.parseIntOr("abc", 7))
        assertEquals(90, MediaMetadataParsing.parseIntOr(" 90 "))
    }

    @Test
    fun durationMsBecomesMicroseconds() {
        assertEquals(12_345_000L, MediaMetadataParsing.durationMsToUs("12345"))
        assertEquals(0L, MediaMetadataParsing.durationMsToUs(null))
        assertEquals(0L, MediaMetadataParsing.durationMsToUs("-5"))
    }

    @Test
    fun frameRateFromCountAndDuration() {
        assertEquals(30f, MediaMetadataParsing.frameRateFrom(300, 10_000_000)!!, 0.001f)
        assertNull(MediaMetadataParsing.frameRateFrom(null, 10_000_000))
        assertNull(MediaMetadataParsing.frameRateFrom(300, 0))
    }

    @Test
    fun plausibleFrameRateBounds() {
        assertTrue(MediaMetadataParsing.isPlausibleFrameRate(29.97f))
        assertTrue(MediaMetadataParsing.isPlausibleFrameRate(240f))
        assertFalse(MediaMetadataParsing.isPlausibleFrameRate(0f))
        assertFalse(MediaMetadataParsing.isPlausibleFrameRate(90_000f))
        assertFalse(MediaMetadataParsing.isPlausibleFrameRate(null))
    }
}
