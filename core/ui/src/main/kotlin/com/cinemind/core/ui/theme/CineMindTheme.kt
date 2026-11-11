package com.cinemind.core.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import java.util.Locale

/**
 * App-wide theme. [mode] comes from the persisted appearance setting; SYSTEM follows Android.
 * Provides Material 3 colours derived from [CineMindColors] plus the tokens themselves.
 */
@Composable
fun CineMindTheme(
    mode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit,
) {
    val colors = if (mode.isDark(isSystemInDarkTheme())) DarkCineMindColors else LightCineMindColors
    CineMindTheme(colors = colors, content = content)
}

/** Theme with explicit tokens (used by the Settings preview to show both themes side by side). */
@Composable
fun CineMindTheme(colors: CineMindColors, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalCineMindColors provides colors) {
        MaterialTheme(
            colorScheme = colors.toColorScheme(),
            typography = CineMindTypography,
            shapes = CineMindShapes,
            content = content,
        )
    }
}

/** Shorthand: `CineMind.colors.textSecondary`. */
object CineMind {
    val colors: CineMindColors
        @Composable @ReadOnlyComposable get() = LocalCineMindColors.current
}

fun CineMindColors.toColorScheme(): ColorScheme {
    val base = if (isDark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = accent,
        onPrimary = onAccent,
        primaryContainer = accentContainer,
        onPrimaryContainer = onAccentContainer,
        secondary = highlight,
        onSecondary = if (isDark) background else surface,
        secondaryContainer = accentContainer,
        onSecondaryContainer = onAccentContainer,
        tertiary = success,
        background = background,
        onBackground = textPrimary,
        surface = surface,
        onSurface = textPrimary,
        surfaceVariant = surfaceSunken,
        onSurfaceVariant = textSecondary,
        surfaceContainerLowest = background,
        surfaceContainerLow = surface,
        surfaceContainer = surface,
        surfaceContainerHigh = surfaceRaised,
        surfaceContainerHighest = surfaceSunken,
        surfaceTint = accent,
        inverseSurface = textPrimary,
        inverseOnSurface = background,
        inversePrimary = accentContainer,
        error = danger,
        onError = if (isDark) background else surface,
        outline = textTertiary,
        outlineVariant = hairline,
        scrim = scrim,
    )
}

private fun style(size: Float, weight: FontWeight, line: Float, tracking: Float = 0f) = TextStyle(
    fontFamily = FontFamily.Default,
    fontSize = size.sp,
    fontWeight = weight,
    lineHeight = line.sp,
    letterSpacing = tracking.sp,
)

/** System font (Vivo's own UI font on the target phone) with a calm, clear hierarchy. */
val CineMindTypography = Typography(
    displayLarge = style(48f, FontWeight.SemiBold, 54f, -1f),
    displayMedium = style(40f, FontWeight.SemiBold, 46f, -0.8f),
    displaySmall = style(34f, FontWeight.SemiBold, 40f, -0.6f),
    headlineLarge = style(30f, FontWeight.SemiBold, 36f, -0.4f),
    headlineMedium = style(26f, FontWeight.SemiBold, 32f, -0.3f),
    headlineSmall = style(22f, FontWeight.SemiBold, 28f, -0.2f),
    titleLarge = style(20f, FontWeight.SemiBold, 26f),
    titleMedium = style(16f, FontWeight.SemiBold, 22f),
    titleSmall = style(14f, FontWeight.Medium, 20f),
    bodyLarge = style(16f, FontWeight.Normal, 23f),
    bodyMedium = style(14f, FontWeight.Normal, 20f),
    bodySmall = style(12.5f, FontWeight.Normal, 17f),
    labelLarge = style(14f, FontWeight.Medium, 18f),
    labelMedium = style(12f, FontWeight.Medium, 16f),
    labelSmall = style(11f, FontWeight.Medium, 14f, 0.2f),
)

val CineMindShapes = Shapes(
    extraSmall = RoundedCornerShape(Radius.xs),
    small = RoundedCornerShape(Radius.s),
    medium = RoundedCornerShape(Radius.m),
    large = RoundedCornerShape(Radius.l),
    extraLarge = RoundedCornerShape(Radius.xl),
)

/**
 * Formats a timeline position as HH:MM:SS:FF.
 * Frames are floored (a frame is shown only once it has fully started), so the
 * value can never roll over to [fps].
 */
fun formatTimecode(microseconds: Long, fps: Int = 30): String {
    require(fps > 0) { "fps must be positive" }
    val us = microseconds.coerceAtLeast(0)
    val totalSeconds = us / 1_000_000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    val frames = (us % 1_000_000) * fps / 1_000_000
    return String.format(Locale.ROOT, "%02d:%02d:%02d:%02d", hours, minutes, seconds, frames)
}
