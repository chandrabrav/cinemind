package com.cinemind.feature.media.importer

/** Pure helpers for interpreting raw container metadata. Kept Android-free so they are unit-testable. */
object MediaMetadataParsing {

    data class Size(val width: Int, val height: Int)

    /** Applies the container's rotation so width/height describe the picture as displayed. */
    fun orientedSize(width: Int, height: Int, rotationDegrees: Int): Size {
        val normalized = ((rotationDegrees % 360) + 360) % 360
        return if (normalized == 90 || normalized == 270) Size(height, width) else Size(width, height)
    }

    /** Parses an integer metadata string, returning [default] when absent or malformed. */
    fun parseIntOr(value: String?, default: Int = 0): Int = value?.trim()?.toIntOrNull() ?: default

    /** Parses a millisecond duration string into microseconds (0 when absent or malformed). */
    fun durationMsToUs(value: String?): Long =
        (value?.trim()?.toLongOrNull() ?: 0L).coerceAtLeast(0L) * 1_000L

    /** Average frame rate from a frame count and duration; null when either is unusable. */
    fun frameRateFrom(frameCount: Int?, durationUs: Long): Float? {
        if (frameCount == null || frameCount <= 0 || durationUs <= 0) return null
        return frameCount * 1_000_000f / durationUs
    }

    /** True when a frame rate is plausible for camera/phone video. */
    fun isPlausibleFrameRate(fps: Float?): Boolean = fps != null && fps in 1f..480f
}
