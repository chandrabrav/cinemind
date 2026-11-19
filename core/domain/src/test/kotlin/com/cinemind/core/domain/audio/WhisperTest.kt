package com.cinemind.core.domain.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class WhisperTest {

    @Test
    fun specialTokensMatchTheWhisperTokenizer() {
        assertEquals(99, WhisperTokens.LANGUAGES.size)
        assertEquals(50259, WhisperTokens.languageToken("en"))
        assertEquals(50276, WhisperTokens.languageToken("hi"))
        assertEquals(50276, WhisperTokens.languageToken("hi-en"))
        assertEquals(WhisperTokens.TRANSLATE - 1, WhisperTokens.LAST_LANGUAGE)
        assertEquals("hi", WhisperTokens.languageOf(50276))
        assertNull(WhisperTokens.languageToken("xx"))
        assertTrue(WhisperTokens.prompt(50276, translate = true).contentEquals(intArrayOf(50258, 50276, 50358)))
    }

    @Test
    fun melHasWhisperShapeAndFindsATone() {
        val tone = FloatArray(WhisperMel.SAMPLE_RATE * 2) { (0.5 * sin(2 * PI * 1_000 * it / WhisperMel.SAMPLE_RATE)).toFloat() }
        val mel = WhisperMel.compute(tone)
        assertEquals(WhisperMel.N_MELS * WhisperMel.FRAMES, mel.size)
        // Whisper clamps to 8 log10 units below the peak: the range is at most 2 after scaling.
        assertTrue(mel.max() - mel.min() <= 2.0001f)
        // In a frame during the tone, the strongest mel band is the one around 1 kHz.
        val f = 100
        val strongest = (0 until WhisperMel.N_MELS).maxBy { mel[it * WhisperMel.FRAMES + f] }
        val centreHz = WhisperMel.filters[strongest].withIndex().maxBy { it.value }.index * 40
        assertTrue("band at $centreHz Hz", centreHz in 800..1_250)
        // After the 2 s of tone the padded silence sits at the clamp floor.
        val quiet = mel[strongest * WhisperMel.FRAMES + 2_500]
        assertTrue(quiet < mel[strongest * WhisperMel.FRAMES + f] - 1f)
    }

    @Test
    fun byteLevelTokensDecodeToText() {
        // "Ġ" is the byte-level form of a space; "à¤" bytes make Devanagari.
        val vocab = WhisperVocab(listOf("Hello", "Ġworld", "!", "à¤¨", "à¤®"))
        assertEquals("Hello world!", vocab.text(listOf(0, 1, 2)))
        assertEquals("नम", vocab.text(listOf(3, 4)))
        assertEquals("Hello", vocab.text(listOf(0, WhisperTokens.FIRST_TIMESTAMP, WhisperTokens.END)))
    }

    @Test
    fun timestampsBecomeSegments() {
        val vocab = WhisperVocab(listOf("Hi", "Ġthere", "Bye"))
        val t = { s: Double -> WhisperTokens.FIRST_TIMESTAMP + kotlin.math.round(s * 50).toInt() }
        val tokens = listOf(t(0.0), 0, 1, t(1.5), t(2.0), 2, t(3.0), WhisperTokens.END)
        val segs = WhisperSegments.parse(tokens, vocab, offsetUs = 10_000_000, limitUs = 40_000_000)
        assertEquals(2, segs.size)
        assertEquals(SpeechSegment(10_000_000, 11_500_000, "Hi there"), segs[0])
        assertEquals(SpeechSegment(12_000_000, 13_000_000, "Bye"), segs[1])
        // A timestamp beyond the real audio is clamped, never invented.
        val late = WhisperSegments.parse(listOf(t(0.0), 2, t(29.0)), vocab, 0, 5_000_000)
        assertEquals(5_000_000L, late.single().endUs)
    }

    @Test
    fun decodingRulesKeepTimestampsInOrder() {
        val logits = FloatArray(WhisperTokens.VOCAB)
        logits[5] = 3f // a text token
        logits[WhisperTokens.FIRST_TIMESTAMP + 10] = 2f
        logits[WhisperTokens.FIRST_TIMESTAMP + 2] = 4f
        // First step: always a timestamp, even when text scores higher.
        assertEquals(WhisperTokens.FIRST_TIMESTAMP + 2, WhisperSegments.pick(logits, 0, 0, WhisperTokens.FIRST_TIMESTAMP, false, true))
        // Timestamps can't go backwards: below the last one only text competes.
        assertEquals(5, WhisperSegments.pick(logits, 0, 3, WhisperTokens.FIRST_TIMESTAMP + 50, false, false))
        // Two timestamps in a row: text next.
        assertEquals(5, WhisperSegments.pick(logits, 0, 4, WhisperTokens.FIRST_TIMESTAMP, true, true))
    }

    @Test
    fun hindiHeardAsUrduIsTreatedAsHindi() {
        assertEquals("hi", WhisperTokens.spokenLanguage("ur"))
        assertEquals("hi", WhisperTokens.spokenLanguage("hi"))
        assertEquals("en", WhisperTokens.spokenLanguage("en"))
    }
}
