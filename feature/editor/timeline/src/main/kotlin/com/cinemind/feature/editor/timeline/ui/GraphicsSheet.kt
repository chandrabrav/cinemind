package com.cinemind.feature.editor.timeline.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cinemind.core.domain.text.GraphicSlot
import com.cinemind.core.domain.text.GraphicSuggestion
import com.cinemind.core.domain.text.GraphicsAdvisor
import com.cinemind.core.ui.components.CineMindSheet
import com.cinemind.core.ui.components.GradientButton
import com.cinemind.core.ui.format.formatShortTime
import com.cinemind.core.ui.theme.CineMind
import com.cinemind.core.ui.theme.Radius
import com.cinemind.core.ui.theme.Spacing
import kotlinx.coroutines.launch

/**
 * Graphics that suit this video (from its name, brief and filming date): an opening title, a
 * lower third, an end card and stickers, each in a fitting look. With an on-device AI model the
 * title can be written by AI. Tick what you want; they're added as normal, editable text layers.
 */
@Composable
internal fun GraphicsSheet(
    loadContext: suspend () -> TimelineViewModel.GraphicsContext,
    aiTitles: suspend (TimelineViewModel.GraphicsContext) -> List<String>,
    onAdd: (List<GraphicSuggestion>) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = CineMind.colors
    val scope = rememberCoroutineScope()
    var ctx by remember { mutableStateOf<TimelineViewModel.GraphicsContext?>(null) }
    var title by remember { mutableStateOf<String?>(null) }
    var options by remember { mutableStateOf<List<String>>(emptyList()) }
    var thinking by remember { mutableStateOf(false) }
    val unticked = remember { mutableStateListOf<GraphicSlot>() }
    val untickedStickers = remember { mutableStateListOf<String>() }
    LaunchedEffect(Unit) { ctx = loadContext() }

    CineMindSheet(onDismiss = onDismiss, title = "Graphics for this video") {
        val context = ctx
        if (context == null) {
            CircularProgressIndicator(modifier = Modifier.padding(Spacing.l).align(Alignment.CenterHorizontally))
            return@CineMindSheet
        }
        val suggestions = remember(context, title) {
            GraphicsAdvisor.suggest(context.projectName, context.brief, context.totalUs, filmedAt = context.filmedAt, aiTitle = title)
        }
        Text(
            "Picked from the video's name and brief" + (if (context.filmedAt != null) " and the date it was filmed" else "") + ". Everything stays editable.",
            style = MaterialTheme.typography.bodySmall,
            color = c.textSecondary,
        )
        if (context.aiAvailable) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(enabled = !thinking, onClick = {
                    thinking = true
                    scope.launch {
                        options = aiTitles(context)
                        options.firstOrNull()?.let { title = it }
                        thinking = false
                    }
                }) { Text(if (thinking) "AI is writing titles…" else "✨ Write the title with AI") }
                if (thinking) CircularProgressIndicator(modifier = Modifier.padding(start = Spacing.s).height(18.dp), strokeWidth = 2.dp)
            }
            if (options.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    options.forEach { o -> GfxPill(o, o == title) { title = o } }
                }
            }
        }
        Spacer(Modifier.height(Spacing.s))
        suggestions.forEach { s ->
            val on = if (s.slot == GraphicSlot.STICKER) s.text !in untickedStickers else s.slot !in unticked
            val shape = RoundedCornerShape(Radius.m)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .padding(top = Spacing.xs)
                    .fillMaxWidth()
                    .clip(shape)
                    .background(c.surfaceSunken)
                    .border(1.dp, if (on) c.accent else c.hairline, shape)
                    .clickable {
                        if (s.slot == GraphicSlot.STICKER) { if (on) untickedStickers += s.text else untickedStickers -= s.text }
                        else { if (on) unticked += s.slot else unticked -= s.slot }
                    }
                    .padding(horizontal = Spacing.s, vertical = Spacing.xs),
            ) {
                Checkbox(checked = on, onCheckedChange = null)
                Column(Modifier.weight(1f)) {
                    Text(
                        s.text,
                        style = MaterialTheme.typography.bodyLarge.copy(fontSize = if (s.slot == GraphicSlot.STICKER) 22.sp else 16.sp),
                        color = c.textPrimary,
                        maxLines = 2,
                    )
                    Text(
                        "${s.slot.label}${s.preset?.let { " · ${it.label}" } ?: ""} · ${formatShortTime(s.startUs)}–${formatShortTime(s.endUs)} · ${s.reason}",
                        style = MaterialTheme.typography.bodySmall,
                        color = c.textTertiary,
                    )
                }
            }
        }
        Spacer(Modifier.height(Spacing.m))
        val chosen = suggestions.filter { if (it.slot == GraphicSlot.STICKER) it.text !in untickedStickers else it.slot !in unticked }
        GradientButton(
            if (chosen.isEmpty()) "Nothing selected" else "Add ${chosen.size} to the video",
            onClick = { onAdd(chosen); onDismiss() },
            enabled = chosen.isNotEmpty(),
            icon = Icons.Default.AutoAwesome,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun GfxPill(text: String, selected: Boolean, onClick: () -> Unit) {
    val c = CineMind.colors
    val shape = RoundedCornerShape(Radius.xl)
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = if (selected) c.onAccent else c.textPrimary,
        modifier = Modifier
            .clip(shape)
            .background(if (selected) c.accent else c.surfaceSunken)
            .border(1.dp, if (selected) c.accent else c.hairline, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.m, vertical = Spacing.xs),
    )
}
