package com.cinemind.feature.editor.timeline.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.LayoutInflater
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import com.cinemind.core.ui.theme.CineMind
import com.cinemind.core.ui.theme.Spacing
import com.cinemind.core.ui.theme.formatTimecode
import com.cinemind.feature.editor.timeline.R
import com.cinemind.feature.editor.timeline.preview.PreviewState
import kotlinx.coroutines.delay

/**
 * Immersive preview: the same player and composition as the editor preview (so every edit shows,
 * the playhead is kept and nothing is re-decoded), with the editor UI and system bars hidden and the
 * project's aspect ratio preserved. Tap shows/hides the controls, double-tap plays/pauses, a
 * horizontal swipe scrubs, Back or the exit button returns. Never changes the project.
 */
@Composable
internal fun FullscreenPreview(
    player: Player,
    state: PreviewState,
    ratio: Float,
    fps: Int,
    onTogglePlay: () -> Unit,
    onScrub: (Long) -> Unit,
    onScrubFinished: (Long) -> Unit,
    onExit: () -> Unit,
    captionLayer: @Composable () -> Unit = {},
) {
    // Its own full-screen window, so it also covers the app bar; Back (dialog dismiss) exits.
    Dialog(
        onDismissRequest = onExit,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false, dismissOnClickOutside = false),
    ) {
        FullscreenContent(player, state, ratio, fps, onTogglePlay, onScrub, onScrubFinished, onExit, captionLayer)
    }
}

@Composable
private fun FullscreenContent(
    player: Player,
    state: PreviewState,
    ratio: Float,
    fps: Int,
    onTogglePlay: () -> Unit,
    onScrub: (Long) -> Unit,
    onScrubFinished: (Long) -> Unit,
    onExit: () -> Unit,
    captionLayer: @Composable () -> Unit,
) {
    val c = CineMind.colors
    ImmersiveSystemBars()

    var controlsVisible by remember { mutableStateOf(true) }
    var interactions by remember { mutableIntStateOf(0) }
    // Controls fade out after 3 s of playback without touches; they stay while paused.
    LaunchedEffect(controlsVisible, state.isPlaying, interactions) {
        if (controlsVisible && state.isPlaying) {
            delay(3_000)
            controlsVisible = false
        }
    }
    val currentState by rememberUpdatedState(state)
    var dragFromUs by remember { mutableStateOf<Long?>(null) }
    var dragUs by remember { mutableStateOf(0L) }

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .semantics { contentDescription = "Full-screen preview. Tap for controls, double-tap to play or pause, swipe sideways to scrub." }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { controlsVisible = !controlsVisible; interactions++ },
                    onDoubleTap = { onTogglePlay(); controlsVisible = true; interactions++ },
                )
            }
            .pointerInput(Unit) {
                // A full-width swipe moves through the whole video.
                detectHorizontalDragGestures(
                    onDragStart = { dragFromUs = currentState.positionUs; dragUs = currentState.positionUs; controlsVisible = true },
                    onDragEnd = { dragFromUs?.let { onScrubFinished(dragUs) }; dragFromUs = null; interactions++ },
                    onDragCancel = { dragFromUs = null },
                    onHorizontalDrag = { change, dx ->
                        change.consume()
                        val total = currentState.durationUs
                        if (total > 0 && size.width > 0) {
                            dragUs = (dragUs + (dx / size.width * total).toLong()).coerceIn(0, total)
                            onScrub(dragUs)
                        }
                    },
                )
            },
    ) {
        Box(modifier = Modifier.aspectRatio(ratio.coerceIn(0.2f, 5f))) {
            AndroidView(
                factory = { ctx ->
                    (LayoutInflater.from(ctx).inflate(R.layout.preview_player_view, null) as PlayerView).apply { this.player = player }
                },
                update = { it.player = player },
                onRelease = { view -> runCatching { view.player = null } },
                modifier = Modifier.fillMaxSize(),
            )
            captionLayer()
        }

        AnimatedVisibility(visible = controlsVisible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize()) {
                IconButton(
                    onClick = onExit,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .statusBarsPadding()
                        .padding(Spacing.s)
                        .clip(CircleShape)
                        .background(c.stageControl),
                ) {
                    Icon(Icons.Default.FullscreenExit, contentDescription = "Exit full screen", tint = c.onStage)
                }
                var sliderValue by remember { mutableFloatStateOf(0f) }
                var sliding by remember { mutableStateOf(false) }
                val shown = if (sliding) sliderValue else state.positionUs.toFloat()
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(Spacing.m)
                        .clip(RoundedCornerShape(28.dp))
                        .background(c.stageControl)
                        .padding(horizontal = Spacing.s, vertical = Spacing.xs),
                ) {
                    IconButton(onClick = { onTogglePlay(); interactions++ }) {
                        Icon(
                            if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (state.isPlaying) "Pause" else "Play",
                            tint = c.onStage,
                            modifier = Modifier.size(28.dp),
                        )
                    }
                    Text(
                        "${formatTimecode(shown.toLong(), fps)} / ${formatTimecode(state.durationUs, fps)}",
                        color = c.onStage,
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Spacer(Modifier.width(Spacing.s))
                    Slider(
                        value = shown.coerceIn(0f, state.durationUs.toFloat().coerceAtLeast(1f)),
                        valueRange = 0f..state.durationUs.toFloat().coerceAtLeast(1f),
                        onValueChange = { sliding = true; sliderValue = it; onScrub(it.toLong()); interactions++ },
                        onValueChangeFinished = { sliding = false; onScrubFinished(sliderValue.toLong()) },
                        colors = SliderDefaults.colors(thumbColor = c.onStage, activeTrackColor = c.onStage, inactiveTrackColor = c.onStage.copy(alpha = 0.3f)),
                        modifier = Modifier.weight(1f).semantics { contentDescription = "Playback position" },
                    )
                }
            }
        }
    }
}

/** Hides status and navigation bars while shown (swipe from an edge reveals them briefly). */
@Composable
private fun ImmersiveSystemBars() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = (view.parent as? DialogWindowProvider)?.window ?: view.context.findActivity()?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
