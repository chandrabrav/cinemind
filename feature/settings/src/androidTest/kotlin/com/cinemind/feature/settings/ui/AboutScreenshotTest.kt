package com.cinemind.feature.settings.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cinemind.core.ui.components.AppBackground
import com.cinemind.core.ui.theme.CineMindColors
import com.cinemind.core.ui.theme.CineMindTheme
import com.cinemind.core.ui.theme.DarkCineMindColors
import com.cinemind.core.ui.theme.LightCineMindColors
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The About screen keeps the brand and the developer credit, in both themes. */
@RunWith(AndroidJUnit4::class)
class AboutScreenshotTest {

    @get:Rule val compose = createComposeRule()

    private fun show(colors: CineMindColors) {
        compose.setContent { CineMindTheme(colors = colors) { AppBackground { AboutScreen() } } }
        compose.onNodeWithText("CineMind AI").assertIsDisplayed()
        compose.onNodeWithText("Developed by Gautam").assertIsDisplayed()
    }

    @Test fun lightTheme() { show(LightCineMindColors); compose.saveScreenshot("about_light") }

    @Test fun darkTheme() { show(DarkCineMindColors); compose.saveScreenshot("about_dark") }
}
