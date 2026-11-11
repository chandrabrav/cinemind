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
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

// Color Palette
private val DarkColorPalette = darkColorScheme(
    primary = Color(0xFFFFD600),
    onPrimary = Color(0xFF0D0D0D),
    primaryContainer = Color(0xFF1A1A1A),
    secondary = Color(0xFF00E5FF),
    secondaryContainer = Color(0xFF262626),
    tertiary = Color(0xFFFF3D00),
    surface = Color(0xFF0D0D0D),
    surfaceContainer = Color(0xFF1A1A1A),
    surfaceContainerHigh = Color(0xFF262626),
    surfaceContainerHighest = Color(0xFF333333),
    background = Color(0xFF0D0D0D),
    onBackground = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFF1A1A1A),
    onSurface = Color(0xFFFFFFFF),
    onSurfaceVariant = Color(0xFFB3B3B3),
    inverseSurface = Color(0xFFB3B3B3),
    inverseOnSurface = Color(0xFF0D0D0D),
    inversePrimary = Color(0xFF0D0D0D),
    error = Color(0xFFFF3D00),
    errorContainer = Color(0xFF1A0D0D),
    outline = Color(0xFF404040),
    outlineVariant = Color(0xFF2A2A2A),
    scrim = Color(0xFF000000),
)

// Provisional light palette; the full design system (Stage 1) will refine both themes.
private val LightColorPalette = lightColorScheme(
    primary = Color(0xFF8A6D00),
    onPrimary = Color(0xFFFFFFFF),
    secondary = Color(0xFF006874),
    tertiary = Color(0xFFB02F00),
    surface = Color(0xFFFAFAFA),
    background = Color(0xFFFAFAFA),
    onSurface = Color(0xFF111111),
    onSurfaceVariant = Color(0xFF4D4D4D),
    error = Color(0xFFB3261E),
)

// Track Colors
val TrackVideoColor = Color(0xFF1E3A5F)
val TrackAudioColor = Color(0xFF5F1E3A)
val TrackEffectColor = Color(0xFF3A5F1E)
val TrackOverlayColor = Color(0xFF5F3A1E)

@Composable
@ReadOnlyComposable
fun CineMindColorScheme(): ColorScheme = MaterialTheme.colorScheme

@Composable
fun CineMindTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColorPalette else LightColorPalette,
        typography = CineMindTypography,
        shapes = CineMindShapes,
        content = content,
    )
}

private fun style(size: Int, weight: FontWeight) =
    TextStyle(fontFamily = FontFamily.Default, fontSize = size.sp, fontWeight = weight)

val CineMindTypography = Typography(
    displayLarge = style(57, FontWeight.W400),
    displayMedium = style(45, FontWeight.W400),
    displaySmall = style(36, FontWeight.W400),
    headlineLarge = style(32, FontWeight.W400),
    headlineMedium = style(28, FontWeight.W400),
    headlineSmall = style(24, FontWeight.W400),
    titleLarge = style(22, FontWeight.W500),
    titleMedium = style(16, FontWeight.W500),
    titleSmall = style(14, FontWeight.W500),
    bodyLarge = style(16, FontWeight.W400),
    bodyMedium = style(14, FontWeight.W400),
    bodySmall = style(12, FontWeight.W400),
    labelLarge = style(14, FontWeight.W500),
    labelMedium = style(12, FontWeight.W500),
    labelSmall = style(11, FontWeight.W500),
)

val CineMindShapes = Shapes(
    small = RoundedCornerShape(4.dp),
    medium = RoundedCornerShape(8.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp),
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
