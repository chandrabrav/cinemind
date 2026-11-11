package com.cinemind.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import com.cinemind.core.ui.theme.CineMind
import com.cinemind.core.ui.theme.Spacing
import com.cinemind.feature.settings.ui.Branding
import kotlinx.coroutines.delay

/**
 * The CineMind AI wordmark for a moment after the system splash (which can only show the icon),
 * with a quiet developer credit. Fades out by itself after ~1 s; a tap skips it. The app is
 * already loading underneath, so this adds no wait.
 */
@Composable
fun LaunchBrand() {
    val c = CineMind.colors
    var visible by rememberSaveable { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        delay(1_000)
        visible = false
    }
    AnimatedVisibility(visible = visible, enter = fadeIn(tween(0)), exit = fadeOut(tween(350))) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(c.backdropTop, c.backdropBottom)))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { visible = false },
        ) {
            Text(
                Branding.APP_NAME,
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.SemiBold,
                color = c.textPrimary,
            )
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = Spacing.xxl),
            ) {
                Text(Branding.DEVELOPER_CREDIT, style = MaterialTheme.typography.labelMedium, color = c.textTertiary)
            }
        }
    }
}
