package com.cinemind.feature.editor.timeline.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cinemind.core.domain.audio.MusicSettings
import com.cinemind.core.domain.editing.ClipLimits
import com.cinemind.core.domain.editing.ClipTransition
import com.cinemind.core.domain.editing.TimelineEditing
import com.cinemind.core.domain.model.Clip
import com.cinemind.core.ui.components.AppBackground
import com.cinemind.core.ui.theme.CineMindColors
import com.cinemind.core.ui.theme.CineMindTheme
import com.cinemind.core.ui.theme.DarkCineMindColors
import com.cinemind.core.ui.theme.LightCineMindColors
import com.cinemind.feature.editor.timeline.preview.PreviewClip
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Renders the redesigned editor controls (multitrack timeline with a selected clip, trim handles,
 * a transition marker, music lane and playhead; clip inspector; tools bar) in both themes.
 */
@RunWith(AndroidJUnit4::class)
class EditorScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private fun clip(id: String, lenUs: Long, type: ClipTransition = ClipTransition.CUT, ms: Long = 0) =
        Clip(id = id, trackId = "t", assetId = "a$id", timelineInUs = 0, timelineOutUs = 0, sourceInUs = 0, sourceOutUs = lenUs,
            transitionInType = type.name, transitionInMs = ms)

    private val clips = TimelineEditing.relayout(
        listOf(clip("1", 2_400_000), clip("2", 3_000_000, ClipTransition.FADE, 500), clip("3", 1_800_000), clip("4", 4_000_000, ClipTransition.SLIDE_LEFT, 500)),
    ).map { PreviewClip(it, asset = null) }

    private val music = MusicUi("Sunset Drive.mp3", 180_000_000, MusicSettings(assetId = "m"))
    private val captions = listOf(
        com.cinemind.core.domain.captions.Caption(id = "c1", projectId = "p", startUs = 500_000, endUs = 2_200_000, text = "Bhai, yeh jagah sach mein"),
        com.cinemind.core.domain.captions.Caption(id = "c2", projectId = "p", startUs = 2_400_000, endUs = 4_600_000, text = "bahut beautiful hai!", uncertain = true),
    )
    private val limits = ClipLimits(sourceDurationUs = 10_000_000, isStill = false)

    private fun editor(colors: CineMindColors) {
        compose.setContent {
            CineMindTheme(colors = colors) {
                AppBackground {
                    Column {
                        MultiTrackTimeline(
                            clips = clips,
                            music = music,
                            totalUs = clips.last().clip.timelineOutUs,
                            positionUs = 3_200_000,
                            isPlaying = false,
                            selectedClipId = "2",
                            limitsFor = { limits },
                            onSeek = {},
                            onClipTap = {},
                            onTrim = { _, _ -> },
                            onMusicTap = {},
                            modifier = Modifier.fillMaxWidth(),
                            captions = captions,
                        )
                        // Clip selected: the bottom bar shows only that clip's actions.
                        EditorBottomBar(
                            title = "Clip 2",
                            onClose = {},
                            actions = listOf("Trim", "Split", "Speed", "Crop & move", "Colour", "Delete")
                                .map { EditorAction(androidx.compose.material.icons.Icons.Default.Tune, it) {} },
                        )
                        // Nothing selected: the main tools.
                        EditorBottomBar(
                            title = null,
                            actions = listOf("Add", "Audio", "Text", "Captions", "Overlay", "Colour")
                                .map { EditorAction(androidx.compose.material.icons.Icons.Default.Tune, it) {} },
                        )
                    }
                }
            }
        }
    }

    private fun assertKeyControls() {
        compose.onNodeWithText("Sunset Drive.mp3").assertIsDisplayed()
        compose.onNodeWithContentDescription("Trim end handle").assertIsDisplayed()
        compose.onNodeWithText("Split").assertIsDisplayed()
        compose.onNodeWithText("Audio").assertExists()
        compose.onNodeWithText("Captions").assertExists()
        compose.onNodeWithText("Bhai, yeh jagah sach mein").assertIsDisplayed()
    }

    @Test
    fun lightTheme() {
        editor(LightCineMindColors)
        assertKeyControls()
        compose.saveScreenshot("editor_light")
    }

    @Test
    fun darkTheme() {
        editor(DarkCineMindColors)
        assertKeyControls()
        compose.saveScreenshot("editor_dark")
    }
}
