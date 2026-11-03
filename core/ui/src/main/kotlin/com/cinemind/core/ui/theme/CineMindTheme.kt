package com.cinemind.core.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.readOnlyComposable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color

// Color Palette
private val DarkColorPalette = darkColorScheme(
    primary = Color(0xFFFFD600),
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
    scrim = Color(0xFF000000)
)

// Track Colors
val TrackVideoColor = Color(0xFF1E3A5F)
val TrackAudioColor = Color(0xFF5F1E3A)
val TrackEffectColor = Color(0xFF3A5F1E)
val TrackOverlayColor = Color(0xFF5F3A1E)

@Composable
@readOnlyComposable
fun CineMindColorScheme(): ColorScheme = DarkColorPalette

@Composable
fun CineMindTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorPalette else LightColorScheme()
    MaterialTheme(
        colorScheme = colorScheme,
        typography = CineMindTypography,
        shapes = CineMindShapes,
        content = content
    )
}

val CineMindTypography = Typography(
    displayLarge = TextStyle(fontFamily = FontFamily.Default, fontSize = 57.sp, fontWeight = FontWeight.W400),
    displayMedium = TextStyle(fontFamily = FontFamily.Default, fontSize = 45.sp, fontWeight = FontWeight.W400),
    displaySmall = TextStyle(fontFamily = FontFamily.Default, fontSize = 36.sp, fontWeight = FontWeight.W400),
    headlineLarge = TextStyle(fontFamily = FontFamily.Default, fontSize = 32.sp, fontWeight = FontWeight.W400),
    headlineMedium = TextStyle(fontFamily = FontFamily.Default, fontSize = 28.sp, fontWeight = FontWeight.W400),
    headlineSmall = TextStyle(fontFamily = FontFamily.Default, fontSize = 24.sp, fontWeight = FontWeight.W400),
    titleLarge = TextStyle(fontFamily = FontFamily.Default, fontSize = 22.sp, fontWeight = FontWeight.W500),
    titleMedium = TextStyle(fontFamily = FontFamily.Default, fontSize = 16.sp, fontWeight = FontWeight.W500),
    titleSmall = TextStyle(fontFamily = FontFamily.Default, fontSize = 14.sp, fontWeight = FontWeight.W500),
    bodyLarge = TextStyle(fontFamily = FontFamily.Default, fontSize = 16.sp, fontWeight = FontWeight.W400),
    bodyMedium = TextStyle(fontFamily = FontFamily.Default, fontSize = 14.sp, fontWeight = FontWeight.W400),
    bodySmall = TextStyle(fontFamily = FontFamily.Default, fontSize = 12.sp, fontWeight = FontWeight.W400),
    labelLarge = TextStyle(fontFamily = FontFamily.Default, fontSize = 14.sp, fontWeight = FontWeight.W500),
    labelMedium = TextStyle(fontFamily = FontFamily.Default, fontSize = 12.sp, fontWeight = FontWeight.W500),
    labelSmall = TextStyle(fontFamily = FontFamily.Default, fontSize = 11.sp, fontWeight = FontWeight.W500),
)

val CineMindShapes = Shapes(
    small = CornerShape(4.dp),
    medium = CornerShape(8.dp),
    large = CornerShape(16.dp),
    extraLarge = CornerShape(28.dp)
)

// Timecode formatting utility
fun formatTimecode(microseconds: Long): String {
    val totalSeconds = microseconds / 1_000_000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    val frames = ((microseconds % 1_000_000) / 1_000_000.0 * 30).roundToInt()
    return "${hours.toString().padStart(2, '0')}:${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}:${frames.toString().padStart(2, '0')}"
}