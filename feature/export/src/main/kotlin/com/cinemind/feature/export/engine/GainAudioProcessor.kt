package com.cinemind.feature.export.engine

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer

/**
 * Scales 16-bit PCM by a gain that can change over time ([gainAt] receives microseconds since the
 * start of this media item). Used for music volume + fades and for the clips' own volume.
 */
@OptIn(UnstableApi::class)
internal class GainAudioProcessor(private val gainAt: (Long) -> Float) : BaseAudioProcessor() {

    private var framesProcessed = 0L

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val channels = inputAudioFormat.channelCount
        val sampleRate = inputAudioFormat.sampleRate
        val out = replaceOutputBuffer(remaining)
        val frameBytes = 2 * channels
        while (inputBuffer.remaining() >= frameBytes) {
            val gain = gainAt(framesProcessed * 1_000_000L / sampleRate)
            repeat(channels) {
                val scaled = (inputBuffer.getShort() * gain).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                out.putShort(scaled.toShort())
            }
            framesProcessed++
        }
        // Drop any trailing partial frame (never expected for PCM).
        inputBuffer.position(inputBuffer.limit())
        out.flip()
    }

    override fun onFlush() {
        framesProcessed = 0
    }

    override fun onReset() {
        framesProcessed = 0
    }
}
