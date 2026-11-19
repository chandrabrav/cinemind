package com.cinemind.feature.editor.timeline.matte

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.cinemind.core.common.CineMindLogger
import com.cinemind.core.domain.model.MediaAsset
import com.cinemind.feature.export.engine.MatteStore
import com.cinemind.feature.export.engine.MatteWriter
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.Segmenter
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenter
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Makes the subject cutout of a photo or video, entirely on the phone (nothing is uploaded).
 * Google's subject segmentation finds any main subject (people, pets, products); if that model
 * isn't available on this phone, the bundled people-only model is used instead. Video is sampled
 * about [SAMPLES_PER_SECOND] times a second; the GPU effect blends between samples.
 */
class MatteMaker(private val context: Context) {

    enum class Engine(val label: String) { SUBJECT("Any subject"), PEOPLE("People") }

    data class Result(val engine: Engine, val frames: Int)

    class MatteException(message: String) : Exception(message)

    /** Makes and saves the cutout for [asset]; [onProgress] gets 0..1. Replaces an older cutout. */
    suspend fun make(asset: MediaAsset, onProgress: (Float) -> Unit): Result = withContext(Dispatchers.Default) {
        if (!asset.isVideo && !asset.isImage) throw MatteException("Only photos and videos can be cut out")
        val (mw, mh) = matteSize(asset.width, asset.height)
        val target = MatteStore.file(context, asset.id)
        val writer = MatteWriter(target, mw, mh)
        val seg = Segmenters()
        try {
            var count = 0
            if (asset.isImage) {
                val bmp = decodeImage(Uri.parse(asset.uri))
                writer.add(0, seg.mask(bmp, mw, mh, video = false))
                bmp.recycle()
                count = 1
                onProgress(1f)
            } else {
                count = sampleVideo(asset, mw, mh, seg, writer, onProgress)
            }
            if (count == 0) throw MatteException("Couldn't read any frames of this video")
            writer.finish()
            CineMindLogger.i("Cutout made for ${asset.id}: $count frames, ${mw}x$mh, ${seg.used}")
            Result(seg.used, count)
        } catch (e: Throwable) {
            writer.close()
            throw e
        } finally {
            seg.close()
        }
    }

    private suspend fun sampleVideo(asset: MediaAsset, mw: Int, mh: Int, seg: Segmenters, writer: MatteWriter, onProgress: (Float) -> Unit): Int {
        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(context, Uri.parse(asset.uri))
            val durationUs = (mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) * 1000
            val frameCount = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toIntOrNull() ?: 0
            val rotation = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (durationUs <= 0) throw MatteException("This video has no length")
            val usPerFrame = if (frameCount > 0) durationUs.toDouble() / frameCount else 1_000_000.0 / 30
            // Decode in order (much faster than seeking for every sample) and keep every step-th frame.
            val step = max(1, (1_000_000.0 / SAMPLES_PER_SECOND / usPerFrame).roundToInt())
            val params = MediaMetadataRetriever.BitmapParams().apply { preferredConfig = Bitmap.Config.RGB_565 }
            var count = 0
            if (frameCount > 0) {
                var index = 0
                while (index < frameCount) {
                    coroutineContext.ensureActive()
                    val n = min(CHUNK, frameCount - index)
                    val frames = runCatching { mmr.getFramesAtIndex(index, n, params) }.getOrNull()
                    if (frames.isNullOrEmpty()) break
                    frames.forEachIndexed { k, f ->
                        val i = index + k
                        if (i % step == 0) {
                            val upright = upright(f, asset, rotation)
                            writer.add((i * usPerFrame).toLong(), seg.mask(upright, mw, mh, video = true))
                            if (upright !== f) upright.recycle()
                            count++
                        }
                        f.recycle()
                    }
                    index += n
                    onProgress(index.toFloat() / frameCount)
                }
            }
            // Fallback when the container doesn't report frames: sample by time.
            if (count == 0) {
                val sampleUs = 1_000_000L / SAMPLES_PER_SECOND
                var t = 0L
                while (t < durationUs) {
                    coroutineContext.ensureActive()
                    val f = mmr.getScaledFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST, mw * 2, mh * 2) ?: break
                    val upright = upright(f, asset, rotation)
                    writer.add(t, seg.mask(upright, mw, mh, video = true))
                    if (upright !== f) upright.recycle()
                    f.recycle()
                    count++
                    t += sampleUs
                    onProgress(t.toFloat() / durationUs)
                }
            }
            return count
        } finally {
            runCatching { mmr.release() }
        }
    }

    /** Frames should already be upright; if the orientation disagrees with the asset, turn it. */
    private fun upright(f: Bitmap, asset: MediaAsset, rotation: Int): Bitmap {
        val portraitAsset = asset.height > asset.width
        val portraitFrame = f.height > f.width
        if (asset.width == asset.height || portraitAsset == portraitFrame) return f
        return Bitmap.createBitmap(f, 0, 0, f.width, f.height, Matrix().apply { postRotate(if (rotation % 180 != 0) rotation.toFloat() else 90f) }, true)
    }

    private fun decodeImage(uri: Uri): Bitmap {
        val src = ImageDecoder.createSource(context.contentResolver, uri)
        return ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val s = INPUT_SHORT_SIDE.toFloat() / min(info.size.width, info.size.height)
            if (s < 1f) decoder.setTargetSize((info.size.width * s).roundToInt().coerceAtLeast(1), (info.size.height * s).roundToInt().coerceAtLeast(1))
        }
    }

    /** Both models, created lazily; switches to the people model if the subject model fails. */
    private inner class Segmenters {
        private var subject: SubjectSegmenter? = null
        private var people: Segmenter? = null
        private var subjectFailed = false
        var used: Engine = Engine.SUBJECT
            private set

        suspend fun mask(src: Bitmap, mw: Int, mh: Int, video: Boolean): ByteArray {
            val input = scaled(src)
            try {
                if (!subjectFailed) {
                    try {
                        val s = subject ?: SubjectSegmentation.getClient(
                            SubjectSegmenterOptions.Builder().enableForegroundConfidenceMask().build(),
                        ).also { subject = it }
                        val result = s.process(InputImage.fromBitmap(input, 0)).awaitTask()
                        val buf = result.foregroundConfidenceMask ?: throw MatteException("No mask")
                        buf.rewind()
                        val values = FloatArray(input.width * input.height)
                        buf.get(values, 0, min(values.size, buf.remaining()))
                        used = Engine.SUBJECT
                        return downsample(values, input.width, input.height, mw, mh)
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        CineMindLogger.w("Subject model unavailable, using people model: ${e.message}")
                        subjectFailed = true
                    }
                }
                val p = people ?: Segmentation.getClient(
                    SelfieSegmenterOptions.Builder()
                        .setDetectorMode(if (video) SelfieSegmenterOptions.STREAM_MODE else SelfieSegmenterOptions.SINGLE_IMAGE_MODE)
                        .build(),
                ).also { people = it }
                val mask = p.process(InputImage.fromBitmap(input, 0)).awaitTask()
                val buf = mask.buffer.order(ByteOrder.nativeOrder())
                buf.rewind()
                val values = FloatArray(mask.width * mask.height)
                buf.asFloatBuffer().get(values, 0, min(values.size, buf.remaining() / 4))
                used = Engine.PEOPLE
                return downsample(values, mask.width, mask.height, mw, mh)
            } finally {
                if (input !== src) input.recycle()
            }
        }

        fun close() {
            runCatching { subject?.close() }
            runCatching { people?.close() }
        }
    }

    private fun scaled(src: Bitmap): Bitmap {
        val s = INPUT_SHORT_SIDE.toFloat() / min(src.width, src.height)
        val argb = if (src.config != Bitmap.Config.ARGB_8888) src.copy(Bitmap.Config.ARGB_8888, false) else src
        if (s >= 1f) return argb
        val out = Bitmap.createScaledBitmap(argb, (argb.width * s).roundToInt().coerceAtLeast(1), (argb.height * s).roundToInt().coerceAtLeast(1), true)
        if (argb !== src && argb !== out) argb.recycle()
        return out
    }

    companion object {
        const val SAMPLES_PER_SECOND = 12
        const val MATTE_SHORT_SIDE = 256
        const val INPUT_SHORT_SIDE = 512
        private const val CHUNK = 6

        fun matteSize(w: Int, h: Int): Pair<Int, Int> {
            if (w <= 0 || h <= 0) return MATTE_SHORT_SIDE to MATTE_SHORT_SIDE
            val s = MATTE_SHORT_SIDE.toFloat() / min(w, h)
            return (w * s).roundToInt().coerceAtLeast(1) to (h * s).roundToInt().coerceAtLeast(1)
        }

        /** Box-filters a confidence map (0..1) to the matte size as bytes 0..255. */
        fun downsample(values: FloatArray, w: Int, h: Int, mw: Int, mh: Int): ByteArray {
            val out = ByteArray(mw * mh)
            for (y in 0 until mh) {
                val y0 = y * h / mh
                val y1 = max(y0 + 1, (y + 1) * h / mh)
                for (x in 0 until mw) {
                    val x0 = x * w / mw
                    val x1 = max(x0 + 1, (x + 1) * w / mw)
                    var sum = 0f
                    var n = 0
                    for (yy in y0 until min(y1, h)) for (xx in x0 until min(x1, w)) { sum += values[yy * w + xx]; n++ }
                    val v = if (n == 0) 0f else sum / n
                    out[y * mw + x] = (v.coerceIn(0f, 1f) * 255f).roundToInt().toByte()
                }
            }
            return out
        }
    }
}

private suspend fun <T> Task<T>.awaitTask(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
    addOnCanceledListener { cont.cancel() }
}
