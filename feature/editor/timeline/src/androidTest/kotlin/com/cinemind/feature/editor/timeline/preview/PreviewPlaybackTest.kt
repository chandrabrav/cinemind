package com.cinemind.feature.editor.timeline.preview

import android.graphics.SurfaceTexture
import android.view.Surface
import androidx.media3.common.Player
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cinemind.core.domain.editing.ClipTransition
import com.cinemind.core.domain.editing.TimelineEditing
import com.cinemind.core.domain.model.Clip
import com.cinemind.core.domain.model.MediaAsset
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The editor preview now plays the export's own composition (CompositionPlayer). This plays a real
 * video → photo timeline (with a fade at the cut) on the emulator and checks that playback runs
 * across the cut to the end without errors. Smoothness on a phone still needs a device check.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class PreviewPlaybackTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private fun asset(name: String): File {
        val out = File(context.cacheDir, "preview-$name")
        instrumentation.context.assets.open(name).use { input -> out.outputStream().use { input.copyTo(it) } }
        return out
    }

    private fun <T> onMain(block: () -> T): T {
        var result: T? = null
        instrumentation.runOnMainSync { result = block() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    @Test
    fun playsVideoThenPhotoAcrossTheCut() {
        val video = asset("portrait.mp4")
        val photo = asset("photo.jpg")
        val clips = TimelineEditing.relayout(
            listOf(
                Clip(id = "v", trackId = "t", assetId = "v", timelineInUs = 0, timelineOutUs = 0, sourceInUs = 0, sourceOutUs = 2_000_000),
                Clip(id = "p", trackId = "t", assetId = "p", timelineInUs = 0, timelineOutUs = 0, sourceInUs = 0, sourceOutUs = 1_000_000,
                    transitionInType = ClipTransition.FADE.name, transitionInMs = 400),
            ),
        )
        val items = listOf(
            PreviewClip(clips[0], MediaAsset(id = "v", uri = android.net.Uri.fromFile(video).toString(), mimeType = "video/mp4", width = 720, height = 1280, durationUs = 2_000_000, frameRate = 30f, hasAudio = true)),
            PreviewClip(clips[1], MediaAsset(id = "p", uri = android.net.Uri.fromFile(photo).toString(), mimeType = "image/jpeg", width = 1200, height = 900, durationUs = 0)),
        )
        val texture = SurfaceTexture(false)
        val surface = Surface(texture)
        val preview = onMain {
            PreviewPlayer(context).apply {
                setFormat(1080, 1920, 30)
                setClips(items)
            }
        }
        try {
            instrumentation.waitForIdleSync()
            onMain {
                (preview.player as androidx.media3.transformer.CompositionPlayer).setVideoSurface(surface, Size(720, 1280))
                preview.togglePlayPause()
            }
            var maxPos = 0L
            var ended = false
            val deadline = System.currentTimeMillis() + 20_000
            while (System.currentTimeMillis() < deadline && !ended) {
                Thread.sleep(250)
                val s = onMain { preview.snapshot() }
                assertNull("no playback error", s.error)
                maxPos = maxOf(maxPos, s.positionUs)
                ended = onMain { preview.player.playbackState == Player.STATE_ENDED }
            }
            assertTrue("played across the cut into the photo (reached ${maxPos / 1000} ms)", ended || maxPos >= 2_500_000)
            assertNull(onMain { preview.snapshot().error })
        } finally {
            onMain { preview.release() }
            surface.release()
            texture.release()
        }
    }
}
