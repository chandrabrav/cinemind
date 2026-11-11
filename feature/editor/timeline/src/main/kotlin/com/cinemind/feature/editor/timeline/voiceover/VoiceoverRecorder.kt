package com.cinemind.feature.editor.timeline.voiceover

import android.content.Context
import android.media.MediaMetadataRetriever
import android.media.MediaRecorder
import android.os.SystemClock
import com.cinemind.core.common.CineMindLogger
import java.io.File
import java.util.UUID

/** A finished take, not yet on the timeline. */
data class VoiceTake(val file: File, val durationUs: Long)

/**
 * Records narration with the phone's microphone (AAC, 48 kHz mono, 128 kbit/s) into permanent app
 * storage (`files/voiceovers`, kept with the project, never the cache). Supports pause/resume and
 * exposes the input level for a live waveform. Main thread.
 */
class VoiceoverRecorder(private val context: Context) {

    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var startedAtMs = 0L
    private var pausedTotalMs = 0L
    private var pausedAtMs = 0L

    val isRecording: Boolean get() = recorder != null
    var isPaused: Boolean = false
        private set

    private fun dir() = File(context.filesDir, "voiceovers").apply { mkdirs() }

    /** Starts recording; returns false (with nothing left behind) if the microphone can't be used. */
    fun start(): Boolean {
        if (recorder != null) return true
        val out = File(dir(), "voice-${UUID.randomUUID()}.m4a")
        val r = if (android.os.Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        return try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioSamplingRate(48_000)
            r.setAudioChannels(1)
            r.setAudioEncodingBitRate(128_000)
            r.setOutputFile(out.absolutePath)
            r.prepare()
            r.start()
            recorder = r
            file = out
            startedAtMs = SystemClock.elapsedRealtime()
            pausedTotalMs = 0
            isPaused = false
            true
        } catch (e: Exception) {
            CineMindLogger.w("Microphone unavailable: ${e.message}")
            runCatching { r.release() }
            out.delete()
            false
        }
    }

    fun pause() {
        val r = recorder ?: return
        if (isPaused) return
        runCatching { r.pause() }.onSuccess { isPaused = true; pausedAtMs = SystemClock.elapsedRealtime() }
    }

    fun resume() {
        val r = recorder ?: return
        if (!isPaused) return
        runCatching { r.resume() }.onSuccess {
            isPaused = false
            pausedTotalMs += SystemClock.elapsedRealtime() - pausedAtMs
        }
    }

    /** Recorded time so far (pauses excluded). */
    fun elapsedMs(): Long = when {
        recorder == null -> 0
        isPaused -> pausedAtMs - startedAtMs - pausedTotalMs
        else -> SystemClock.elapsedRealtime() - startedAtMs - pausedTotalMs
    }

    /** Input level 0..1 since the last call (for the live waveform). */
    fun level(): Float = runCatching { (recorder?.maxAmplitude ?: 0) / 32767f }.getOrDefault(0f).coerceIn(0f, 1f)

    /** Stops and returns the take, or null if it was too short or the file is unusable. */
    fun stop(): VoiceTake? {
        val r = recorder ?: return null
        val out = file
        recorder = null
        file = null
        isPaused = false
        val ok = runCatching { r.stop() }.isSuccess // throws if nothing was recorded
        runCatching { r.release() }
        if (!ok || out == null) {
            out?.delete()
            return null
        }
        val durationUs = runCatching {
            MediaMetadataRetriever().use { m ->
                m.setDataSource(out.absolutePath)
                (m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) * 1_000
            }
        }.getOrDefault(0L)
        if (durationUs < 300_000) {
            out.delete()
            return null
        }
        return VoiceTake(out, durationUs)
    }

    /** Stops and throws the recording away. */
    fun cancel() {
        stop()?.file?.delete()
    }

    fun release() {
        if (recorder != null) cancel()
    }
}
