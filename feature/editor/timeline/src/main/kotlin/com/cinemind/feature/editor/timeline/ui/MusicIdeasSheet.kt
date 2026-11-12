package com.cinemind.feature.editor.timeline.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.cinemind.core.domain.audio.InstagramSound
import com.cinemind.core.domain.audio.MusicAdvisor
import com.cinemind.core.domain.audio.MusicIdea
import com.cinemind.core.ui.components.CineMindSheet
import com.cinemind.core.ui.theme.CineMind
import com.cinemind.core.ui.theme.Radius
import com.cinemind.core.ui.theme.Spacing

/** Per-project saved Instagram sound link (a reminder for posting; nothing is downloaded). */
internal object InstagramSoundStore {
    private fun prefs(c: Context) = c.getSharedPreferences("instagram_sounds", Context.MODE_PRIVATE)
    fun get(c: Context, projectId: String): InstagramSound? = prefs(c).getString(projectId, null)?.let { InstagramSound.parse(it) }
    fun set(c: Context, projectId: String, sound: InstagramSound?) {
        prefs(c).edit().apply { if (sound == null) remove(projectId) else putString(projectId, sound.url) }.apply()
    }
}

/**
 * Music ideas for this video: moods and genres that suit its theme (and the current song's tempo),
 * with search terms to open in free-to-use music libraries; plus an Instagram sound to remember
 * for posting. CineMind doesn't download or scrape music from any platform.
 */
@Composable
internal fun MusicIdeasSheet(projectId: String, themeText: String, bpm: Float, onChooseFile: () -> Unit, onDismiss: () -> Unit) {
    val c = CineMind.colors
    val context = LocalContext.current
    val ideas = remember(themeText, bpm) { MusicAdvisor.suggest(themeText, bpm) }
    var term by remember { mutableStateOf<String?>(null) }
    var saved by remember(projectId) { mutableStateOf(InstagramSoundStore.get(context, projectId)) }
    var link by remember { mutableStateOf("") }
    var linkError by remember { mutableStateOf<String?>(null) }
    fun open(url: String) = runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }

    CineMindSheet(onDismiss = onDismiss, title = "Find music") {
        Text("Suggested for this video", style = MaterialTheme.typography.labelLarge, color = c.textSecondary)
        ideas.forEach { idea -> IdeaCard(idea, selectedTerm = term) { term = it } }
        val t = term
        if (t != null) {
            Spacer(Modifier.height(Spacing.s))
            Text("Search “$t” in:", style = MaterialTheme.typography.labelLarge, color = c.textSecondary)
            MusicAdvisor.sources.forEach { s ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(Radius.m))
                        .clickable { open(s.searchUrl(t)) }
                        .padding(vertical = Spacing.s),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(s.name, style = MaterialTheme.typography.bodyMedium, color = c.accent)
                        Text(s.note, style = MaterialTheme.typography.bodySmall, color = c.textTertiary)
                    }
                }
            }
            Text(
                "Download a track you're allowed to use, then add it here.",
                style = MaterialTheme.typography.bodySmall,
                color = c.textSecondary,
            )
            TextButton(onClick = { onDismiss(); onChooseFile() }) { Text("Choose a song from my phone") }
        }

        Spacer(Modifier.height(Spacing.l))
        Text("Instagram sound", style = MaterialTheme.typography.labelLarge, color = c.textSecondary)
        Text(
            "Instagram's songs can only be added inside Instagram. Paste the Reel or audio link here and CineMind keeps it with this project, so you can add that exact sound when you post.",
            style = MaterialTheme.typography.bodySmall,
            color = c.textTertiary,
        )
        val s = saved
        if (s != null) {
            Text(
                (if (s.kind == InstagramSound.Kind.AUDIO_PAGE) "Audio " + (s.audioId ?: "") else "Reel") + " · " + s.url,
                style = MaterialTheme.typography.bodySmall,
                color = c.textPrimary,
                maxLines = 2,
            )
            Row {
                TextButton(onClick = { open(s.url) }) { Text("Open in Instagram") }
                TextButton(onClick = { InstagramSoundStore.set(context, projectId, null); saved = null }) { Text("Remove", color = c.danger) }
            }
        } else {
            OutlinedTextField(
                value = link,
                onValueChange = { link = it; linkError = null },
                label = { Text("instagram.com/reels/audio/…") },
                singleLine = true,
                isError = linkError != null,
                supportingText = linkError?.let { e -> { Text(e) } },
                modifier = Modifier.fillMaxWidth(),
                trailingIcon = {
                    TextButton(onClick = {
                        val parsed = InstagramSound.parse(link)
                        if (parsed == null) linkError = "That isn't an Instagram link" else {
                            InstagramSoundStore.set(context, projectId, parsed)
                            saved = parsed
                            link = ""
                        }
                    }) { Text("Save") }
                },
            )
        }
    }
}

@Composable
private fun IdeaCard(idea: MusicIdea, selectedTerm: String?, onTerm: (String) -> Unit) {
    val c = CineMind.colors
    val shape = RoundedCornerShape(Radius.l)
    Column(
        modifier = Modifier
            .padding(top = Spacing.s)
            .fillMaxWidth()
            .clip(shape)
            .background(c.surfaceSunken)
            .border(1.dp, c.hairline, shape)
            .padding(Spacing.m),
    ) {
        Text(idea.mood, style = MaterialTheme.typography.titleSmall, color = c.textPrimary)
        Text("${idea.genres.joinToString(" · ")} · ${idea.bpmRange.first}–${idea.bpmRange.last} BPM", style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
        Text(idea.reason, style = MaterialTheme.typography.bodySmall, color = c.textTertiary)
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs), modifier = Modifier.padding(top = Spacing.xs).horizontalScroll(rememberScrollState())) {
            idea.searchTerms.forEach { t ->
                val sel = t == selectedTerm
                val pill = RoundedCornerShape(Radius.xl)
                Text(
                    t,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (sel) c.onAccent else c.textPrimary,
                    modifier = Modifier
                        .clip(pill)
                        .background(if (sel) c.accent else c.surface)
                        .border(1.dp, if (sel) c.accent else c.hairline, pill)
                        .clickable { onTerm(t) }
                        .padding(horizontal = Spacing.m, vertical = Spacing.xs),
                )
            }
        }
    }
}
