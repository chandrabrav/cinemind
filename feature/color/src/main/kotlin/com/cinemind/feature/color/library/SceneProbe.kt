package com.cinemind.feature.color.library

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.cinemind.core.domain.color.ColorMath
import com.cinemind.core.domain.color.CubeLut
import com.cinemind.core.domain.color.LutAdvisor
import com.cinemind.feature.ai.llm.ondevice.OnDeviceLlm
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.min

/** A small frame of footage to measure and to preview looks on. */
data class FrameSample(val bitmap: Bitmap)

/** Measured look of the footage: 0..1 brightness and saturation, −1..1 warmth. */
data class FootageStats(val brightness: Float, val saturation: Float, val warmth: Float)

/**
 * Looks at the actual footage for colour decisions: grabs small frames, measures brightness,
 * saturation and warmth, and — only when the user asks — has the on-device AI model name the
 * scene in a few words (the image never leaves the phone; the model is freed right after).
 */
@Singleton
class SceneProbe @Inject constructor(
    @ApplicationContext private val context: Context,
    private val llm: OnDeviceLlm,
) {

    /** A ~256 px frame of a video at [timeUs], or a downscaled photo. */
    suspend fun frame(uri: String, isVideo: Boolean, timeUs: Long): FrameSample? = withContext(Dispatchers.IO) {
        runCatching {
            val bmp = if (isVideo) {
                MediaMetadataRetriever().use { r ->
                    r.setDataSource(context, Uri.parse(uri))
                    r.getScaledFrameAtTime(timeUs.coerceAtLeast(0), MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 256, 256)
                }
            } else {
                context.contentResolver.openInputStream(Uri.parse(uri))?.use { s ->
                    BitmapFactory.decodeStream(s, null, BitmapFactory.Options().apply { inSampleSize = 8 })
                }
            }
            bmp?.let { FrameSample(it.copy(Bitmap.Config.ARGB_8888, false)) }
        }.getOrNull()
    }

    fun measure(frames: List<FrameSample>): FootageStats? {
        if (frames.isEmpty()) return null
        var lum = 0.0; var sat = 0.0; var warm = 0.0; var n = 0
        for (f in frames) {
            val b = f.bitmap
            val px = IntArray(b.width * b.height)
            b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
            var i = 0
            while (i < px.size) {
                val c = px[i]
                val r = ((c shr 16) and 0xFF) / 255f; val g = ((c shr 8) and 0xFF) / 255f; val bl = (c and 0xFF) / 255f
                lum += ColorMath.luma(r, g, bl)
                sat += max(r, max(g, bl)) - min(r, min(g, bl))
                warm += r - bl
                n++
                i += 7 // sampling every 7th pixel is plenty
            }
        }
        if (n == 0) return null
        return FootageStats((lum / n).toFloat(), (sat / n).toFloat(), (warm / n * 2).toFloat().coerceIn(-1f, 1f))
    }

    /** The frame with a look applied (for the look thumbnails). */
    fun preview(frame: FrameSample, lut: CubeLut?): Bitmap {
        val src = frame.bitmap
        if (lut == null) return src
        val px = IntArray(src.width * src.height)
        src.getPixels(px, 0, src.width, 0, 0, src.width, src.height)
        val out = FloatArray(3)
        for (i in px.indices) {
            val c = px[i]
            lut.sample(((c shr 16) and 0xFF) / 255f, ((c shr 8) and 0xFF) / 255f, (c and 0xFF) / 255f, out)
            px[i] = (0xFF shl 24) or ((out[0] * 255f).toInt().coerceIn(0, 255) shl 16) or ((out[1] * 255f).toInt().coerceIn(0, 255) shl 8) or (out[2] * 255f).toInt().coerceIn(0, 255)
        }
        return Bitmap.createBitmap(px, src.width, src.height, Bitmap.Config.ARGB_8888)
    }

    /** True if an on-device model that understands images is installed. */
    val aiAvailable: Boolean get() = llm.installedModel()?.supportsImages == true

    /**
     * Asks the on-device model to name the scene, place, time of day and mood of [frame] in a few
     * words; returns them as advisor keywords. Frees the model afterwards.
     */
    suspend fun aiSceneWords(frame: FrameSample): Set<String> {
        val file = File(context.cacheDir, "color_scene.jpg")
        withContext(Dispatchers.IO) { file.outputStream().use { frame.bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it) } }
        return try {
            val reply = llm.ask(
                "Describe this video frame for choosing a colour grade. Answer with at most 8 lowercase words separated by commas: " +
                    "the place (e.g. beach, city, forest, indoor), time of day (e.g. sunset, night, daylight), the main subject " +
                    "(e.g. people, food, landscape) and the mood (e.g. calm, energetic, romantic, dramatic). Words only.",
                imageFile = file,
                maxOutputTokens = 48,
            )
            LutAdvisor.keywords(reply.substringBefore('\n'))
        } finally {
            file.delete()
            llm.unload()
        }
    }
}
