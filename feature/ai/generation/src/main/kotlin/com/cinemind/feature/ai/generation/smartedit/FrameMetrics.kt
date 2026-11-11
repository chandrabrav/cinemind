package com.cinemind.feature.ai.generation.smartedit

import kotlin.math.abs

/** One sampled moment of a source. sharpness is raw (normalised later across all footage). */
data class SamplePoint(
    val timeUs: Long,
    val sharpness: Float,
    /** Mean brightness 0..1. */
    val brightness: Float,
    /** Mean absolute change from the previous sample 0..1 (0 for the first sample / photos). */
    val motion: Float,
)

data class AssetAnalysis(
    val assetId: String,
    val isVideo: Boolean,
    val durationUs: Long,
    /** Position of the source in the project (first appearance), used for chronological order. */
    val order: Int,
    val samples: List<SamplePoint>,
)

/** Pure image measurements on a grayscale (luma 0..255) buffer. */
object FrameMetrics {

    /** Rec. 601 luma from packed ARGB pixels. */
    fun luma(argb: IntArray): IntArray = IntArray(argb.size) { i ->
        val c = argb[i]
        val r = (c shr 16) and 0xFF
        val g = (c shr 8) and 0xFF
        val b = c and 0xFF
        (299 * r + 587 * g + 114 * b) / 1000
    }

    fun meanBrightness(luma: IntArray): Float = if (luma.isEmpty()) 0f else luma.average().toFloat() / 255f

    /** Variance of the 4-neighbour Laplacian: high for crisp detail, low for blur. */
    fun laplacianVariance(luma: IntArray, width: Int, height: Int): Float {
        if (width < 3 || height < 3 || luma.size < width * height) return 0f
        var sum = 0.0
        var sumSq = 0.0
        var n = 0
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val i = y * width + x
                val lap = 4 * luma[i] - luma[i - 1] - luma[i + 1] - luma[i - width] - luma[i + width]
                sum += lap
                sumSq += lap.toDouble() * lap
                n++
            }
        }
        val mean = sum / n
        return (sumSq / n - mean * mean).toFloat()
    }

    /** Mean absolute difference between two equally sized frames, 0..1. */
    fun meanAbsDiff(a: IntArray, b: IntArray): Float {
        if (a.isEmpty() || a.size != b.size) return 0f
        var total = 0L
        for (i in a.indices) total += abs(a[i] - b[i])
        return total.toFloat() / a.size / 255f
    }
}

/** Turns raw measurements into a 0..1 "how usable/attractive is this moment" score. */
object MomentScoring {

    fun exposureScore(brightness: Float): Float {
        val d = (brightness - 0.5f) / 0.5f
        return (1f - d * d).coerceIn(0f, 1f)
    }

    /** Some motion is lively; none is static; a lot usually means shake or a hard camera move. */
    fun motionScore(motion: Float): Float = when {
        motion < 0.02f -> 0.55f
        motion <= 0.20f -> 1f
        else -> (1f - (motion - 0.20f) / 0.30f).coerceIn(0f, 1f)
    }

    /** Sharpness normaliser: the 90th percentile of all footage counts as "fully sharp". */
    fun sharpnessReference(all: List<Float>): Float {
        if (all.isEmpty()) return 1f
        val sorted = all.sorted()
        return sorted[((sorted.size - 1) * 0.9).toInt()].coerceAtLeast(1f)
    }

    fun score(sample: SamplePoint, sharpRef: Float, strict: Boolean): Float {
        val sharp = (sample.sharpness / sharpRef).coerceIn(0f, 1f)
        val exposure = exposureScore(sample.brightness)
        val motion = motionScore(sample.motion)
        return if (strict) {
            0.6f * sharp + 0.25f * exposure + 0.15f * motion
        } else {
            0.45f * sharp + 0.30f * exposure + 0.25f * motion
        }
    }
}
