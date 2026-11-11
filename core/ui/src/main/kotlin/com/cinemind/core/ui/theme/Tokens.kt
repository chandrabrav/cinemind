package com.cinemind.core.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * CineMind semantic colour tokens. Screens use these (or MaterialTheme.colorScheme, which is derived
 * from them) — never raw colour literals. Every text/background pair is contrast-checked in
 * `ThemeContrastTest` (WCAG AA, ≥ 4.5:1 for text).
 *
 * Visual direction: airy, layered, glass-like surfaces over a soft gradient backdrop
 * (inspired by the feel of OriginOS — original design, no copied assets).
 */
@Immutable
data class CineMindColors(
    val isDark: Boolean,
    // Backdrop & surfaces
    val backdropTop: Color,
    val backdropBottom: Color,
    val background: Color,
    val surface: Color,
    /** Translucent card surface for layered "glass" panels. */
    val surfaceGlass: Color,
    val surfaceRaised: Color,
    val surfaceSunken: Color,
    /** Hairline border that gives glass panels their edge. */
    val hairline: Color,
    val scrim: Color,
    // Text & icons
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    // Accents
    val accent: Color,
    val onAccent: Color,
    val accentContainer: Color,
    val onAccentContainer: Color,
    val accentGradientStart: Color,
    val accentGradientEnd: Color,
    /** Text/icons on the accent gradient (white; gradient stops are chosen for ≥ 4.5:1). */
    val onAccentGradient: Color,
    /** Warm cinematic highlight (selection, playhead). */
    val highlight: Color,
    val success: Color,
    val warning: Color,
    val danger: Color,
    // Video stage & timeline
    /** The preview stage is black in both themes, like any video player. */
    val stage: Color,
    val onStage: Color,
    val stageControl: Color,
    val timelineLane: Color,
    val trackVideo: Color,
    val trackMusic: Color,
    val clipLabel: Color,
    val playhead: Color,
    /** Selected clip outline, trim handles and trim sliders (graphic; ≥ 3:1 on the timeline lane). */
    val selection: Color,
)

val LightCineMindColors = CineMindColors(
    isDark = false,
    backdropTop = Color(0xFFE8EDFF),
    backdropBottom = Color(0xFFF5F3FA),
    background = Color(0xFFF3F5F9),
    surface = Color(0xFFFFFFFF),
    surfaceGlass = Color(0xD9FFFFFF),
    surfaceRaised = Color(0xFFFFFFFF),
    surfaceSunken = Color(0xFFE7EAF1),
    hairline = Color(0x1A12151C),
    scrim = Color(0x66000000),
    textPrimary = Color(0xFF12151C),
    textSecondary = Color(0xFF454C5A),
    textTertiary = Color(0xFF606775),
    accent = Color(0xFF2C55E0),
    onAccent = Color(0xFFFFFFFF),
    accentContainer = Color(0xFFE2E9FF),
    onAccentContainer = Color(0xFF13328F),
    accentGradientStart = Color(0xFF3361F0),
    accentGradientEnd = Color(0xFF7A4DF2),
    onAccentGradient = Color(0xFFFFFFFF),
    highlight = Color(0xFF8A5900),
    success = Color(0xFF11744A),
    warning = Color(0xFF8F5A00),
    danger = Color(0xFFC62F2F),
    stage = Color(0xFF000000),
    onStage = Color(0xFFFFFFFF),
    stageControl = Color(0x8C1B1E26),
    timelineLane = Color(0xFFE4E8F0),
    trackVideo = Color(0xFF3B5FD9),
    trackMusic = Color(0xFF0B7A66),
    clipLabel = Color(0xFFFFFFFF),
    playhead = Color(0xFFE0451F),
    selection = Color(0xFFD9480F),
)

val DarkCineMindColors = CineMindColors(
    isDark = true,
    backdropTop = Color(0xFF141A33),
    backdropBottom = Color(0xFF0A0C11),
    background = Color(0xFF0B0D12),
    surface = Color(0xFF161A22),
    surfaceGlass = Color(0xCC1C212C),
    surfaceRaised = Color(0xFF1F2430),
    surfaceSunken = Color(0xFF0F1218),
    hairline = Color(0x1FFFFFFF),
    scrim = Color(0x99000000),
    textPrimary = Color(0xFFF2F4F8),
    textSecondary = Color(0xFFB6BDC9),
    textTertiary = Color(0xFF8D96A6),
    accent = Color(0xFF7D9BFF),
    onAccent = Color(0xFF0B0D12),
    accentContainer = Color(0xFF243266),
    onAccentContainer = Color(0xFFDCE4FF),
    accentGradientStart = Color(0xFF4561E6),
    accentGradientEnd = Color(0xFF7348E8),
    onAccentGradient = Color(0xFFFFFFFF),
    highlight = Color(0xFFF2B84B),
    success = Color(0xFF4ADE96),
    warning = Color(0xFFF5C46B),
    danger = Color(0xFFFF7A7A),
    stage = Color(0xFF000000),
    onStage = Color(0xFFFFFFFF),
    stageControl = Color(0x8C1B1E26),
    timelineLane = Color(0xFF12161E),
    trackVideo = Color(0xFF3F5FCF),
    trackMusic = Color(0xFF13806C),
    clipLabel = Color(0xFFFFFFFF),
    playhead = Color(0xFFFFB547),
    selection = Color(0xFFFFB547),
)

val LocalCineMindColors = staticCompositionLocalOf { DarkCineMindColors }

/** Spacing scale (dp). Use these instead of ad-hoc paddings. */
object Spacing {
    val xxs = 2.dp
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
    /** Screen side margin. */
    val gutter = 16.dp
    /** Minimum touch target. */
    val touch = 48.dp
}

/** Corner radii. */
object Radius {
    val xs = 6.dp
    val s = 10.dp
    val m = 16.dp
    val l = 22.dp
    val xl = 28.dp
    val pill = 999.dp
}

/** User's appearance choice. */
enum class ThemeMode(val label: String) {
    SYSTEM("System default"),
    LIGHT("Light"),
    DARK("Dark"),
    ;

    /** Whether to draw dark, given whether the phone itself is in dark mode. */
    fun isDark(systemDark: Boolean): Boolean = when (this) {
        SYSTEM -> systemDark
        LIGHT -> false
        DARK -> true
    }

    companion object {
        fun fromName(name: String?): ThemeMode = entries.firstOrNull { it.name == name } ?: SYSTEM
    }
}
