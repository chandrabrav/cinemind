package com.cinemind.feature.captions.speech

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Speech models expect 16 kHz mono. */
const val SPEECH_SAMPLE_RATE = 16_000

/** Pure audio helpers (unit-tested). */
object Pcm {

    /** Interleaved 16-bit PCM → mono floats in -1..1. */
    fun downmix(interleaved: ShortArray, count: Int, channels: Int): FloatArray {
        val ch = channels.coerceAtLeast(1)
        val frames = count / ch
        val out = FloatArray(frames)
        for (f in 0 until frames) {
            var sum = 0f
            for (c in 0 until ch) sum += interleaved[f * ch + c]
            out[f] = sum / ch / 32768f
        }
        return out
    }

    /** RMS level of [samples] in dBFS (-100 for silence). */
    fun dbfs(samples: FloatArray, from: Int = 0, to: Int = samples.size): Float {
        if (to <= from) return -100f
        var acc = 0.0
        for (i in from until to) acc += samples[i] * samples[i]
        val rms = sqrt(acc / (to - from))
        return if (rms < 1e-5) -100f else (20 * log10(rms)).toFloat()
    }

    fun toPcm16(samples: FloatArray, from: Int = 0, to: Int = samples.size): ByteArray {
        val buf = ByteBuffer.allocate((to - from) * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in from until to) buf.putShort((samples[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort())
        return buf.array()
    }

    /** A complete WAV file (RIFF, PCM 16-bit mono). */
    fun wav(samples: FloatArray, from: Int, to: Int, sampleRate: Int = SPEECH_SAMPLE_RATE): ByteArray {
        val pcm = toPcm16(samples, from, to)
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + pcm.size); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1)
            putInt(sampleRate); putInt(sampleRate * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(pcm.size)
        }
        return ByteArrayOutputStream(44 + pcm.size).apply { write(header.array()); write(pcm) }.toByteArray()
    }

    fun writeWav(file: File, samples: FloatArray, from: Int, to: Int) = file.writeBytes(wav(samples, from, to))
}

/**
 * Linear-interpolation resampler that works on a stream of buffers (so a long clip never has to be
 * held at its original rate). Good enough for speech recognition; not for music.
 */
class StreamingResampler(private val inRate: Int, private val outRate: Int) {
    private val step = inRate.toDouble() / outRate
    private var pos = 0.0
    private var last = 0f
    private var hasLast = false

    fun push(input: FloatArray): FloatArray {
        if (inRate == outRate) return input
        if (input.isEmpty()) return input
        // Virtual array = [last] + input, index 0 is `last` when we have one.
        val offset = if (hasLast) 1 else 0
        val size = input.size + offset
        fun at(i: Int): Float = if (hasLast && i == 0) last else input[i - offset]
        val out = ArrayList<Float>((input.size / step).toInt() + 2)
        while (pos + 1 < size) {
            val i = pos.toInt()
            val frac = (pos - i).toFloat()
            out += at(i) * (1 - frac) + at(i + 1) * frac
            pos += step
        }
        pos -= (size - 1)
        last = input.last()
        hasLast = true
        return out.toFloatArray()
    }
}

/**
 * Finds stretches of speech in 16 kHz mono audio by loudness, so each one can be transcribed on
 * its own (the model takes ≤ 30 s at a time) and gets real start/end times. The threshold adapts
 * to the recording's noise floor. Pure; unit-tested.
 */
object SpeechSegmenter {

    const val FRAME_MS = 30
    private const val FRAME = SPEECH_SAMPLE_RATE * FRAME_MS / 1000

    data class Segment(val startSample: Int, val endSample: Int) {
        val startUs: Long get() = startSample * 1_000_000L / SPEECH_SAMPLE_RATE
        val endUs: Long get() = endSample * 1_000_000L / SPEECH_SAMPLE_RATE
    }

    fun segments(
        samples: FloatArray,
        minSilenceMs: Int = 400,
        maxSegmentMs: Int = 12_000,
        minSpeechMs: Int = 300,
        padMs: Int = 150,
    ): List<Segment> {
        val frames = samples.size / FRAME
        if (frames == 0) return emptyList()
        val db = FloatArray(frames) { Pcm.dbfs(samples, it * FRAME, (it + 1) * FRAME) }
        val sorted = db.sortedArray()
        val floor = sorted[(frames * 0.2).toInt().coerceAtMost(frames - 1)]
        val peak = sorted[(frames * 0.98).toInt().coerceAtMost(frames - 1)]
        if (peak < -50f) return emptyList() // effectively silent
        // Above the noise floor, but never so high that continuous speech (floor ≈ speech) is missed.
        val threshold = max(floor + 9f, -55f).coerceAtMost(peak - 15f)
        val loud = BooleanArray(frames) { db[it] > threshold }

        // Group loud frames, bridging short pauses.
        val gap = minSilenceMs / FRAME_MS
        val raw = mutableListOf<IntRange>()
        var start = -1
        var lastLoud = -1
        for (f in 0 until frames) {
            if (loud[f]) {
                if (start < 0) start = f
                else if (f - lastLoud > gap) { raw += start..lastLoud; start = f }
                lastLoud = f
            }
        }
        if (start >= 0) raw += start..lastLoud

        val maxFrames = maxSegmentMs / FRAME_MS
        val split = raw.flatMap { splitLong(it, db, maxFrames) }
        val pad = padMs / FRAME_MS
        return split
            .filter { (it.last - it.first + 1) * FRAME_MS >= minSpeechMs }
            .map { r ->
                Segment(
                    startSample = ((r.first - pad).coerceAtLeast(0)) * FRAME,
                    endSample = min(samples.size, (r.last + 1 + pad) * FRAME),
                )
            }
            .fold(mutableListOf()) { acc, s ->
                // Padding can make neighbours overlap: trim at the midpoint.
                val prev = acc.lastOrNull()
                if (prev != null && s.startSample < prev.endSample) {
                    val mid = (prev.endSample + s.startSample) / 2
                    acc[acc.lastIndex] = prev.copy(endSample = mid)
                    acc += s.copy(startSample = mid)
                } else {
                    acc += s
                }
                acc
            }
    }

    /** Splits a too-long range at its quietest frame (searching the middle half), recursively. */
    private fun splitLong(r: IntRange, db: FloatArray, maxFrames: Int): List<IntRange> {
        val len = r.last - r.first + 1
        if (len <= maxFrames) return listOf(r)
        val from = r.first + len / 4
        val to = r.last - len / 4
        var cut = from
        for (f in from..to) if (db[f] < db[cut]) cut = f
        return splitLong(r.first until cut, db, maxFrames) + splitLong(cut..r.last, db, maxFrames)
    }
}
