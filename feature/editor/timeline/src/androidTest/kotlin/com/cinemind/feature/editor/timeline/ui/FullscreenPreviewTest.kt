package com.cinemind.feature.editor.timeline.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cinemind.core.ui.theme.CineMindTheme
import com.cinemind.core.ui.theme.DarkCineMindColors
import com.cinemind.core.ui.theme.LightCineMindColors
import com.cinemind.feature.editor.timeline.preview.PreviewState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Full-screen preview: controls, play/pause, exit button and Android Back; both themes. */
@RunWith(AndroidJUnit4::class)
class FullscreenPreviewTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var player: ExoPlayer
    private var exits = 0
    private var toggles = 0

    private fun show(dark: Boolean) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            player = ExoPlayer.Builder(InstrumentationRegistry.getInstrumentation().targetContext).build()
        }
        compose.setContent {
            CineMindTheme(colors = if (dark) DarkCineMindColors else LightCineMindColors) {
                FullscreenPreview(
                    player = player,
                    state = PreviewState(isPlaying = false, positionUs = 2_000_000, durationUs = 10_000_000),
                    ratio = 9f / 16f,
                    fps = 30,
                    onTogglePlay = { toggles++ },
                    onScrub = {},
                    onScrubFinished = {},
                    onExit = { exits++ },
                )
            }
        }
    }

    @After
    fun release() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { player.release() }
    }

    @Test
    fun controlsPlayAndExitButton() {
        show(dark = false)
        compose.onNodeWithContentDescription("Exit full screen").assertIsDisplayed()
        compose.onNodeWithContentDescription("Playback position").assertIsDisplayed()
        compose.onNodeWithContentDescription("Play").performClick()
        assertEquals(1, toggles)
        compose.saveScreenshot("fullscreen_light", topWindow = true)
        compose.onNodeWithContentDescription("Exit full screen").performClick()
        assertEquals(1, exits)
    }

    @Test
    fun androidBackExits() {
        show(dark = true)
        compose.onNodeWithContentDescription("Exit full screen").assertIsDisplayed()
        compose.saveScreenshot("fullscreen_dark", topWindow = true)
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        compose.waitForIdle()
        assertEquals(1, exits)
    }
}
