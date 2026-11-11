package com.cinemind.feature.media.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cinemind.core.common.CineMindLogger
import com.cinemind.core.domain.model.MediaAsset
import com.cinemind.core.domain.model.ProjectFactory
import com.cinemind.core.domain.repository.MediaAssetRepository
import com.cinemind.core.domain.repository.ProjectRepository
import com.cinemind.feature.media.importer.MediaImporter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

data class MediaLibraryUiState(
    val assets: List<MediaAsset> = emptyList(),
    /** Selected asset ids in the order the user tapped them; this becomes clip order. */
    val selection: List<String> = emptyList(),
    val importing: Boolean = false,
    val creatingProject: Boolean = false,
)

sealed interface MediaLibraryEvent {
    data class ProjectCreated(val projectId: String) : MediaLibraryEvent
    data class Message(val text: String) : MediaLibraryEvent
}

@HiltViewModel
class MediaLibraryViewModel @Inject constructor(
    mediaAssetRepository: MediaAssetRepository,
    private val projectRepository: ProjectRepository,
    private val importer: MediaImporter,
) : ViewModel() {

    private val local = MutableStateFlow(MediaLibraryUiState())
    private val events = Channel<MediaLibraryEvent>(Channel.BUFFERED)
    val eventFlow: Flow<MediaLibraryEvent> = events.receiveAsFlow()

    val uiState: StateFlow<MediaLibraryUiState> =
        combine(mediaAssetRepository.getAllAssets(), local) { all, state ->
            // Music files are imported from the editor and never become video clips.
            val assets = all.filterNot { it.isAudio }
            val ids = assets.mapTo(HashSet()) { it.id }
            state.copy(assets = assets, selection = state.selection.filter { it in ids })
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MediaLibraryUiState())

    fun importMedia(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            local.update { it.copy(importing = true) }
            val imported = mutableListOf<MediaAsset>()
            var failed = 0
            for (uri in uris) {
                runCatching { importer.import(uri) }
                    .onSuccess { imported += it }
                    .onFailure {
                        failed++
                        CineMindLogger.w("Import failed: ${it.message}")
                    }
            }
            // Newly picked items are auto-selected, in picker order, ready to become a project.
            local.update { state ->
                state.copy(
                    importing = false,
                    selection = (state.selection + imported.map { it.id }).distinct(),
                )
            }
            val msg = when {
                failed == 0 -> "Imported ${imported.size} item(s)"
                imported.isEmpty() -> "Could not import $failed item(s)"
                else -> "Imported ${imported.size}, $failed could not be read"
            }
            events.send(MediaLibraryEvent.Message(msg))
        }
    }

    fun toggleSelection(assetId: String) {
        local.update { state ->
            val sel = state.selection
            state.copy(selection = if (assetId in sel) sel - assetId else sel + assetId)
        }
    }

    fun clearSelection() = local.update { it.copy(selection = emptyList()) }

    fun createProject() {
        val state = uiState.value
        if (state.creatingProject || state.selection.isEmpty()) return
        val byId = state.assets.associateBy { it.id }
        val chosen = state.selection.mapNotNull { byId[it] }
        if (chosen.isEmpty()) return

        viewModelScope.launch {
            local.update { it.copy(creatingProject = true) }
            runCatching {
                val name = "Project " + SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(Date())
                projectRepository.createProjectWithTimeline(ProjectFactory.fromAssets(name, chosen))
            }.onSuccess { projectId ->
                local.update { it.copy(creatingProject = false, selection = emptyList()) }
                events.send(MediaLibraryEvent.ProjectCreated(projectId))
            }.onFailure {
                CineMindLogger.e(it, "Project creation failed")
                local.update { s -> s.copy(creatingProject = false) }
                events.send(MediaLibraryEvent.Message("Could not create project"))
            }
        }
    }
}
