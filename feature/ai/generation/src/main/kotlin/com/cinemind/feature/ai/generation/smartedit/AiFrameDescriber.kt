package com.cinemind.feature.ai.generation.smartedit

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.cinemind.core.common.CineMindLogger
import com.cinemind.core.domain.model.MediaAsset
import com.cinemind.feature.ai.llm.ondevice.OnDeviceLlm
import com.cinemind.feature.ai.llm.ondevice.Prompts
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/** Shows individual frames to the on-device vision model and returns what it understood. */
class AiFrameDescriber @Inject constructor(
    @ApplicationContext private val context: Context,
    private val llm: OnDeviceLlm,
) {
    /** Describes [asset] at each of [timesUs]; frames the model can't read are skipped. */
    suspend fun describe(asset: MediaAsset, timesUs: List<Long>): List<TimedInsight> {
        val out = mutableListOf<TimedInsight>()
        for (t in timesUs) {
            val frame = withContext(Dispatchers.IO) { frameFile(asset, t) } ?: continue
            try {
                val reply = llm.ask(Prompts.FRAME, imageFile = frame, maxOutputTokens = 160)
                val insight = Prompts.parseInsight(reply)
                if (insight != null) out += TimedInsight(t, insight) else CineMindLogger.w("Unparsed AI reply: ${reply.take(120)}")
            } finally {
                frame.delete()
            }
        }
        return out
    }

    private fun frameFile(asset: MediaAsset, timeUs: Long): File? {
        val bitmap = try {
            if (asset.isVideo) {
                val r = MediaMetadataRetriever()
                try {
                    r.setDataSource(context, Uri.parse(asset.uri))
                    r.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, SIZE, SIZE)
                } finally {
                    runCatching { r.release() }
                }
            } else {
                decodePhoto(Uri.parse(asset.uri))
            }
        } catch (e: Exception) {
            CineMindLogger.w("Frame grab failed: ${e.message}")
            null
        } ?: return null
        val f = File.createTempFile("ai_frame", ".jpg", context.cacheDir)
        f.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        bitmap.recycle()
        return f
    }

    private fun decodePhoto(uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= SIZE) sample *= 2
        return context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    }

    private companion object {
        /** Long side sent to the model: enough detail, small enough to be fast. */
        const val SIZE = 768
    }
}
