package com.cinemind.feature.export.engine

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.cinemind.core.domain.audio.EnhanceLevel
import com.cinemind.core.domain.audio.VoiceEnhance
import com.cinemind.core.domain.audio.VoiceEnhancer
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Runs [VoiceEnhancer] on 16-bit PCM (each channel separately) inside Media3, for preview and
 * export alike. Sample-aligned: the held-back tail is written at the end of the stream.
 */
@OptIn(UnstableApi::class)
internal class VoiceEnhanceAudioProcessor(private val level: EnhanceLevel) : BaseAudioProcessor() {

    private var enhancers: List<VoiceEnhancer> = emptyList()

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        if (level == EnhanceLevel.OFF) return AudioProcessor.AudioFormat.NOT_SET
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val channels = inputAudioFormat.channelCount
        val frameBytes = 2 * channels
        val frames = inputBuffer.remaining() / frameBytes
        if (frames == 0) {
            inputBuffer.position(inputBuffer.limit())
            return
        }
        val split = Array(channels) { FloatArray(frames) }
        val src = inputBuffer.order(ByteOrder.nativeOrder())
        for (f in 0 until frames) for (c in 0 until channels) split[c][f] = src.getShort() / 32768f
        inputBuffer.position(inputBuffer.limit())
        write(split.mapIndexed { c, x -> enhancersFor(channels)[c].process(x) })
    }

    override fun onQueueEndOfStream() {
        if (enhancers.isNotEmpty()) write(enhancers.map { it.finish() })
        enhancers = emptyList()
    }

    private fun write(out: List<FloatArray>) {
        val frames = out.minOfOrNull { it.size } ?: 0
        if (frames == 0) return
        val buf = replaceOutputBuffer(frames * 2 * out.size)
        for (f in 0 until frames) for (c in out.indices) {
            val v = (out[c][f] * 32767f).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            buf.putShort(v.toShort())
        }
        buf.flip()
    }

    private fun enhancersFor(channels: Int): List<VoiceEnhancer> {
        if (enhancers.size != channels) enhancers = List(channels) { VoiceEnhancer(inputAudioFormat.sampleRate, level) }
        return enhancers
    }

    override fun onFlush() {
        enhancers = emptyList()
    }

    override fun onReset() {
        enhancers = emptyList()
    }
}

/** Per-project voice enhancer setting (kept beside the project, like the layer order). */
object VoiceEnhanceStore {
    private fun prefs(c: Context) = c.getSharedPreferences("voice_enhance", Context.MODE_PRIVATE)
    fun get(c: Context, projectId: String): VoiceEnhance = VoiceEnhance.decode(prefs(c).getString(projectId, null))
    fun set(c: Context, projectId: String, value: VoiceEnhance) {
        prefs(c).edit().putString(projectId, value.encode()).apply()
    }
}
