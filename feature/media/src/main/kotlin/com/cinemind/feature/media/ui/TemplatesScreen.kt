package com.cinemind.feature.media.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.cinemind.core.common.CineMindLogger
import com.cinemind.core.domain.repository.ProjectRepository
import com.cinemind.core.domain.repository.TextLayerRepository
import com.cinemind.core.domain.templates.Template
import com.cinemind.core.domain.templates.Templates
import com.cinemind.core.ui.components.GlassCard
import com.cinemind.core.ui.components.GradientButton
import com.cinemind.core.ui.theme.CineMind
import com.cinemind.core.ui.theme.Radius
import com.cinemind.core.ui.theme.Spacing
import com.cinemind.feature.media.importer.MediaImporter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class TemplatesUi(val working: String? = null, val error: String? = null)

@HiltViewModel
class TemplatesViewModel @Inject constructor(
    private val importer: MediaImporter,
    private val projectRepository: ProjectRepository,
    private val textRepository: TextLayerRepository,
) : ViewModel() {
    private val _ui = MutableStateFlow(TemplatesUi())
    val ui: StateFlow<TemplatesUi> = _ui.asStateFlow()
    private val created = Channel<String>(Channel.BUFFERED)
    val createdFlow = created.receiveAsFlow()

    /** Imports the picked media and builds the project from [template]. */
    fun use(template: Template, uris: List<Uri>) {
        if (uris.isEmpty() || _ui.value.working != null) return
        _ui.value = TemplatesUi(working = template.id)
        viewModelScope.launch {
            runCatching {
                val assets = uris.mapNotNull { runCatching { importer.import(it) }.getOrNull() }.filter { it.isVideo || it.isImage }
                require(assets.isNotEmpty()) { "None of the picked files could be read" }
                val (timeline, texts) = Templates.apply(template, assets, template.name)
                val id = projectRepository.createProjectWithTimeline(timeline)
                if (texts.isNotEmpty()) textRepository.save(id, texts.map { it.copy(projectId = id) })
                id
            }.onSuccess { id ->
                _ui.value = TemplatesUi()
                created.send(id)
            }.onFailure {
                CineMindLogger.e(it, "Template failed")
                _ui.value = TemplatesUi(error = it.message ?: "Couldn't use the template")
            }
        }
    }
}

/**
 * Templates: ready-made edits (CineMind's own). Pick one, choose photos and videos for its shots
 * (in order), and get a normal editable project with cuts, transitions, look and titles.
 */
@Composable
fun TemplatesScreen(onProjectCreated: (String) -> Unit, viewModel: TemplatesViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) { viewModel.createdFlow.collect { onProjectCreated(it) } }
    var chosen by remember { mutableStateOf<Template?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(maxItems = 20)) { uris ->
        chosen?.let { t -> viewModel.use(t, uris) }
    }
    val c = CineMind.colors
    LazyColumn(contentPadding = PaddingValues(bottom = Spacing.xxl), verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
        item {
            Column(Modifier.padding(horizontal = Spacing.gutter)) {
                Text("Templates", style = MaterialTheme.typography.headlineSmall, color = c.textPrimary)
                Text(
                    "Pick a template, then choose your photos and videos in the order you want them. Everything stays editable.",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.textSecondary,
                )
                ui.error?.let { Text(it, color = c.danger, style = MaterialTheme.typography.bodySmall) }
            }
        }
        items(Templates.all, key = { it.id }) { t ->
            GlassCard(modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter)) {
                Text(t.name, style = MaterialTheme.typography.titleMedium, color = c.textPrimary)
                Text("${t.category} · ${t.slots.size} shots · ${t.durationUs / 1_000_000}s · ${t.description}", style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
                Spacer(Modifier.height(Spacing.s))
                ShotBar(t)
                Spacer(Modifier.height(Spacing.xs))
                Text("Shots: " + t.slots.joinToString(" · ") { it.hint }, style = MaterialTheme.typography.labelSmall, color = c.textTertiary)
                Spacer(Modifier.height(Spacing.s))
                if (ui.working == t.id) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.width(18.dp).height(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(Spacing.s))
                        Text("Building your edit…", color = c.textSecondary)
                    }
                } else {
                    GradientButton(
                        "Use with ${t.slots.size} photos or videos",
                        onClick = {
                            chosen = t
                            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                        },
                        enabled = ui.working == null,
                        icon = Icons.Default.AddPhotoAlternate,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

/** The template's rhythm: one bar per shot, as wide as it lasts. */
@Composable
private fun ShotBar(t: Template) {
    val c = CineMind.colors
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.fillMaxWidth().height(14.dp)) {
        t.slots.forEachIndexed { i, s ->
            Box(
                Modifier
                    .weight(s.lengthUs.toFloat())
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(Radius.xs))
                    .background(if (i % 2 == 0) c.accent else c.trackVideo),
            )
        }
    }
}
