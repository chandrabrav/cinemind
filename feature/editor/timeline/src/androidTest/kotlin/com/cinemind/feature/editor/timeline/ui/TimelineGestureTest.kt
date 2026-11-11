package com.cinemind.feature.editor.timeline.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.unit.width
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cinemind.core.domain.editing.ClipLimits
import com.cinemind.core.domain.editing.TimelineEditing
import com.cinemind.core.domain.model.Clip
import com.cinemind.core.ui.components.AppBackground
import com.cinemind.core.ui.theme.CineMindTheme
import com.cinemind.core.ui.theme.LightCineMindColors
import com.cinemind.feature.editor.timeline.preview.PreviewClip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Timeline zoom gestures on the emulator: pinch and buttons change zoom, never the edit. */
@RunWith(AndroidJUnit4::class)
class TimelineGestureTest {

    @get:Rule
    val compose = createComposeRule()

    private val clips = TimelineEditing.relayout(
        listOf(
            Clip(id = "1", trackId = "t", assetId = "a", timelineInUs = 0, timelineOutUs = 0, sourceInUs = 0, sourceOutUs = 3_000_000),
            Clip(id = "2", trackId = "t", assetId = "a", timelineInUs = 0, timelineOutUs = 0, sourceInUs = 0, sourceOutUs = 3_000_000),
        ),
    ).map { PreviewClip(it, asset = null) }

    private var trims = 0
    private var seeks = 0

    private fun show() {
        compose.setContent {
            CineMindTheme(colors = LightCineMindColors) {
                AppBackground {
                    MultiTrackTimeline(
                        clips = clips,
                        music = null,
                        totalUs = 6_000_000,
                        positionUs = 1_000_000,
                        isPlaying = false,
                        selectedClipId = "1",
                        limitsFor = { ClipLimits(sourceDurationUs = 10_000_000, isStill = false) },
                        onSeek = { seeks++ },
                        onClipTap = {},
                        onTrim = { _, _ -> trims++ },
                        onMusicTap = {},
                        modifier = Modifier.fillMaxWidth(),
                        fps = 30,
                    )
                }
            }
        }
    }

    private fun clipWidth(): Float =
        compose.onNodeWithContentDescription("Clip 1", substring = true).getUnclippedBoundsInRoot().width.value

    @Test
    fun pinchOutZoomsInWithoutTrimming() {
        show()
        val before = clipWidth()
        compose.onNodeWithContentDescription("Timeline. Pinch", substring = true).performTouchInput {
            pinch(
                start0 = Offset(centerX - 20f, centerY), end0 = Offset(centerX - 200f, centerY),
                start1 = Offset(centerX + 20f, centerY), end1 = Offset(centerX + 200f, centerY),
                durationMillis = 400,
            )
        }
        compose.waitForIdle()
        val after = clipWidth()
        assertTrue("pinch out widens clips ($before → $after)", after > before * 1.5f)
        assertEquals("a pinch never trims", 0, trims)
    }

    @Test
    fun buttonsZoomAndFit() {
        show()
        val before = clipWidth()
        compose.onNodeWithContentDescription("Zoom in").performClick()
        compose.waitForIdle()
        assertTrue("zoom in widens", clipWidth() > before * 1.3f)
        // A clip is selected, so "fit" frames that clip: it fills most of the width.
        compose.onNodeWithContentDescription("Zoom to selected clip").performClick()
        compose.waitForIdle()
        val root = compose.onNodeWithContentDescription("Timeline. Pinch", substring = true).getUnclippedBoundsInRoot().width.value
        assertTrue("selected clip fills the view (${clipWidth()} of $root)", clipWidth() > root * 0.7f)
        compose.onNodeWithContentDescription("Go to start").performClick()
        assertEquals(1, seeks)
        assertEquals(0, trims)
    }
}
