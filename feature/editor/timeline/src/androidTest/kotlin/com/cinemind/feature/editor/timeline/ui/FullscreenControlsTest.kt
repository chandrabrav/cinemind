package com.cinemind.feature.editor.timeline.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cinemind.core.ui.theme.CineMindTheme
import com.cinemind.core.ui.theme.DarkCineMindColors
import com.cinemind.core.ui.theme.LightCineMindColors
import com.cinemind.feature.editor.timeline.preview.PreviewState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Full screen now grows the editor's own preview (no second window or video view). These are the
 * controls drawn over it: play/pause, position, exit; both themes.
 */
@RunWith(AndroidJUnit4::class)
class FullscreenControlsTest {

    @get:Rule
    val compose = createComposeRule()

    private var exits = 0
    private var toggles = 0

    private fun show(dark: Boolean) {
        compose.setContent {
            CineMindTheme(colors = if (dark) DarkCineMindColors else LightCineMindColors) {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    FullscreenControls(
                        state = PreviewState(isPlaying = false, positionUs = 2_000_000, durationUs = 10_000_000),
                        fps = 30,
                        onTogglePlay = { toggles++ },
                        onScrub = {},
                        onScrubFinished = {},
                        onExit = { exits++ },
                        visible = true,
                        onInteract = {},
                    )
                }
            }
        }
    }

    @Test
    fun controlsPlayAndExitButton() {
        show(dark = false)
        compose.onNodeWithContentDescription("Exit full screen").assertIsDisplayed()
        compose.onNodeWithContentDescription("Playback position").assertIsDisplayed()
        compose.onNodeWithContentDescription("Play").performClick()
        assertEquals(1, toggles)
        compose.saveScreenshot("fullscreen_light")
        compose.onNodeWithContentDescription("Exit full screen").performClick()
        assertEquals(1, exits)
    }

    @Test
    fun darkTheme() {
        show(dark = true)
        compose.onNodeWithContentDescription("Exit full screen").assertIsDisplayed()
        compose.saveScreenshot("fullscreen_dark")
    }
}
