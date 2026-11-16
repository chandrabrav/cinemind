package com.cinemind.feature.editor.timeline.reverse

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/** Reverse: the reversed copy keeps the length and starts where the original ends. */
@RunWith(AndroidJUnit4::class)
class ReverseMakerTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private fun asset(name: String): File {
        val out = File(context.cacheDir, "rev-$name")
        instrumentation.context.assets.open(name).use { input -> out.outputStream().use { input.copyTo(it) } }
        return out
    }

    private fun thumb(r: MediaMetadataRetriever, us: Long): IntArray {
        val b = Bitmap.createScaledBitmap(r.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST)!!, 32, 56, true)
        return IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }
    }

    private fun diff(a: IntArray, b: IntArray): Double = a.indices.sumOf { i ->
        val x = a[i]; val y = b[i]
        (abs(((x shr 16) and 0xFF) - ((y shr 16) and 0xFF)) + abs(((x shr 8) and 0xFF) - ((y shr 8) and 0xFF)) + abs((x and 0xFF) - (y and 0xFF))).toDouble()
    } / (a.size * 3 * 255.0)

    @Test
    fun reversedCopyKeepsLengthAndStartsAtTheEnd() = runBlocking {
        val src = asset("portrait.mp4")
        val result = ReverseMaker(context).reverse(Uri.fromFile(src), 0, 2_000_000) {}
        assertTrue("file written", result.file.exists() && result.file.length() > 10_000)
        val o = MediaMetadataRetriever().apply { setDataSource(src.absolutePath) }
        val r = MediaMetadataRetriever().apply { setDataSource(result.file.absolutePath) }
        try {
            val durMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
            assertTrue("about 2 s long ($durMs ms)", durMs in 1_700..2_300)
            val revFirst = thumb(r, 0)
            val origLast = thumb(o, 1_930_000)
            val origFirst = thumb(o, 0)
            val toLast = diff(revFirst, origLast)
            val toFirst = diff(revFirst, origFirst)
            assertTrue("reversed start looks like the original end ($toLast) more than its start ($toFirst)", toLast < toFirst)
        } finally {
            o.release()
            r.release()
            result.file.delete()
        }
    }
}
