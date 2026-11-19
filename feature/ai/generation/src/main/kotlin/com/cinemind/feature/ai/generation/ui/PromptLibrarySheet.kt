package com.cinemind.feature.ai.generation.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cinemind.core.domain.aiedit.Genre
import com.cinemind.core.domain.aiedit.PromptLibrary
import com.cinemind.core.domain.aiedit.PromptTemplate
import com.cinemind.core.ui.components.CineMindSheet
import com.cinemind.core.ui.components.GlassCard
import com.cinemind.core.ui.theme.CineMind
import com.cinemind.core.ui.theme.Spacing

private enum class LibraryTab(val label: String) { ALL("All"), FAVOURITES("Favourites"), RECENT("Recent"), MINE("Mine") }

/** Browse and search ready-made briefs by genre; favourites, recents and your own saved ones. */
@Composable
internal fun PromptLibrarySheet(
    library: EditBriefViewModel.LibraryUi,
    onUse: (PromptTemplate) -> Unit,
    onFavourite: (String) -> Unit,
    onSaveCurrent: (String, Genre) -> Unit,
    onDelete: (String) -> Unit,
    canSaveCurrent: Boolean,
    onDismiss: () -> Unit,
) {
    val c = CineMind.colors
    var query by remember { mutableStateOf("") }
    var genre by remember { mutableStateOf<Genre?>(null) }
    var tab by remember { mutableStateOf(LibraryTab.ALL) }
    var saveTitle by remember { mutableStateOf("") }

    val all = PromptLibrary.search(query, genre, library.custom)
    val shown = when (tab) {
        LibraryTab.ALL -> all
        LibraryTab.FAVOURITES -> all.filter { it.id in library.favourites }
        LibraryTab.RECENT -> library.recents.mapNotNull { id -> all.firstOrNull { it.id == id } }
        LibraryTab.MINE -> all.filter { it.custom }
    }

    CineMindSheet(onDismiss = onDismiss, title = "Prompt library") {
        Column(Modifier.heightIn(max = 600.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search: gym, sunset, tutorial…") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                LibraryTab.entries.forEach { t -> FilterChip(selected = tab == t, onClick = { tab = t }, label = { Text(t.label) }) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                FilterChip(selected = genre == null, onClick = { genre = null }, label = { Text("Every genre") })
                Genre.entries.forEach { g -> FilterChip(selected = genre == g, onClick = { genre = if (genre == g) null else g }, label = { Text(g.label) }) }
            }
            if (shown.isEmpty()) {
                Text(
                    when (tab) {
                        LibraryTab.FAVOURITES -> "Tap ☆ on a prompt to keep it here."
                        LibraryTab.RECENT -> "Prompts you use appear here."
                        LibraryTab.MINE -> "Save your own brief below to reuse it."
                        LibraryTab.ALL -> "Nothing matches. Try another word."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = c.textTertiary,
                )
            }
            shown.forEach { t ->
                GlassCard(modifier = Modifier.fillMaxWidth().clickable { onUse(t) }) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(t.title, style = MaterialTheme.typography.titleSmall, color = c.textPrimary)
                                Text(
                                    t.genre.label + " · " + t.style.label + " · " + t.pacing.label + (t.seconds?.let { " · $it s" } ?: ""),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = c.textTertiary,
                                )
                            }
                            IconButton(onClick = { onFavourite(t.id) }) {
                                Icon(
                                    if (t.id in library.favourites) Icons.Filled.Star else Icons.Outlined.StarOutline,
                                    contentDescription = if (t.id in library.favourites) "Remove from favourites" else "Add to favourites",
                                    tint = if (t.id in library.favourites) c.highlight else c.textTertiary,
                                )
                            }
                        }
                        Text(t.prompt, style = MaterialTheme.typography.bodySmall, color = c.textSecondary, maxLines = 3)
                        Row {
                            TextButton(onClick = { onUse(t) }) { Text("Use") }
                            if (t.custom) TextButton(onClick = { onDelete(t.id) }) { Text("Delete", color = c.danger) }
                        }
                    }
                }
            }
            if (canSaveCurrent) {
                Text("Save your current brief", style = MaterialTheme.typography.titleSmall, color = c.textPrimary, modifier = Modifier.padding(top = Spacing.s))
                OutlinedTextField(
                    value = saveTitle,
                    onValueChange = { saveTitle = it.take(60) },
                    placeholder = { Text("Name it, e.g. My travel style") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(onClick = { onSaveCurrent(saveTitle, genre ?: Genre.SOCIAL); saveTitle = ""; tab = LibraryTab.MINE }) { Text("Save to my prompts") }
            }
        }
    }
}
