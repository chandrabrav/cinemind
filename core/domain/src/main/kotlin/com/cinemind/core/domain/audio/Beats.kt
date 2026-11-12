package com.cinemind.core.domain.audio

import com.cinemind.core.domain.editing.SpeedMath
import com.cinemind.core.domain.editing.TimelineEditing
import com.cinemind.core.domain.model.Clip
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sqrt

/** Loudness outline of a sound for drawing: one peak (0..1) per [bucketUs] of audio. */
object Waveform {
    const val BUCKET_US = 20_000L

    /** Peak level per bucket of [bucketSamples] samples, normalised so the loudest bucket is 1. */
    fun peaks(samples: FloatArray, bucketSamples: Int): FloatArray {
        if (samples.isEmpty() || bucketSamples <= 0) return FloatArray(0)
        val n = (samples.size + bucketSamples - 1) / bucketSamples
        val out = FloatArray(n)
        var loudest = 0f
        for (b in 0 until n) {
            var m = 0f
            val end = minOf(samples.size, (b + 1) * bucketSamples)
            for (i in b * bucketSamples until end) m = max(m, abs(samples[i]))
            out[b] = m
            loudest = max(loudest, m)
        }
        if (loudest > 1e-6f) for (b in 0 until n) out[b] = out[b] / loudest
        return out
    }

    /** Peaks for the source range [fromUs, toUs) resampled to [count] values (max of what each covers). */
    fun slice(peaks: FloatArray, fromUs: Long, toUs: Long, count: Int): FloatArray {
        if (count <= 0 || peaks.isEmpty() || toUs <= fromUs) return FloatArray(maxOf(count, 0))
        val out = FloatArray(count)
        val span = (toUs - fromUs).toDouble() / count
        for (i in 0 until count) {
            val a = ((fromUs + span * i) / BUCKET_US).toInt().coerceIn(0, peaks.lastIndex)
            val b = ((fromUs + span * (i + 1)) / BUCKET_US).toInt().coerceIn(a, peaks.lastIndex)
            var m = 0f
            for (k in a..b) m = max(m, peaks[k])
            out[i] = m
        }
        return out
    }
}

/** Result of beat tracking: tempo and beat times in the song's own time. */
data class BeatInfo(val bpm: Float, val beatsUs: List<Long>, val confidence: Float) {
    val periodUs: Long get() = if (bpm > 0) (60_000_000.0 / bpm).roundToLong() else 0L

    fun encode(): String = "bpm=$bpm;conf=$confidence;beats=" + beatsUs.joinToString(",")

    companion object {
        fun decode(text: String?): BeatInfo? {
            if (text.isNullOrBlank()) return null
            val m = text.split(';').associate { it.substringBefore('=') to it.substringAfter('=', "") }
            val bpm = m["bpm"]?.toFloatOrNull() ?: return null
            val beats = m["beats"].orEmpty().split(',').mapNotNull { it.toLongOrNull() }
            return BeatInfo(bpm, beats, m["conf"]?.toFloatOrNull() ?: 0f)
        }
    }
}

/**
 * Beat tracking for music, on 16 kHz mono samples (what the speech decoder already produces):
 * 1. onset strength = rise of log energy in 10 ms hops (half-wave rectified, smoothed);
 * 2. tempo = autocorrelation peak of the onset curve between 60 and 190 BPM, weighted toward ~120;
 * 3. phase = the beat grid offset that lands on the strongest onsets;
 * 4. each beat nudged to the strongest onset within ±12 % of a beat (follows small tempo drift).
 * Pure Kotlin; unit-tested on synthetic click tracks.
 */
object BeatDetector {

    const val SAMPLE_RATE = 16_000
    private const val HOP = 160 // 10 ms
    private const val HOP_US = 10_000L

    fun onsetEnvelope(samples: FloatArray): FloatArray {
        val frames = samples.size / HOP
        if (frames < 3) return FloatArray(0)
        val energy = FloatArray(frames)
        for (f in 0 until frames) {
            var e = 0.0
            val base = f * HOP
            for (i in 0 until HOP) { val s = samples[base + i]; e += s * s }
            energy[f] = ln(1e-6 + e / HOP).toFloat()
        }
        val onset = FloatArray(frames)
        for (f in 1 until frames) onset[f] = max(0f, energy[f] - energy[f - 1])
        // Light smoothing (3 hops) and removal of the slow-moving average.
        val smooth = FloatArray(frames)
        for (f in 0 until frames) {
            var s = 0f
            var n = 0
            for (k in -1..1) { val j = f + k; if (j in 0 until frames) { s += onset[j]; n++ } }
            smooth[f] = s / n
        }
        val win = 50
        val out = FloatArray(frames)
        var run = 0.0
        for (f in 0 until frames) {
            run += smooth[f]
            if (f >= win) run -= smooth[f - win]
            val mean = run / minOf(f + 1, win)
            out[f] = max(0f, (smooth[f] - mean).toFloat())
        }
        return out
    }

    fun detect(samples: FloatArray, minBpm: Float = 60f, maxBpm: Float = 190f): BeatInfo? {
        val env = onsetEnvelope(samples)
        if (env.size < 300) return null // < 3 s
        val minLag = (6000f / maxBpm).roundToInt().coerceAtLeast(2) // hops per beat (100 hops/s)
        val maxLag = (6000f / minBpm).roundToInt().coerceAtMost(env.size / 3)
        if (maxLag <= minLag) return null
        var bestLag = -1
        var bestScore = 0.0
        var total = 0.0
        val scores = DoubleArray(maxLag + 1)
        for (lag in minLag..maxLag) {
            var s = 0.0
            for (i in lag until env.size) s += env[i] * env[i - lag]
            s /= (env.size - lag)
            // Prefer tempos near 120 BPM (log-Gaussian), as people tap.
            val bpm = 6000.0 / lag
            val w = exp(-0.5 * (ln(bpm / 120.0) / 0.9).let { it * it })
            scores[lag] = s * w
            total += scores[lag]
            if (scores[lag] > bestScore) { bestScore = scores[lag]; bestLag = lag }
        }
        if (bestLag < 0 || bestScore <= 0.0) return null
        // Sub-hop tempo: parabolic interpolation around the peak.
        val lag = if (bestLag in (minLag + 1) until maxLag) {
            val a = scores[bestLag - 1]; val b = scores[bestLag]; val c = scores[bestLag + 1]
            val d = (a - c) / (2 * (a - 2 * b + c)).let { if (abs(it) < 1e-12) 1e12 else it }
            bestLag + d.coerceIn(-0.5, 0.5)
        } else bestLag.toDouble()
        val bpm = (6000.0 / lag).toFloat()

        // Phase: offset in one period with the most onset energy on the grid.
        val period = lag
        var bestPhase = 0
        var bestPhaseScore = -1.0
        for (p in 0 until bestLag) {
            var s = 0.0
            var t = p.toDouble()
            while (t < env.size) { s += env[t.toInt()]; t += period }
            if (s > bestPhaseScore) { bestPhaseScore = s; bestPhase = p }
        }
        val beats = ArrayList<Long>()
        val reach = (period * 0.12).roundToInt().coerceAtLeast(1)
        var t = bestPhase.toDouble()
        while (t < env.size) {
            val c = t.roundToInt()
            var best = c
            for (k in (c - reach)..(c + reach)) if (k in env.indices && env[k] > env[best.coerceIn(env.indices)]) best = k
            beats += best.coerceIn(env.indices) * HOP_US
            t += period
        }
        val mean = total / (maxLag - minLag + 1)
        val confidence = (1.0 - mean / bestScore).toFloat().coerceIn(0f, 1f)
        return BeatInfo(bpm, beats.distinct(), confidence)
    }
}

/** Beat-matched cutting of the main track. */
object BeatSync {

    /** Beats of the song as timeline times, following how [MusicLayout] places (and loops) it. */
    fun beatsOnTimeline(beats: BeatInfo, musicDurationUs: Long, startOffsetUs: Long, totalUs: Long): List<Long> =
        MusicLayout.segments(musicDurationUs, startOffsetUs, totalUs).flatMap { seg ->
            beats.beatsUs.filter { it >= seg.sourceInUs && it < seg.sourceOutUs }.map { seg.timelineStartUs + (it - seg.sourceInUs) }
        }

    /**
     * Shortens (never lengthens) main-track clips so every cut lands on a beat, taking every
     * [every]-th beat. Videos can only get shorter; stills may also grow up to the next beat.
     * Ramped clips are kept as they are (their timing is the ramp). Returns a relaid-out list.
     */
    fun cutToBeats(clips: List<Clip>, beatsTimelineUs: List<Long>, every: Int, isStill: (Clip) -> Boolean, minClipUs: Long = 400_000): List<Clip> {
        if (clips.isEmpty() || beatsTimelineUs.isEmpty()) return clips
        val grid = beatsTimelineUs.sorted().filterIndexed { i, _ -> i % every.coerceAtLeast(1) == 0 }
        val out = ArrayList<Clip>()
        var cursor = 0L
        for ((i, clip) in clips.withIndex()) {
            val length = SpeedMath.timelineDurationUs(clip)
            val ramped = SpeedMath.decode(clip.speedCurve).size >= 2
            val last = i == clips.lastIndex
            if (ramped || last) {
                out += clip
                cursor += length
                continue
            }
            val still = isStill(clip)
            val maxEnd = if (still) cursor + TimelineEditing.MAX_STILL_US else cursor + length
            val wanted = cursor + length
            val beat = grid.filter { it >= cursor + minClipUs && it <= maxEnd }.minByOrNull { abs(it - wanted) }
            if (beat == null) {
                out += clip
                cursor += length
                continue
            }
            val newLen = beat - cursor
            val speed = SpeedMath.clampSpeed(clip.speed)
            val sourceLen = if (still) newLen else (newLen * speed.toDouble()).roundToLong()
            out += if (still) clip.copy(sourceInUs = 0, sourceOutUs = sourceLen) else clip.copy(sourceOutUs = clip.sourceInUs + sourceLen)
            cursor += SpeedMath.timelineDurationUs(out.last())
        }
        return TimelineEditing.relayout(out)
    }

    /** How far each cut is from the nearest beat (for "x of y cuts on the beat"). */
    fun offBeatUs(clips: List<Clip>, beatsTimelineUs: List<Long>): List<Long> =
        clips.dropLast(1).map { c -> beatsTimelineUs.minOfOrNull { abs(it - c.timelineOutUs) } ?: Long.MAX_VALUE }

    /** Root-mean-square helper for tests / diagnostics. */
    internal fun rms(a: FloatArray): Float = sqrt(a.fold(0.0) { s, v -> s + v * v } / a.size.coerceAtLeast(1)).toFloat()
}
