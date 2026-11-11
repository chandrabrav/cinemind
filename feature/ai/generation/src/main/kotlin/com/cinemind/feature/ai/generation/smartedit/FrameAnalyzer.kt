package com.cinemind.feature.ai.generation.smartedit

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.cinemind.core.common.CineMindLogger
import com.cinemind.core.domain.model.MediaAsset
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import javax.inject.Inject
import kotlin.coroutines.coroutineContext

/**
 * Samples small frames from a source and measures sharpness, brightness and motion.
 * Fully on-device; nothing leaves the phone. Works on tiny (≤128 px) frames to stay fast.
 */
class FrameAnalyzer @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    suspend fun analyze(asset: MediaAsset, order: Int): AssetAnalysis = withContext(Dispatchers.Default) {
        val samples = try {
            if (asset.isVideo) sampleVideo(asset) else samplePhoto(asset)
        } catch (e: Exception) {
            CineMindLogger.w("Analysis failed for ${asset.displayName}: ${e.message}")
            emptyList()
        }
        AssetAnalysis(
            assetId = asset.id,
            isVideo = asset.isVideo,
            durationUs = asset.durationUs,
            order = order,
            // Unreadable sources still take part, with a neutral score.
            samples = samples.ifEmpty { listOf(SamplePoint(0, 0f, 0.5f, 0.05f)) },
        )
    }

    private suspend fun sampleVideo(asset: MediaAsset): List<SamplePoint> {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, Uri.parse(asset.uri))
            val count = (asset.durationUs / SAMPLE_SPACING_US).toInt().coerceIn(3, MAX_SAMPLES)
            val out = mutableListOf<SamplePoint>()
            var previous: IntArray? = null
            for (k in 0 until count) {
                coroutineContext.ensureActive()
                val t = asset.durationUs * (2 * k + 1) / (2L * count)
                val frame = retriever.getScaledFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, SIZE, SIZE)
                    ?: continue
                val (luma, w, h) = lumaOf(frame)
                frame.recycle()
                val motion = previous?.takeIf { it.size == luma.size }?.let { FrameMetrics.meanAbsDiff(it, luma) } ?: 0f
                out += SamplePoint(t, FrameMetrics.laplacianVariance(luma, w, h), FrameMetrics.meanBrightness(luma), motion)
                previous = luma
            }
            return out
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun samplePhoto(asset: MediaAsset): List<SamplePoint> {
        val uri = Uri.parse(asset.uri)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= SIZE) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: return emptyList()
        val (luma, w, h) = lumaOf(bitmap)
        bitmap.recycle()
        return listOf(SamplePoint(0, FrameMetrics.laplacianVariance(luma, w, h), FrameMetrics.meanBrightness(luma), 0f))
    }

    private fun lumaOf(bitmap: Bitmap): Triple<IntArray, Int, Int> {
        // Normalise size so sharpness is comparable between sources of different resolutions.
        val scaled = if (bitmap.width > SIZE || bitmap.height > SIZE) {
            val s = SIZE.toFloat() / maxOf(bitmap.width, bitmap.height)
            Bitmap.createScaledBitmap(bitmap, (bitmap.width * s).toInt().coerceAtLeast(3), (bitmap.height * s).toInt().coerceAtLeast(3), true)
        } else {
            bitmap
        }
        val px = IntArray(scaled.width * scaled.height)
        scaled.getPixels(px, 0, scaled.width, 0, 0, scaled.width, scaled.height)
        val result = Triple(FrameMetrics.luma(px), scaled.width, scaled.height)
        if (scaled !== bitmap) scaled.recycle()
        return result
    }

    private companion object {
        const val SIZE = 128
        const val SAMPLE_SPACING_US = 700_000L
        const val MAX_SAMPLES = 48
    }
}
