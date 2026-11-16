package com.cinemind.feature.teleprompter

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.cinemind.core.common.CineMindLogger
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Voice-follow for the teleprompter. Android lets only one app hear the microphone at a time, so
 * CineMind captures the sound itself: it is written to a WAV file for the take (joined to the
 * camera video afterwards) and, at the same time, fed to the phone's **on-device** speech
 * recogniser (Android 13+, nothing leaves the phone), whose partial results move the prompter.
 */
class VoiceFollow(private val context: Context) {

    companion object {
        const val RATE = 48_000
        private const val ASR_RATE = 16_000

        /** Voice-follow needs Android 13+ (feeding audio to the recogniser) and an on-device recogniser. */
        fun available(context: Context): Boolean =
            Build.VERSION.SDK_INT >= 33 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
    }

    private val main = Handler(Looper.getMainLooper())
    private var record: AudioRecord? = null
    private var thread: Thread? = null
    @Volatile private var running = false
    private var wav: RandomAccessFile? = null
    private var wavBytes = 0L
    @Volatile private var asrOut: OutputStream? = null
    private var recognizer: SpeechRecognizer? = null
    private var onHeard: ((String) -> Unit)? = null
    private var language = "en-IN"

    /** Starts capturing into [wavFile]; [heard] gets the words recognised so far (main thread). */
    @SuppressLint("MissingPermission") // asked for before the camera opens
    fun start(wavFile: File, languageTag: String, heard: (String) -> Unit) {
        if (running) return
        onHeard = heard
        language = languageTag
        val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(MediaRecorder.AudioSource.CAMCORDER, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, RATE / 5 * 2))
        check(rec.state == AudioRecord.STATE_INITIALIZED) { "The microphone isn't available" }
        record = rec
        wav = RandomAccessFile(wavFile, "rw").apply { setLength(0); write(ByteArray(44)) }
        wavBytes = 0
        running = true
        rec.startRecording()
        startRecognizer()
        thread = Thread({ pump(rec) }, "teleprompter-audio").apply { start() }
    }

    private fun pump(rec: AudioRecord) {
        val buf = ShortArray(RATE / 20) // 50 ms
        val bytes = ByteBuffer.allocate(buf.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        val down = ByteBuffer.allocate(buf.size / 3 * 2 + 2).order(ByteOrder.LITTLE_ENDIAN)
        while (running) {
            val n = rec.read(buf, 0, buf.size)
            if (n <= 0) continue
            bytes.clear()
            for (i in 0 until n) bytes.putShort(buf[i])
            runCatching { wav?.write(bytes.array(), 0, n * 2) }
            wavBytes += n * 2
            // 48 kHz → 16 kHz for the recogniser (average of 3).
            down.clear()
            var i = 0
            while (i + 2 < n) {
                down.putShort(((buf[i] + buf[i + 1] + buf[i + 2]) / 3).toShort())
                i += 3
            }
            val out = asrOut
            if (out != null) runCatching { out.write(down.array(), 0, down.position()) }.onFailure { asrOut = null }
        }
    }

    private fun startRecognizer() {
        if (Build.VERSION.SDK_INT < 33) return
        main.post {
            if (!running) return@post
            runCatching {
                val pipe = ParcelFileDescriptor.createPipe()
                asrOut = FileOutputStream(pipe[1].fileDescriptor).also { it.flush() }
                val r = recognizer ?: SpeechRecognizer.createOnDeviceSpeechRecognizer(context).also { recognizer = it }
                r.setRecognitionListener(object : RecognitionListener {
                    override fun onPartialResults(partialResults: Bundle?) = deliver(partialResults)
                    override fun onResults(results: Bundle?) {
                        deliver(results)
                        restart(pipe)
                    }
                    override fun onSegmentResults(segmentResults: Bundle) = deliver(segmentResults)
                    override fun onEndOfSegmentedSession() = restart(pipe)
                    override fun onError(error: Int) {
                        CineMindLogger.w("Voice-follow recogniser error $error")
                        restart(pipe)
                    }
                    override fun onReadyForSpeech(params: Bundle?) = Unit
                    override fun onBeginningOfSpeech() = Unit
                    override fun onRmsChanged(rmsdB: Float) = Unit
                    override fun onBufferReceived(buffer: ByteArray?) = Unit
                    override fun onEndOfSpeech() = Unit
                    override fun onEvent(eventType: Int, params: Bundle?) = Unit
                })
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipe[0])
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, ASR_RATE)
                    // Keep listening across pauses until the take ends.
                    putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
                }
                r.startListening(intent)
            }.onFailure { CineMindLogger.w("Voice-follow couldn't start: ${it.message}") }
        }
    }

    private fun restart(pipe: Array<ParcelFileDescriptor>) {
        asrOut = null
        runCatching { pipe[1].close() }
        runCatching { pipe[0].close() }
        if (running) main.postDelayed({ startRecognizer() }, 50)
    }

    private fun deliver(bundle: Bundle?) {
        val text = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: return
        if (text.isNotBlank()) onHeard?.invoke(text)
    }

    /** Stops capturing and finishes the WAV file (header written). */
    fun stop() {
        if (!running) return
        running = false
        runCatching { thread?.join(500) }
        runCatching { record?.stop() }
        runCatching { record?.release() }
        record = null
        asrOut = null
        main.post {
            runCatching { recognizer?.cancel() }
            runCatching { recognizer?.destroy() }
            recognizer = null
        }
        runCatching {
            wav?.let { f ->
                f.seek(0)
                f.write(header(wavBytes))
                f.close()
            }
        }
        wav = null
    }

    private fun header(dataBytes: Long): ByteArray {
        val b = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()); b.putInt((36 + dataBytes).toInt()); b.put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()); b.putInt(16); b.putShort(1); b.putShort(1); b.putInt(RATE); b.putInt(RATE * 2); b.putShort(2); b.putShort(16)
        b.put("data".toByteArray()); b.putInt(dataBytes.toInt())
        return b.array()
    }
}
