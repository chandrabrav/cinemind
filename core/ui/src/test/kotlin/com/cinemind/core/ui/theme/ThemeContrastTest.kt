package com.cinemind.core.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/**
 * WCAG 2.x contrast checks for every text/background pairing the UI uses, in both themes.
 * Translucent surfaces are blended over the backdrop first, as they appear on screen.
 */
class ThemeContrastTest {

    private fun channel(v: Float): Double {
        val c = v.toDouble()
        return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }

    private fun luminance(c: Color) = 0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)

    /** Source-over blend of a (possibly translucent) colour on an opaque one. */
    private fun over(fg: Color, bg: Color): Color {
        val a = fg.alpha
        return Color(fg.red * a + bg.red * (1 - a), fg.green * a + bg.green * (1 - a), fg.blue * a + bg.blue * (1 - a), 1f)
    }

    private fun ratio(a: Color, b: Color): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    private val failures = mutableListOf<String>()

    /** Records (rather than throws) so one run lists every failing pair. */
    private fun assertAA(name: String, text: Color, background: Color, min: Double = 4.5) {
        val r = ratio(text, background)
        if (r < min) failures += "$name contrast %.2f < %.1f".format(r, min)
    }

    private fun check(c: CineMindColors) {
        failures.clear()
        collect(c)
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    private fun collect(c: CineMindColors) {
        val theme = if (c.isDark) "dark" else "light"
        val glassTop = over(c.surfaceGlass, c.backdropTop)
        val glassBottom = over(c.surfaceGlass, c.backdropBottom)
        val backgrounds = mapOf(
            "background" to c.background,
            "surface" to c.surface,
            "surfaceRaised" to c.surfaceRaised,
            "glass/top" to glassTop,
            "glass/bottom" to glassBottom,
            "backdropTop" to c.backdropTop,
            "backdropBottom" to c.backdropBottom,
        )
        for ((bgName, bg) in backgrounds) {
            assertAA("$theme textPrimary on $bgName", c.textPrimary, bg)
            assertAA("$theme textSecondary on $bgName", c.textSecondary, bg)
            assertAA("$theme textTertiary on $bgName", c.textTertiary, bg)
            assertAA("$theme accent text on $bgName", c.accent, bg)
            assertAA("$theme danger on $bgName", c.danger, bg)
            assertAA("$theme success on $bgName", c.success, bg)
            assertAA("$theme highlight on $bgName", c.highlight, bg)
        }
        assertAA("$theme textSecondary on surfaceSunken (pills)", c.textSecondary, c.surfaceSunken)
        assertAA("$theme onAccent on accent", c.onAccent, c.accent)
        assertAA("$theme onAccentContainer on accentContainer", c.onAccentContainer, c.accentContainer)
        assertAA("$theme gradient label (start)", c.onAccentGradient, c.accentGradientStart)
        assertAA("$theme gradient label (end)", c.onAccentGradient, c.accentGradientEnd)
        assertAA("$theme clip label on video track", c.clipLabel, c.trackVideo)
        assertAA("$theme clip label on music track", c.clipLabel, c.trackMusic)
        assertAA("$theme on-stage text over stage controls", c.onStage, over(c.stageControl, c.stage))
        // Playhead is a graphic, not text: WCAG 1.4.11 asks for 3:1 against the lane and clips.
        assertAA("$theme playhead on lane", c.playhead, c.timelineLane, min = 3.0)
    }

    @Test
    fun lightThemeMeetsWcagAA() = check(LightCineMindColors)

    @Test
    fun darkThemeMeetsWcagAA() = check(DarkCineMindColors)

    @Test
    fun themesAreFlaggedCorrectly() {
        assertFalse(LightCineMindColors.isDark)
        assertTrue(DarkCineMindColors.isDark)
    }

    @Test
    fun themeModeResolution() {
        assertTrue(ThemeMode.SYSTEM.isDark(systemDark = true))
        assertFalse(ThemeMode.SYSTEM.isDark(systemDark = false))
        assertFalse(ThemeMode.LIGHT.isDark(systemDark = true))
        assertTrue(ThemeMode.DARK.isDark(systemDark = false))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromName("bogus"))
        assertEquals(ThemeMode.DARK, ThemeMode.fromName("DARK"))
    }

    @Test
    fun materialSchemeIsDerivedFromTokens() {
        val s = LightCineMindColors.toColorScheme()
        assertEquals(LightCineMindColors.accent, s.primary)
        assertEquals(LightCineMindColors.textPrimary, s.onSurface)
        assertEquals(DarkCineMindColors.background, DarkCineMindColors.toColorScheme().background)
    }
}
