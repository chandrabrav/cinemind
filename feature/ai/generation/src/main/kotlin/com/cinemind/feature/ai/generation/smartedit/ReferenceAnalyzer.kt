package com.cinemind.feature.ai.generation.smartedit

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import com.cinemind.core.common.CineMindLogger
import com.cinemind.core.domain.aiedit.ReferenceFrame
import com.cinemind.core.domain.aiedit.ReferenceKind
import com.cinemind.core.domain.aiedit.StyleReference
import com.cinemind.core.domain.aiedit.StyleReferences
import com.cinemind.feature.ai.llm.ondevice.OnDeviceLlm
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import kotlin.coroutines.coroutineContext
import kotlin.math.sqrt

/**
 * Studies a sample video or reference image on the phone: how fast it cuts, how much it moves,
 * and its look (brightness, contrast, colour, warmth). If a vision model is installed it also
 * says in one sentence what the reference looks like. Nothing is uploaded or copied.
 */
class ReferenceAnalyzer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val llm: OnDeviceLlm,
) {
    suspend fun analyze(uri: Uri, kind: ReferenceKind, onStep: (String) -> Unit = {}): StyleReference = withContext(Dispatchers.Default) {
        val name = displayName(uri)
        onStep(if (kind == ReferenceKind.VIDEO) "Studying the sample video…" else "Studying the image…")
        val (durationUs, frames, keyFrame) = if (kind == ReferenceKind.VIDEO) sampleVideo(uri) else sampleImage(uri)
        val seen = keyFrame?.let { bmp ->
            try {
                if (llm.installedModel() == null) "" else {
                    onStep("AI is looking at your reference…")
                    describe(bmp)
                }
            } finally {
                bmp.recycle()
            }
        } ?: ""
        StyleReferences.from(kind, name, durationUs, frames, seen)
    }

    private suspend fun sampleVideo(uri: Uri): Triple<Long, List<ReferenceFrame>, Bitmap?> {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(context, uri)
            val durationUs = (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) * 1000
            // Two samples a second over the first 90 s (enough to judge the cutting), at least 6.
            val windowUs = minOf(durationUs, 90_000_000L)
            val count = (windowUs / 500_000L).toInt().coerceIn(6, 180)
            val out = mutableListOf<ReferenceFrame>()
            var previous: FloatArray? = null
            var key: Bitmap? = null
            for (k in 0 until count) {
                coroutineContext.ensureActive()
                val t = if (count <= 1) 0 else windowUs * k / count
                val frame = r.getScaledFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST, SIZE, SIZE) ?: continue
                val (stats, hist) = measure(frame, t)
                out += stats.copy(change = previous?.let { StyleReferences.histogramChange(it, hist) } ?: 0f)
                previous = hist
                if (key == null && k >= count / 3) {
                    key = r.getScaledFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, LOOK, LOOK)
                }
                frame.recycle()
            }
            return Triple(durationUs, out, key)
        } catch (e: Exception) {
            CineMindLogger.w("Reference video unreadable: ${e.message}")
            return Triple(0L, emptyList(), null)
        } finally {
            runCatching { r.release() }
        }
    }

    private fun sampleImage(uri: Uri): Triple<Long, List<ReferenceFrame>, Bitmap?> {
        val bmp = decode(uri, LOOK) ?: return Triple(0L, emptyList(), null)
        val small = Bitmap.createScaledBitmap(bmp, SIZE, SIZE, true)
        val (stats, _) = measure(small, 0)
        if (small != bmp) small.recycle()
        return Triple(0L, listOf(stats), bmp)
    }

    /** Brightness, saturation, warmth, contrast and a 16-bin brightness histogram of a small frame. */
    private fun measure(bmp: Bitmap, timeUs: Long): Pair<ReferenceFrame, FloatArray> {
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        val hist = FloatArray(16)
        var sumL = 0.0
        var sumL2 = 0.0
        var sumS = 0.0
        var sumWarm = 0.0
        for (c in px) {
            val r = Color.red(c) / 255f
            val g = Color.green(c) / 255f
            val b = Color.blue(c) / 255f
            val l = 0.2126f * r + 0.7152f * g + 0.0722f * b
            val mx = maxOf(r, g, b)
            val mn = minOf(r, g, b)
            sumL += l
            sumL2 += l * l
            sumS += if (mx <= 0f) 0f else (mx - mn) / mx
            sumWarm += (r - b)
            hist[(l * 15.999f).toInt().coerceIn(0, 15)] += 1f
        }
        val n = px.size.coerceAtLeast(1)
        for (i in hist.indices) hist[i] /= n
        val mean = sumL / n
        val std = sqrt((sumL2 / n - mean * mean).coerceAtLeast(0.0))
        return ReferenceFrame(
            timeUs = timeUs,
            brightness = mean.toFloat(),
            saturation = (sumS / n).toFloat(),
            warmth = (sumWarm / n).toFloat().coerceIn(-1f, 1f),
            contrast = std.toFloat(),
        ) to hist
    }

    private suspend fun describe(bmp: Bitmap): String = runCatching {
        val f = File.createTempFile("reference", ".jpg", context.cacheDir)
        try {
            f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
            llm.ask(PROMPT, imageFile = f, maxOutputTokens = 80).lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(200).orEmpty()
        } finally {
            f.delete()
        }
    }.onFailure { CineMindLogger.w("Reference description failed: ${it.message}") }.getOrDefault("")

    private fun decode(uri: Uri, maxSide: Int): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
    }.getOrNull()

    private fun displayName(uri: Uri): String = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment ?: "reference"

    private companion object {
        const val SIZE = 32
        const val LOOK = 512
        const val PROMPT = "In one short sentence, describe the visual style of this picture: setting, light, colours and mood. Do not identify people."
    }
}
