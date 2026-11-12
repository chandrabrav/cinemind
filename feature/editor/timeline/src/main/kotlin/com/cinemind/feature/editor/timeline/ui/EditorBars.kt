package com.cinemind.feature.editor.timeline.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.cinemind.core.ui.components.CineMindSheet
import com.cinemind.core.ui.components.ToolButton
import com.cinemind.core.ui.theme.CineMind
import com.cinemind.core.ui.theme.Radius
import com.cinemind.core.ui.theme.Spacing
import com.cinemind.core.ui.theme.formatTimecode
import com.cinemind.feature.editor.timeline.preview.PreviewState
import kotlinx.coroutines.delay

/** One button on a bottom bar. */
internal class EditorAction(val icon: ImageVector, val label: String, val enabled: Boolean = true, val onClick: () -> Unit)

/**
 * The editor's single bottom bar. With nothing selected it shows the main tools; with a clip,
 * overlay, sound, voiceover or title selected it shows only that item's actions (✕ returns to the
 * main tools). Detailed controls open as sheets, so the timeline is never pushed off the screen.
 */
@Composable
internal fun EditorBottomBar(title: String?, actions: List<EditorAction>, onClose: (() -> Unit)? = null) {
    val c = CineMind.colors
    val shape = RoundedCornerShape(Radius.xl)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.s, vertical = Spacing.xs)
            .clip(shape)
            .background(c.surfaceGlass)
            .border(1.dp, c.hairline, shape)
            .padding(vertical = Spacing.xxs),
    ) {
        if (onClose != null) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(start = Spacing.xs)) {
                IconButton(onClick = onClose, modifier = Modifier.size(40.dp).semantics { contentDescription = "Done with ${title ?: "selection"}" }) {
                    Icon(Icons.Default.Close, contentDescription = null, tint = c.textPrimary)
                }
                if (title != null) {
                    Text(title, style = MaterialTheme.typography.labelSmall, color = c.accent, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(56.dp))
                }
            }
            Box(Modifier.padding(vertical = Spacing.s).width(1.dp).size(width = 1.dp, height = 40.dp).background(c.hairline))
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(Spacing.xxs),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()).padding(horizontal = Spacing.xs),
        ) {
            actions.forEach { a -> ToolButton(a.icon, a.label, onClick = a.onClick, enabled = a.enabled, minWidth = 64.dp) }
        }
    }
}

/** Audio: background music, sound effects, voiceover. */
@Composable
internal fun AudioMenuSheet(hasMusic: Boolean, onMusic: () -> Unit, onSounds: () -> Unit, onVoice: () -> Unit, onDismiss: () -> Unit, onFindMusic: () -> Unit = {}) {
    CineMindSheet(onDismiss = onDismiss, title = "Audio") {
        MenuRow(Icons.Default.MusicNote, "Music", if (hasMusic) "Change the song, volume, beats" else "Add a song from your phone; cut to its beat") { onDismiss(); onMusic() }
        MenuRow(Icons.Default.Search, "Find music", "Song ideas for this video, free music libraries, Instagram sounds") { onDismiss(); onFindMusic() }
        MenuRow(Icons.Default.GraphicEq, "Sound effects", "Whoosh, impact, pop, ding… or your own sounds") { onDismiss(); onSounds() }
        MenuRow(Icons.Default.Mic, "Voiceover", "Record narration over the video") { onDismiss(); onVoice() }
    }
}

@Composable
private fun MenuRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    val c = CineMind.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.m))
            .clickable(onClick = onClick)
            .padding(vertical = Spacing.m, horizontal = Spacing.s),
    ) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(c.accentContainer), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = c.onAccentContainer)
        }
        Spacer(Modifier.width(Spacing.m))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = c.textPrimary)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = c.textTertiary)
    }
}

/**
 * Controls shown over the preview in full screen (the same video view grows to fill the screen;
 * nothing is handed to another view or window). Tap toggles them; they hide after 3 s of playback.
 */
@Composable
internal fun FullscreenControls(
    state: PreviewState,
    fps: Int,
    onTogglePlay: () -> Unit,
    onScrub: (Long) -> Unit,
    onScrubFinished: (Long) -> Unit,
    onExit: () -> Unit,
    visible: Boolean,
    onInteract: () -> Unit,
) {
    val c = CineMind.colors
    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
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
                IconButton(onClick = { onTogglePlay(); onInteract() }) {
                    Icon(
                        if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (state.isPlaying) "Pause" else "Play",
                        tint = c.onStage,
                        modifier = Modifier.size(28.dp),
                    )
                }
                Text("${formatTimecode(shown.toLong(), fps)} / ${formatTimecode(state.durationUs, fps)}", color = c.onStage, style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.width(Spacing.s))
                Slider(
                    value = shown.coerceIn(0f, state.durationUs.toFloat().coerceAtLeast(1f)),
                    valueRange = 0f..state.durationUs.toFloat().coerceAtLeast(1f),
                    onValueChange = { sliding = true; sliderValue = it; onScrub(it.toLong()); onInteract() },
                    onValueChangeFinished = { sliding = false; onScrubFinished(sliderValue.toLong()) },
                    colors = SliderDefaults.colors(thumbColor = c.onStage, activeTrackColor = c.onStage, inactiveTrackColor = c.onStage.copy(alpha = 0.3f)),
                    modifier = Modifier.weight(1f).semantics { contentDescription = "Playback position" },
                )
            }
        }
    }
}

/** Hides status and navigation bars while composed (swipe from an edge shows them briefly). */
@Composable
internal fun ImmersiveBars() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = view.context.findActivity()?.window
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
