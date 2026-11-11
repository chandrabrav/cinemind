package com.cinemind.feature.captions.speech

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext

/** The file has no audio track, or it can't be decoded on this phone. */
class NoAudioException(message: String) : Exception(message)

/**
 * Decodes the audio of a video (or audio file) between two source times into 16 kHz mono floats
 * for speech recognition, using the phone's own decoders (MediaCodec). Originals are only read.
 */
class AudioExtractor(private val context: Context) {

    /** At most this much audio per call (20 min ≈ 77 MB of floats). */
    private val maxUs = 20 * 60 * 1_000_000L

    suspend fun extract(uri: Uri, startUs: Long, endUs: Long): FloatArray = withContext(Dispatchers.IO) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            try {
                extractor.setDataSource(context, uri, null)
            } catch (e: java.io.IOException) {
                throw NoAudioException("Can't read this clip (moved or deleted?)")
            }
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw NoAudioException("This clip has no sound")
            val format = extractor.getTrackFormat(track)
            extractor.selectTrack(track)
            extractor.seekTo(startUs.coerceAtLeast(0), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val stopUs = minOf(endUs, startUs + maxUs)

            val mime = format.getString(MediaFormat.KEY_MIME)!!
            codec = try {
                MediaCodec.createDecoderByType(mime).apply { configure(format, null, null, 0); start() }
            } catch (t: Throwable) {
                throw NoAudioException("This phone can't decode the clip's sound ($mime)")
            }

            var channels = format.getIntegerOrNull(MediaFormat.KEY_CHANNEL_COUNT) ?: 1
            var rate = format.getIntegerOrNull(MediaFormat.KEY_SAMPLE_RATE) ?: 44_100
            var resampler = StreamingResampler(rate, SPEECH_SAMPLE_RATE)
            val out = FloatChunks()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            while (!outputDone) {
                coroutineContext.ensureActive()
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val buffer = codec.getInputBuffer(inIndex)!!
                        val size = extractor.readSampleData(buffer, 0)
                        val time = extractor.sampleTime
                        if (size < 0 || time > stopUs) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size, time, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = codec.outputFormat
                        channels = f.getIntegerOrNull(MediaFormat.KEY_CHANNEL_COUNT) ?: channels
                        val newRate = f.getIntegerOrNull(MediaFormat.KEY_SAMPLE_RATE) ?: rate
                        if (newRate != rate) { rate = newRate; resampler = StreamingResampler(rate, SPEECH_SAMPLE_RATE) }
                    }
                    outIndex >= 0 -> {
                        if (info.size > 0) {
                            val buffer = codec.getOutputBuffer(outIndex)!!.order(ByteOrder.nativeOrder())
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            val shorts = ShortArray(info.size / 2)
                            buffer.asShortBuffer().get(shorts)
                            var mono = Pcm.downmix(shorts, shorts.size, channels)
                            // Drop audio before the requested start (we seeked to a sync point before it).
                            val bufferStart = info.presentationTimeUs
                            if (bufferStart < startUs) {
                                val skip = ((startUs - bufferStart) * rate / 1_000_000L).toInt().coerceIn(0, mono.size)
                                mono = mono.copyOfRange(skip, mono.size)
                            }
                            val bufferEndUs = bufferStart + mono.size * 1_000_000L / rate
                            if (bufferEndUs > stopUs) {
                                val keep = (mono.size - ((bufferEndUs - stopUs) * rate / 1_000_000L).toInt()).coerceIn(0, mono.size)
                                mono = mono.copyOfRange(0, keep)
                            }
                            if (mono.isNotEmpty() && bufferStart < stopUs) out.add(resampler.push(mono))
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
            out.toArray()
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }

    private fun MediaFormat.getIntegerOrNull(key: String): Int? = if (containsKey(key)) getInteger(key) else null

    private class FloatChunks {
        private val chunks = mutableListOf<FloatArray>()
        private var size = 0
        fun add(a: FloatArray) { if (a.isNotEmpty()) { chunks += a; size += a.size } }
        fun toArray(): FloatArray {
            val out = FloatArray(size)
            var p = 0
            for (c in chunks) { c.copyInto(out, p); p += c.size }
            return out
        }
    }
}
