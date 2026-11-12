package com.cinemind.core.domain.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class SoundEffectsTest {

    @Test
    fun everyEffectIsAudibleBoundedAndTheRightLength() {
        SoundEffect.entries.forEach { e ->
            val s = SfxSynth.render(e)
            assertEquals(e.name, (e.seconds * SfxSynth.SAMPLE_RATE).toInt(), s.size)
            val peak = s.maxOf { abs(it) }
            assertTrue("${e.name} peak $peak", peak in 0.5f..0.9f)
            assertTrue("${e.name} has NaN", s.none { it.isNaN() })
            // Starts and ends quietly (no clicks).
            assertTrue("${e.name} start", abs(s.first()) < 0.01f)
            assertTrue("${e.name} end", abs(s.last()) < 0.05f)
        }
    }

    @Test
    fun renderingIsRepeatable() {
        assertArrayEquals(SfxSynth.render(SoundEffect.WHOOSH), SfxSynth.render(SoundEffect.WHOOSH), 0f)
    }

    @Test
    fun wavHeaderAndSize() {
        val wav = SfxSynth.wav(FloatArray(100) { 0.5f })
        assertEquals(44 + 200, wav.size)
        assertEquals("RIFF", String(wav, 0, 4))
        assertEquals("WAVE", String(wav, 8, 4))
        assertEquals("data", String(wav, 36, 4))
        val first = (wav[44].toInt() and 0xFF) or (wav[45].toInt() shl 8)
        assertEquals(16383, first)
        assertEquals(1_000_000L, SfxSynth.durationUs(44_100))
    }
}
