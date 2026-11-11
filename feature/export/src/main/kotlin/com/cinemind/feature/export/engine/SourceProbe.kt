package com.cinemind.feature.export.engine

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.cinemind.core.domain.model.MediaAsset

/**
 * One-line technical summary of the sources of an export (codec, size, frame rate, HDR), written
 * to the export journal so a failure on a phone can be tied to the footage that caused it.
 * Reads only container metadata (no decoding). Never includes file names or locations.
 */
internal object SourceProbe {

    fun describe(context: Context, assets: List<MediaAsset>): String =
        assets.distinctBy { it.id }.take(12).joinToString("; ") { a -> describe(context, a) }

    private fun describe(context: Context, a: MediaAsset): String {
        if (!a.isVideo) return "photo ${a.width}x${a.height}"
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, Uri.parse(a.uri), null)
            val parts = mutableListOf<String>()
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/")) {
                    val w = f.intOrNull(MediaFormat.KEY_WIDTH)
                    val h = f.intOrNull(MediaFormat.KEY_HEIGHT)
                    val fps = f.intOrNull(MediaFormat.KEY_FRAME_RATE)
                    val transfer = when (f.intOrNull(MediaFormat.KEY_COLOR_TRANSFER)) {
                        MediaFormat.COLOR_TRANSFER_ST2084 -> "HDR10/PQ"
                        MediaFormat.COLOR_TRANSFER_HLG -> "HLG"
                        MediaFormat.COLOR_TRANSFER_SDR_VIDEO -> "SDR"
                        null -> "SDR?"
                        else -> "transfer ${f.intOrNull(MediaFormat.KEY_COLOR_TRANSFER)}"
                    }
                    val profile = f.intOrNull(MediaFormat.KEY_PROFILE)?.let { " profile $it" } ?: ""
                    parts += "$mime ${w}x$h ${fps ?: "?"}fps $transfer$profile rot ${f.intOrNull(MediaFormat.KEY_ROTATION) ?: 0}"
                } else if (mime.startsWith("audio/")) {
                    parts += "$mime ${f.intOrNull(MediaFormat.KEY_SAMPLE_RATE) ?: "?"}Hz ${f.intOrNull(MediaFormat.KEY_CHANNEL_COUNT) ?: "?"}ch"
                }
            }
            "video ${a.durationUs / 1000} ms [" + parts.joinToString(", ") + "]"
        } catch (e: Exception) {
            "video (unreadable: ${e.javaClass.simpleName})"
        } finally {
            extractor.release()
        }
    }

    private fun MediaFormat.intOrNull(key: String): Int? = if (containsKey(key)) runCatching { getInteger(key) }.getOrNull() else null
}
