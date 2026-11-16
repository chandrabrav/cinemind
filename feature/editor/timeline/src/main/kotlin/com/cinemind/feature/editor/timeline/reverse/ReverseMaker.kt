package com.cinemind.feature.editor.timeline.reverse

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Makes a reversed copy of part of a video (Media3 can't play backwards): frames are decoded in
 * order a chunk at a time from the end, saved as pictures in reverse order, and encoded into a
 * new silent video at the original frame rate. Kept in app storage; the original is never touched.
 */
@OptIn(UnstableApi::class)
class ReverseMaker(private val context: Context) {

    companion object {
        const val MAX_US = 30_000_000L
        private const val CHUNK = 24
        private const val MAX_SHORT_SIDE = 1080
    }

    data class Result(val file: File, val width: Int, val height: Int, val durationUs: Long, val fps: Float)

    suspend fun reverse(uri: Uri, fromUs: Long, toUs: Long, progress: (Float) -> Unit): Result = withContext(Dispatchers.Default) {
        require(toUs - fromUs in 100_000..MAX_US) { "Reverse works on parts up to ${MAX_US / 1_000_000} s; split the clip first" }
        val work = File(context.cacheDir, "reverse_" + System.currentTimeMillis()).apply { mkdirs() }
        val out = File(File(context.filesDir, "reversed").apply { mkdirs() }, "reversed_" + System.currentTimeMillis() + ".mp4")
        try {
            val r = MediaMetadataRetriever()
            val frames = ArrayList<File>()
            val fps: Float
            try {
                r.setDataSource(context, uri)
                val count = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toIntOrNull() ?: error("Can't read this video's frames")
                val durUs = (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) * 1_000
                require(count > 0 && durUs > 0) { "Can't read this video's frames" }
                fps = (count * 1_000_000f / durUs).coerceIn(1f, 240f)
                val first = (fromUs * fps / 1_000_000f).roundToInt().coerceIn(0, count - 1)
                val last = ((toUs * fps / 1_000_000f).roundToInt() - 1).coerceIn(first, count - 1)
                val total = last - first + 1
                var end = last
                // Walk backwards in chunks; each chunk is decoded forwards, then written newest first.
                while (end >= first) {
                    coroutineContext.ensureActive()
                    val start = max(first, end - CHUNK + 1)
                    val bitmaps = r.getFramesAtIndex(start, end - start + 1)
                    for (b in bitmaps.asReversed()) {
                        val f = File(work, "f%06d.jpg".format(frames.size))
                        scaled(b).also { s ->
                            f.outputStream().use { s.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                            if (s !== b) s.recycle()
                        }
                        b.recycle()
                        frames += f
                    }
                    progress(frames.size.toFloat() / total * 0.6f)
                    end = start - 1
                }
            } finally {
                runCatching { r.release() }
            }
            require(frames.isNotEmpty()) { "No frames could be read" }
            encode(frames, fps, out) { progress(0.6f + it * 0.4f) }
            val (w, h) = MediaMetadataRetriever().let { m ->
                try {
                    m.setDataSource(out.absolutePath)
                    val rot = m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                    val w = m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                    val h = m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                    if (rot == 90 || rot == 270) h to w else w to h
                } finally {
                    m.release()
                }
            }
            Result(out, w, h, (frames.size * 1_000_000L / fps).toLong(), fps)
        } catch (e: Throwable) {
            out.delete()
            throw e
        } finally {
            work.deleteRecursively()
        }
    }

    private fun scaled(b: Bitmap): Bitmap {
        val short = minOf(b.width, b.height)
        if (short <= MAX_SHORT_SIDE) return b
        val s = MAX_SHORT_SIDE.toFloat() / short
        return Bitmap.createScaledBitmap(b, (b.width * s).roundToInt() / 2 * 2, (b.height * s).roundToInt() / 2 * 2, true)
    }

    /** Pictures (in play order) → video at [fps], via Media3 (images as one-frame items). */
    private suspend fun encode(frames: List<File>, fps: Float, out: File, progress: (Float) -> Unit) = withContext(Dispatchers.Main) {
        val frameMs = (1_000f / fps).coerceAtLeast(1f)
        val items = frames.map { f ->
            EditedMediaItem.Builder(MediaItem.Builder().setUri(Uri.fromFile(f)).setImageDurationMs(frameMs.roundToInt().toLong()).build())
                .setFrameRate(fps.roundToInt().coerceAtLeast(1))
                .build()
        }
        val composition = Composition.Builder(EditedMediaItemSequence.Builder(setOf(androidx.media3.common.C.TRACK_TYPE_VIDEO)).addItems(items).build()).build()
        suspendCancellableCoroutine<Unit> { cont ->
            val transformer = Transformer.Builder(context)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        if (cont.isActive) cont.resume(Unit)
                    }
                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        if (cont.isActive) cont.resumeWithException(exportException)
                    }
                })
                .build()
            transformer.start(composition, out.absolutePath)
            cont.invokeOnCancellation { transformer.cancel() }
            progress(0f)
        }
        progress(1f)
    }
}
