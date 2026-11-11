package com.cinemind.feature.ai.generation.ui

import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cinemind.core.domain.model.EditBrief
import com.cinemind.core.domain.model.EditBriefRules
import com.cinemind.core.domain.model.EditStyle
import com.cinemind.core.domain.model.Pacing
import com.cinemind.core.domain.model.Project
import com.cinemind.core.domain.model.ProjectFormat
import com.cinemind.core.ui.components.ChoicePills
import com.cinemind.core.ui.components.GlassCard
import com.cinemind.core.ui.components.GradientButton
import com.cinemind.core.ui.components.Pill
import com.cinemind.core.ui.components.ScreenHeader
import com.cinemind.core.ui.components.SectionHeader
import com.cinemind.core.ui.format.formatDuration
import com.cinemind.core.ui.theme.CineMind
import com.cinemind.core.ui.theme.Radius
import com.cinemind.core.ui.theme.Spacing
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items

/**
 * Where the user describes the edit they want. The brief is saved with the project and drives
 * Smart edit (on-device analysis, plus the on-device AI model when one is installed).
 * Format changes apply to the project right away.
 */
@Composable
fun EditBriefScreen(
    projectId: String,
    onSmartEditCreated: (String) -> Unit,
    viewModel: EditBriefViewModel = hiltViewModel(),
) {
    LaunchedEffect(projectId) { viewModel.load(projectId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val project by viewModel.project.collectAsStateWithLifecycle()
    val smartEdit by viewModel.smartEdit.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(viewModel) {
        viewModel.createdFlow.collect { result ->
            Toast.makeText(context, "Smart edit ready: " + result.decisions.first(), Toast.LENGTH_LONG).show()
            onSmartEditCreated(result.projectId)
        }
    }

    // Save pending edits when leaving the screen or when the app goes to the background.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) viewModel.flush()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.flush()
        }
    }

    val brief = state.brief
    if (!state.loaded || brief == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = CineMind.colors.accent)
        }
        return
    }
    val c = CineMind.colors

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = Spacing.xxl),
    ) {
        ScreenHeader(
            title = "Describe your edit",
            subtitle = project?.let { "${it.name} · ${formatDuration(it.durationUs)} of footage" },
        ) { SaveState(state.saved) }

        GlassCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.gutter),
        ) {
            Text("What should this video be?", style = MaterialTheme.typography.titleSmall, color = c.textPrimary)
            Spacer(Modifier.height(Spacing.s))
            OutlinedTextField(
                value = brief.prompt,
                onValueChange = viewModel::setPrompt,
                placeholder = { Text("e.g. A dreamy travel reel of our Goa trip — sunsets, beach, friends laughing", color = c.textTertiary) },
                supportingText = { Text("${brief.prompt.length} / ${EditBriefRules.MAX_PROMPT_LENGTH}", color = c.textTertiary) },
                minLines = 4,
                shape = RoundedCornerShape(Radius.m),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = c.accent,
                    unfocusedBorderColor = c.hairline,
                    focusedContainerColor = c.surface,
                    unfocusedContainerColor = c.surface,
                    cursorColor = c.accent,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Lightbulb, contentDescription = null, tint = c.highlight, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(Spacing.xs))
                Text("Tap to add an idea", style = MaterialTheme.typography.labelMedium, color = c.textTertiary)
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s), contentPadding = PaddingValues(vertical = Spacing.xs)) {
                items(EditBriefRules.SUGGESTIONS) { text ->
                    Pill(text = text, selected = false, onClick = { viewModel.addSuggestion(text) })
                }
            }
        }

        SectionHeader("Style", modifier = Modifier.padding(top = Spacing.m))
        ChoicePills(EditStyle.entries, brief.style, { it.label }, viewModel::setStyle)

        SectionHeader("Length")
        ChoicePills(
            items = listOf<Int?>(null) + EditBriefRules.DURATION_OPTIONS_SEC,
            selected = brief.targetDurationSec,
            label = { if (it == null) "Auto" else "$it s" },
            onSelect = viewModel::setTargetDuration,
        )

        SectionHeader("Pacing")
        ChoicePills(Pacing.entries, brief.pacing, { it.label }, viewModel::setPacing)

        SectionHeader("Format", hint = "Changes the project's frame now — preview and export follow it.")
        ChoicePills(
            items = ProjectFormat.entries,
            selected = ProjectFormat.entries.firstOrNull { it.ratio == project?.aspectRatio },
            label = { "${it.ratio} · ${it.label}" },
            onSelect = viewModel::setFormat,
        )

        Notices(brief = brief, footageUs = project?.durationUs ?: 0)

        GenerateCard(
            state = smartEdit,
            hasFootage = (project?.durationUs ?: 0) > 0,
            onCreate = viewModel::createSmartEdit,
            onCancel = viewModel::cancelSmartEdit,
        )
    }
}

@Composable
private fun SaveState(saved: Boolean) {
    val c = CineMind.colors
    AnimatedContent(saved, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "saved") { isSaved ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (isSaved) {
                Icon(Icons.Default.Check, contentDescription = null, tint = c.success, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(Spacing.xs))
                Text("Saved", style = MaterialTheme.typography.labelMedium, color = c.textSecondary)
            } else {
                Text("Saving…", style = MaterialTheme.typography.labelMedium, color = c.textTertiary)
            }
        }
    }
}

@Composable
private fun Notices(brief: EditBrief, footageUs: Long) {
    val notices = EditBriefRules.notices(brief, footageUs)
    if (notices.isEmpty()) return
    val c = CineMind.colors
    Column(modifier = Modifier.padding(horizontal = Spacing.gutter, vertical = Spacing.m)) {
        notices.forEach { text ->
            Row(
                verticalAlignment = Alignment.Top,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = Spacing.xxs)
                    .clip(RoundedCornerShape(Radius.s))
                    .background(c.highlight.copy(alpha = 0.10f))
                    .padding(Spacing.s),
            ) {
                Icon(Icons.Outlined.Info, contentDescription = null, tint = c.highlight, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(Spacing.s))
                Text(text, style = MaterialTheme.typography.bodySmall, color = c.textPrimary)
            }
        }
    }
}

@Composable
private fun GenerateCard(
    state: SmartEditUi,
    hasFootage: Boolean,
    onCreate: () -> Unit,
    onCancel: () -> Unit,
) {
    val c = CineMind.colors
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.gutter, vertical = Spacing.m),
    ) {
        when (state) {
            is SmartEditUi.Working -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Sparkle()
                    Spacer(Modifier.width(Spacing.m))
                    Text(state.message, style = MaterialTheme.typography.titleSmall, color = c.textPrimary, modifier = Modifier.weight(1f))
                }
                Spacer(Modifier.height(Spacing.m))
                val p = state.progress
                if (p != null) {
                    LinearProgressIndicator(progress = { p }, color = c.accent, trackColor = c.surfaceSunken, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(color = c.accent, trackColor = c.surfaceSunken, modifier = Modifier.fillMaxWidth())
                }
                Spacer(Modifier.height(Spacing.m))
                OutlinedButton(onClick = onCancel) { Text("Cancel", color = c.textSecondary) }
            }
            else -> {
                GradientButton(
                    "Create smart edit",
                    onClick = onCreate,
                    icon = Icons.Default.AutoAwesome,
                    enabled = hasFootage,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (state is SmartEditUi.Failed) {
                    Spacer(Modifier.height(Spacing.s))
                    Text(state.message, style = MaterialTheme.typography.bodySmall, color = c.danger)
                }
            }
        }
        Spacer(Modifier.height(Spacing.m))
        Text(
            "Measures every clip on this phone (sharpness, exposure, motion). If an on-device AI model is " +
                "installed (✦ on the home screen) it also reads your brief and looks at key moments. The result " +
                "is a new, fully editable project — this one isn't changed.",
            style = MaterialTheme.typography.bodySmall,
            color = c.textTertiary,
        )
    }
}

/** Soft pulsing gradient dot shown while Smart edit works. */
@Composable
private fun Sparkle() {
    val c = CineMind.colors
    val t = rememberInfiniteTransition(label = "sparkle")
    val a by t.animateFloat(0.45f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "alpha")
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(36.dp)
            .alpha(a)
            .clip(RoundedCornerShape(Radius.s))
            .background(Brush.linearGradient(listOf(c.accentGradientStart, c.accentGradientEnd))),
    ) {
        Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = c.onAccentGradient, modifier = Modifier.size(20.dp))
    }
}
