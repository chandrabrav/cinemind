package com.cinemind.feature.captions.speech

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/**
 * Real decoding on the emulator: the clip's AAC sound (stereo, 48 kHz) → 16 kHz mono → speech
 * stretches with real times. The test clip (made by ffmpeg in CI) has three 1.2 s voice-like bursts
 * starting at 0, 2 and 4 s. This checks the audio half of speech-to-text; the model half needs the
 * downloaded Gemma model and a real phone.
 */
@RunWith(AndroidJUnit4::class)
class SpeechAudioTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private fun asset(name: String): File {
        val out = File(context.cacheDir, "stt-$name")
        instrumentation.context.assets.open(name).use { input -> out.outputStream().use { input.copyTo(it) } }
        return out
    }

    @Test
    fun decodesWholeClipAndFindsTheBursts() = runBlocking {
        val uri = Uri.fromFile(asset("bursts.mp4"))
        val audio = AudioExtractor(context).extract(uri, 0, 6_000_000)
        val seconds = audio.size.toFloat() / SPEECH_SAMPLE_RATE
        assertTrue("about 6 s of 16 kHz audio, got $seconds s", abs(seconds - 6f) < 0.3f)

        val segments = SpeechSegmenter.segments(audio)
        assertEquals("three bursts: ${segments.map { it.startUs to it.endUs }}", 3, segments.size)
        segments.forEachIndexed { i, s ->
            assertTrue("burst $i starts near ${i * 2} s (${s.startUs})", abs(s.startUs - i * 2_000_000L) < 300_000)
            assertTrue("burst $i lasts about 1.2 s (${s.endUs - s.startUs})", (s.endUs - s.startUs) in 1_000_000L..1_700_000L)
        }

        // A WAV the model can read: RIFF header + 16-bit samples.
        val wav = Pcm.wav(audio, segments[0].startSample, segments[0].endSample)
        assertEquals("RIFF", String(wav, 0, 4))
        assertEquals(44 + (segments[0].endSample - segments[0].startSample) * 2, wav.size)
    }

    @Test
    fun decodesOnlyTheTrimmedRange() = runBlocking {
        val uri = Uri.fromFile(asset("bursts.mp4"))
        // Clip trimmed to 1.5 s – 6 s: bursts at 2 s and 4 s become 0.5 s and 2.5 s.
        val audio = AudioExtractor(context).extract(uri, 1_500_000, 6_000_000)
        val seconds = audio.size.toFloat() / SPEECH_SAMPLE_RATE
        assertTrue("about 4.5 s, got $seconds s", abs(seconds - 4.5f) < 0.3f)
        val starts = SpeechSegmenter.segments(audio).map { it.startUs }
        assertEquals("two bursts in range: $starts", 2, starts.size)
        assertTrue("first at ~0.5 s ($starts)", abs(starts[0] - 500_000) < 300_000)
        assertTrue("second at ~2.5 s ($starts)", abs(starts[1] - 2_500_000) < 300_000)
    }

    @Test(expected = NoAudioException::class)
    fun unreadableFileReportsNoSound() = runBlocking<Unit> {
        val bogus = File(context.cacheDir, "not-media.txt").apply { writeText("not a video") }
        AudioExtractor(context).extract(Uri.fromFile(bogus), 0, 1_000_000)
    }
}
