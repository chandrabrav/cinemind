package com.cinemind.feature.ai.generation.smartedit

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.cinemind.core.common.CineMindLogger
import com.cinemind.feature.ai.llm.ondevice.OnDeviceLlm
import com.cinemind.feature.captions.speech.WhisperEngine
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Speak instead of typing: records the microphone (only while the user holds the listening
 * dialog open, at most a minute) and turns it into text with Whisper on the phone. Speech in
 * another language (Hindi, Hinglish…) becomes an English prompt, which the planners read best.
 * The recording stays in memory and is dropped afterwards; nothing is saved or uploaded.
 */
@Singleton
class VoicePrompt @Inject constructor(
    @ApplicationContext private val context: Context,
    private val llm: OnDeviceLlm,
) {
    /** True when Whisper is installed, so speech is recognised on the phone. */
    val onDevice: Boolean get() = llm.speechModel() != null

    @Volatile private var record: AudioRecord? = null
    @Volatile private var thread: Thread? = null
    @Volatile var level: Float = 0f
        private set
    private val lock = Any()
    private var samples = ShortArray(RATE * 8)
    private var count = 0

    @SuppressLint("MissingPermission") // The screen asks for the microphone first.
    fun start(): Boolean {
        stopRecording()
        synchronized(lock) { count = 0 }
        return runCatching {
            val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, RATE / 2))
            check(rec.state == AudioRecord.STATE_INITIALIZED) { "The microphone isn't available" }
            rec.startRecording()
            record = rec
            thread = Thread({ pump(rec) }, "voice-prompt").apply { start() }
            true
        }.onFailure { CineMindLogger.w("Voice prompt: ${it.message}") }.getOrDefault(false)
    }

    private fun pump(rec: AudioRecord) {
        val buf = ShortArray(RATE / 10)
        while (record === rec) {
            val n = rec.read(buf, 0, buf.size)
            if (n <= 0) continue
            var peak = 0
            for (i in 0 until n) peak = maxOf(peak, abs(buf[i].toInt()))
            level = (peak / 32768f).coerceIn(0f, 1f)
            synchronized(lock) {
                if (count + n > MAX_SAMPLES) return@synchronized
                if (count + n > samples.size) samples = samples.copyOf(minOf(MAX_SAMPLES, samples.size * 2).coerceAtLeast(count + n))
                System.arraycopy(buf, 0, samples, count, n)
                count += n
            }
        }
    }

    private fun stopRecording() {
        val rec = record ?: return
        record = null
        runCatching { thread?.join(500) }
        thread = null
        runCatching { rec.stop() }
        rec.release()
        level = 0f
    }

    /** Stops listening and drops what was heard. */
    fun cancel() {
        stopRecording()
        synchronized(lock) { count = 0 }
    }

    /** Stops listening and returns the words (empty if nothing was understood). */
    suspend fun finish(): String {
        stopRecording()
        val audio = synchronized(lock) { FloatArray(count) { samples[it] / 32768f }.also { count = 0 } }
        if (audio.size < RATE / 2) return ""
        val spec = llm.speechModel() ?: return ""
        return withContext(Dispatchers.Default) {
            runCatching {
                WhisperEngine(context, llm.fileOf(spec)).use { engine ->
                    val language = engine.detectLanguage(audio)
                    engine.transcribe(audio, language, translate = language != "en").joinToString(" ") { it.text.trim() }.replace(Regex("\s+"), " ").trim()
                }
            }.onFailure { CineMindLogger.w("Voice prompt transcription failed: ${it.message}") }.getOrDefault("")
        }
    }

    private companion object {
        const val RATE = 16_000
        const val MAX_SAMPLES = RATE * 60
    }
}
