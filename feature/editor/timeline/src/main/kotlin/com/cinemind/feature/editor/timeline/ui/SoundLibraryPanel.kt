package com.cinemind.feature.editor.timeline.ui

import android.media.AudioAttributes
import android.media.MediaPlayer
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.cinemind.core.domain.audio.Freesound
import com.cinemind.core.domain.audio.FreesoundSound
import com.cinemind.core.domain.audio.SoundLicense
import com.cinemind.core.domain.model.MediaAsset
import com.cinemind.core.ui.theme.CineMind
import com.cinemind.core.ui.theme.Radius
import com.cinemind.core.ui.theme.Spacing
import com.cinemind.feature.editor.timeline.audio.PackSound
import com.cinemind.feature.editor.timeline.audio.SoundLibrary
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

data class FreesoundUi(
    val query: String = "",
    val results: List<FreesoundSound> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val page: Int = 1,
    val hasMore: Boolean = false,
    val searched: Boolean = false,
)

@HiltViewModel
class SoundLibraryViewModel @Inject constructor(val library: SoundLibrary) : ViewModel() {
    val hasKey: StateFlow<Boolean> = library.hasKey
    private val _ui = MutableStateFlow(FreesoundUi())
    val ui: StateFlow<FreesoundUi> = _ui.asStateFlow()
    private var job: Job? = null

    /** Null when saved, else what's wrong. */
    fun saveKey(key: String): String? = runCatching { library.saveKey(key) }.exceptionOrNull()?.message

    fun removeKey() {
        library.removeKey()
        _ui.value = FreesoundUi()
    }

    fun search(query: String, more: Boolean = false) {
        val q = query.trim()
        if (q.isEmpty()) return
        val page = if (more) _ui.value.page + 1 else 1
        job?.cancel()
        _ui.value = _ui.value.copy(query = q, loading = true, error = null, results = if (more) _ui.value.results else emptyList())
        job = viewModelScope.launch {
            try {
                val r = library.search(q, page)
                _ui.value = _ui.value.copy(results = (if (more) _ui.value.results else emptyList()) + r.sounds, loading = false, page = page, hasMore = r.hasMore, searched = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.value = _ui.value.copy(loading = false, error = e.message ?: "Search failed", searched = true)
            }
        }
    }
}

/** Plays a short sound from a link or the bundled pack (only one at a time). */
private class Previewer {
    private var player: MediaPlayer? = null

    private fun fresh(): MediaPlayer {
        stop()
        return MediaPlayer().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            setOnPreparedListener { it.start() }
            setOnErrorListener { _, _, _ -> true }
            player = this
        }
    }

    fun play(url: String) = runCatching { fresh().apply { setDataSource(url); prepareAsync() } }

    fun play(library: SoundLibrary, s: PackSound) = runCatching {
        library.openPack(s).use { fd -> fresh().apply { setDataSource(fd.fileDescriptor, fd.startOffset, fd.length); prepareAsync() } }
    }

    fun stop() {
        player?.let { runCatching { it.stop() }; it.release() }
        player = null
    }
}

/**
 * The bundled CC0 pack and Freesound search, inside the Sound effects sheet. [onAdd] gets a loader
 * that copies the chosen sound into the app and returns it as a media asset.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LibrarySounds(onAdd: (suspend () -> MediaAsset) -> Unit, viewModel: SoundLibraryViewModel = hiltViewModel()) {
    val c = CineMind.colors
    val previewer = remember { Previewer() }
    DisposableEffect(Unit) { onDispose { previewer.stop() } }
    val pack = viewModel.library.pack

    if (pack.isNotEmpty()) {
        pack.groupBy { it.category }.forEach { (category, items) ->
            Text("$category · CC0 pack", style = MaterialTheme.typography.labelLarge, color = c.textSecondary, modifier = Modifier.padding(top = Spacing.m, bottom = Spacing.xs))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                items.forEach { s ->
                    SoundChip(s.label, onPlay = { previewer.play(viewModel.library, s) }, onAdd = { previewer.stop(); onAdd { viewModel.library.packAsset(s) } })
                }
            }
        }
    }

    Text("Freesound library", style = MaterialTheme.typography.titleSmall, color = c.textPrimary, modifier = Modifier.padding(top = Spacing.l))
    val hasKey by viewModel.hasKey.collectAsStateWithLifecycle()
    if (!hasKey) {
        FreesoundKeySetup(viewModel)
        return
    }
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf(ui.query) }
    OutlinedTextField(
        value = query,
        onValueChange = { query = it },
        singleLine = true,
        placeholder = { Text("Search sounds (whoosh, boom, laugh…)") },
        trailingIcon = { IconButton(onClick = { viewModel.search(query) }) { Icon(Icons.Default.Search, contentDescription = "Search Freesound") } },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { viewModel.search(query) }),
        modifier = Modifier.fillMaxWidth().padding(top = Spacing.xs),
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs), verticalArrangement = Arrangement.spacedBy(Spacing.xs), modifier = Modifier.padding(top = Spacing.xs)) {
        Freesound.QUICK.take(10).forEach { q ->
            com.cinemind.core.ui.components.Pill(text = q, selected = ui.query == q, onClick = { query = q; viewModel.search(q) })
        }
    }
    ui.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.danger, modifier = Modifier.padding(top = Spacing.s)) }
    if (ui.searched && !ui.loading && ui.results.isEmpty() && ui.error == null) {
        Text("Nothing found. Try a simpler word.", style = MaterialTheme.typography.bodySmall, color = c.textTertiary, modifier = Modifier.padding(top = Spacing.s))
    }
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs), modifier = Modifier.padding(top = Spacing.s)) {
        ui.results.forEach { s ->
            FreesoundRow(s, onPlay = { previewer.play(s.previewUrl) }, onAdd = { previewer.stop(); onAdd { viewModel.library.download(s) } })
        }
    }
    if (ui.loading) {
        CircularProgressIndicator(strokeWidth = 2.dp, color = c.accent, modifier = Modifier.padding(top = Spacing.s).size(20.dp))
    } else if (ui.hasMore) {
        TextButton(onClick = { viewModel.search(ui.query, more = true) }) { Text("More results") }
    }
    Text(
        "From freesound.org: CC0 and CC BY sounds only. CC BY sounds need credit where you post the video; CineMind keeps the list in Settings → About → Sound credits.",
        style = MaterialTheme.typography.bodySmall,
        color = c.textTertiary,
        modifier = Modifier.padding(top = Spacing.s),
    )
    TextButton(onClick = viewModel::removeKey) { Text("Remove Freesound key", color = c.textSecondary) }
}

@Composable
private fun FreesoundKeySetup(viewModel: SoundLibraryViewModel) {
    val c = CineMind.colors
    val uri = LocalUriHandler.current
    var key by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    Text(
        "Search thousands of free sound effects. Freesound needs a free API key: sign up at freesound.org, " +
            "create a key (any name, e.g. “CineMind on my phone”), and paste the “Client secret/Api key” here. It stays encrypted on this phone.",
        style = MaterialTheme.typography.bodySmall,
        color = c.textSecondary,
        modifier = Modifier.padding(top = Spacing.xs),
    )
    TextButton(onClick = { uri.openUri(Freesound.KEY_PAGE) }) { Text("Get a free key at freesound.org") }
    OutlinedTextField(
        value = key,
        onValueChange = { key = it; error = null },
        singleLine = true,
        label = { Text("Freesound API key") },
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
    )
    error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.danger) }
    Spacer(Modifier.height(Spacing.s))
    OutlinedButton(onClick = { error = viewModel.saveKey(key); if (error == null) key = "" }, enabled = key.isNotBlank()) { Text("Save key") }
}

@Composable
private fun SoundChip(label: String, onPlay: () -> Unit, onAdd: () -> Unit) {
    val c = CineMind.colors
    val shape = RoundedCornerShape(Radius.m)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.clip(shape).background(c.surfaceSunken).border(1.dp, c.hairline, shape),
    ) {
        IconButton(onClick = onPlay) { Icon(Icons.Default.PlayArrow, contentDescription = "Play $label", tint = c.accent) }
        Text(label, style = MaterialTheme.typography.bodyMedium, color = c.textPrimary)
        IconButton(onClick = onAdd) { Icon(Icons.Default.Add, contentDescription = "Add $label", tint = c.textPrimary) }
    }
}

@Composable
private fun FreesoundRow(s: FreesoundSound, onPlay: () -> Unit, onAdd: () -> Unit) {
    val c = CineMind.colors
    val shape = RoundedCornerShape(Radius.m)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clip(shape).background(c.surfaceSunken).border(1.dp, c.hairline, shape),
    ) {
        IconButton(onClick = onPlay) { Icon(Icons.Default.PlayArrow, contentDescription = "Play ${s.name}", tint = c.accent) }
        Column(Modifier.weight(1f)) {
            Text(s.name, style = MaterialTheme.typography.bodyMedium, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                String.format(Locale.ROOT, "%.1f s · %s · %s", s.seconds, s.username, if (s.license == SoundLicense.CC0) "CC0" else "CC BY"),
                style = MaterialTheme.typography.labelSmall,
                color = if (s.license.needsCredit) c.textSecondary else c.textTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(Spacing.xs))
        IconButton(onClick = onAdd) { Icon(Icons.Default.Add, contentDescription = "Add ${s.name}", tint = c.textPrimary) }
    }
}
