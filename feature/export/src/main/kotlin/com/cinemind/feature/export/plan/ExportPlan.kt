package com.cinemind.feature.export.plan

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Output resolution, named by the short side so it works for vertical and horizontal projects. */
enum class ExportResolution(val shortSide: Int, val label: String) {
    P720(720, "720p"),
    P1080(1080, "1080p"),
    P2160(2160, "4K"),
}

enum class ExportQuality(val label: String, internal val bitsPerPixel: Double) {
    STANDARD("Standard", 0.12),
    HIGH("High", 0.20),
}

data class ExportSettings(
    val resolution: ExportResolution = ExportResolution.P1080,
    val frameRate: Int = 30,
    val quality: ExportQuality = ExportQuality.HIGH,
)

data class OutputSize(val width: Int, val height: Int)

/** Pure export arithmetic: output size, bitrate, file-size estimate and file name. */
object ExportPlanner {

    val FRAME_RATES = listOf(24, 25, 30, 60)
    const val AUDIO_BITRATE_BPS = 192_000
    private const val MIN_VIDEO_BITRATE_BPS = 2_000_000
    private const val MAX_VIDEO_BITRATE_BPS = 80_000_000

    /**
     * Scales the project frame so its short side equals the chosen resolution, keeping the aspect
     * ratio. Dimensions are rounded to even numbers, which hardware encoders require.
     */
    fun outputSize(projectWidth: Int, projectHeight: Int, resolution: ExportResolution): OutputSize {
        require(projectWidth > 0 && projectHeight > 0) { "Project size must be positive" }
        val scale = resolution.shortSide.toDouble() / minOf(projectWidth, projectHeight)
        return OutputSize(even(projectWidth * scale), even(projectHeight * scale))
    }

    private fun even(value: Double): Int = ((value / 2).roundToInt() * 2).coerceAtLeast(2)

    /** Video bitrate from pixels per second × bits-per-pixel for the chosen quality, clamped to a sane range. */
    fun videoBitrateBps(size: OutputSize, frameRate: Int, quality: ExportQuality): Int {
        val raw = size.width.toDouble() * size.height * frameRate * quality.bitsPerPixel
        return raw.roundToLong().coerceIn(MIN_VIDEO_BITRATE_BPS.toLong(), MAX_VIDEO_BITRATE_BPS.toLong()).toInt()
    }

    /** Rough output size (video + audio + ~2% container overhead). */
    fun estimatedSizeBytes(durationUs: Long, videoBitrateBps: Int, withAudio: Boolean = true): Long {
        if (durationUs <= 0) return 0
        val bps = videoBitrateBps.toLong() + if (withAudio) AUDIO_BITRATE_BPS else 0
        return (bps * durationUs / 8 / 1_000_000 * 1.02).roundToLong()
    }

    /** e.g. "CineMind_Goa_trip_20261111_142530.mp4" — safe characters only, bounded length. */
    fun fileName(projectName: String, now: Date = Date()): String {
        val safe = projectName
            .replace(Regex("[^A-Za-z0-9]+"), "_")
            .trim('_')
            .take(40)
            .ifEmpty { "Video" }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(now)
        return "CineMind_${safe}_$stamp.mp4"
    }
}
