package com.cinemind.feature.captions.speech

import com.cinemind.core.domain.captions.CaptionScript
import com.cinemind.core.domain.captions.SpokenLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class PcmTest {

    @Test fun `downmix averages channels`() {
        val stereo = shortArrayOf(1000, 3000, -2000, 2000)
        val mono = Pcm.downmix(stereo, stereo.size, 2)
        assertEquals(2, mono.size)
        assertEquals(2000f / 32768f, mono[0], 1e-6f)
        assertEquals(0f, mono[1], 1e-6f)
    }

    @Test fun `wav header is valid`() {
        val samples = FloatArray(100) { 0.5f }
        val wav = Pcm.wav(samples, 10, 60)
        assertEquals("RIFF", String(wav, 0, 4))
        assertEquals("WAVE", String(wav, 8, 4))
        assertEquals("data", String(wav, 36, 4))
        assertEquals(44 + 50 * 2, wav.size)
        val rate = (wav[24].toInt() and 0xFF) or ((wav[25].toInt() and 0xFF) shl 8) or ((wav[26].toInt() and 0xFF) shl 16)
        assertEquals(16_000, rate)
    }

    @Test fun `dbfs of silence and full scale`() {
        assertEquals(-100f, Pcm.dbfs(FloatArray(100)), 0.01f)
        assertEquals(0f, Pcm.dbfs(FloatArray(100) { if (it % 2 == 0) 1f else -1f }), 0.01f)
    }

    @Test fun `streaming resampler keeps length and tone across buffers`() {
        val inRate = 48_000
        val tone = FloatArray(inRate) { sin(2 * PI * 440 * it / inRate).toFloat() }
        val r = StreamingResampler(inRate, SPEECH_SAMPLE_RATE)
        val out = mutableListOf<Float>()
        // Uneven buffer sizes, like a decoder's.
        var p = 0
        val sizes = intArrayOf(1024, 777, 4096, 333)
        var k = 0
        while (p < tone.size) {
            val n = minOf(sizes[k++ % sizes.size], tone.size - p)
            out += r.push(tone.copyOfRange(p, p + n)).toList()
            p += n
        }
        assertTrue("about 16000 samples, got ${out.size}", abs(out.size - 16_000) <= 2)
        // Compare with the ideal 440 Hz tone at 16 kHz.
        var maxErr = 0f
        for (i in 0 until out.size - 1) maxErr = maxOf(maxErr, abs(out[i] - sin(2 * PI * 440 * i / SPEECH_SAMPLE_RATE).toFloat()))
        assertTrue("linear interpolation error small ($maxErr)", maxErr < 0.02f)
    }
}

class SpeechSegmenterTest {

    private fun signal(vararg parts: Pair<Float, Boolean>, noise: Float = 0.001f): FloatArray {
        val out = mutableListOf<Float>()
        var n = 0
        for ((seconds, voiced) in parts) {
            repeat((seconds * SPEECH_SAMPLE_RATE).toInt()) {
                val v = if (voiced) 0.3f * sin(2 * PI * 220 * n / SPEECH_SAMPLE_RATE).toFloat() else 0f
                val hiss = noise * (((n * 7919) % 200) / 100f - 1f)
                out += v + hiss
                n++
            }
        }
        return out.toFloatArray()
    }

    @Test fun `finds speech with real times`() {
        val s = signal(0.5f to false, 1.0f to true, 1.0f to false, 0.8f to true, 0.5f to false)
        val seg = SpeechSegmenter.segments(s)
        assertEquals(2, seg.size)
        assertTrue(abs(seg[0].startUs - 350_000) < 60_000)
        assertTrue(abs(seg[0].endUs - 1_650_000) < 60_000)
        assertTrue(abs(seg[1].startUs - 2_350_000) < 60_000)
    }

    @Test fun `short pauses stay in one segment`() {
        val s = signal(1.0f to true, 0.2f to false, 1.0f to true)
        assertEquals(1, SpeechSegmenter.segments(s).size)
    }

    @Test fun `silence and clicks give nothing`() {
        assertTrue(SpeechSegmenter.segments(signal(3f to false)).isEmpty())
        assertTrue(SpeechSegmenter.segments(signal(1f to false, 0.1f to true, 1f to false)).isEmpty())
        assertTrue(SpeechSegmenter.segments(FloatArray(0)).isEmpty())
    }

    @Test fun `long speech is split below the model limit`() {
        val s = signal(30f to true)
        val seg = SpeechSegmenter.segments(s, maxSegmentMs = 12_000)
        assertTrue(seg.size >= 3)
        assertTrue(seg.all { it.endUs - it.startUs <= 12_400_000 })
        for (i in 1 until seg.size) assertTrue(seg[i].startSample >= seg[i - 1].endSample)
    }
}

class SpeechPromptsTest {

    @Test fun `hinglish prompt forbids translation and shows the example`() {
        val p = SpeechPrompts.transcribe(SpokenLanguage.HINGLISH, CaptionScript.HINGLISH)
        assertTrue(p.contains("Do not translate"))
        assertTrue(p.contains("Bhai, yeh jagah sach mein bahut beautiful hai!"))
        assertTrue(p.contains("Hinglish"))
        assertTrue(SpeechPrompts.transcribe(SpokenLanguage.HINDI, CaptionScript.NATIVE).contains("Devanagari"))
        assertFalse(SpeechPrompts.transcribe(SpokenLanguage.AUTO, CaptionScript.ROMAN).contains("expected to speak"))
    }

    @Test fun `parses language and text`() {
        val t = SpeechPrompts.parse("LANG: hi-en\nTEXT: Bhai, yeh jagah sach mein bahut beautiful hai!", CaptionScript.HINGLISH, SpokenLanguage.AUTO)!!
        assertEquals("hi-en", t.language)
        assertEquals("Bhai, yeh jagah sach mein bahut beautiful hai!", t.text)
        assertFalse(t.uncertain)
    }

    @Test fun `no speech and empty answers`() {
        assertNull(SpeechPrompts.parse("NO_SPEECH", CaptionScript.ROMAN, SpokenLanguage.AUTO))
        assertNull(SpeechPrompts.parse("LANG: en\nTEXT:   ", CaptionScript.ROMAN, SpokenLanguage.AUTO))
        assertNull(SpeechPrompts.parse("", CaptionScript.ROMAN, SpokenLanguage.AUTO))
    }

    @Test fun `unsure words are flagged and the mark removed`() {
        val t = SpeechPrompts.parse("LANG: en\nTEXT: we went to Kasol [?] last week", CaptionScript.ROMAN, SpokenLanguage.AUTO)!!
        assertTrue(t.uncertain)
        assertEquals("we went to Kasol last week", t.text)
    }

    @Test fun `devanagari answer is transliterated when roman was asked, not translated`() {
        val t = SpeechPrompts.parse("LANG: hi\nTEXT: यह जगह बहुत beautiful है", CaptionScript.HINGLISH, SpokenLanguage.AUTO)!!
        assertEquals("yeh jagah bahut beautiful hai", t.text)
        val native = SpeechPrompts.parse("LANG: hi\nTEXT: यह जगह", CaptionScript.NATIVE, SpokenLanguage.AUTO)!!
        assertEquals("यह जगह", native.text)
    }

    @Test fun `missing labels fall back to the hint language`() {
        val t = SpeechPrompts.parse("\"hello everyone\"", CaptionScript.ROMAN, SpokenLanguage.ENGLISH)!!
        assertEquals("en", t.language)
        assertEquals("hello everyone", t.text)
    }

    @Test fun `repetition loops are collapsed and flagged`() {
        assertEquals("hello hello world", SpeechPrompts.collapseRepeats("hello hello hello hello world"))
        assertEquals("go team go team", SpeechPrompts.collapseRepeats("go team go team go team go team"))
        val t = SpeechPrompts.parse("TEXT: la la la la la la la la la la", CaptionScript.ROMAN, SpokenLanguage.AUTO)!!
        assertTrue(t.uncertain)
    }
}
