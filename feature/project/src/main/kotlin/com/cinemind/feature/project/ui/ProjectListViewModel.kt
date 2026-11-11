package com.cinemind.feature.project.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cinemind.core.common.CineMindLogger
import com.cinemind.core.domain.model.ProjectNames
import com.cinemind.core.domain.model.ProjectSummary
import com.cinemind.core.domain.repository.ProjectRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** null = still loading (so the UI doesn't flash the empty state on start). */
data class ProjectListUiState(val projects: List<ProjectSummary>? = null)

@HiltViewModel
class ProjectListViewModel @Inject constructor(
    private val repository: ProjectRepository,
) : ViewModel() {

    val uiState: StateFlow<ProjectListUiState> = repository.getProjectSummaries()
        .map { ProjectListUiState(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProjectListUiState())

    private val messages = Channel<String>(Channel.BUFFERED)
    val messageFlow: Flow<String> = messages.receiveAsFlow()

    fun rename(projectId: String, input: String) {
        val name = ProjectNames.sanitize(input)
        if (name == null) {
            messages.trySend("Name can't be empty")
            return
        }
        viewModelScope.launch {
            runCatching { repository.renameProject(projectId, name) }
                .onFailure { report(it, "Could not rename project") }
        }
    }

    fun duplicate(summary: ProjectSummary) {
        viewModelScope.launch {
            runCatching { repository.duplicateProject(summary.project.id, ProjectNames.copyOf(summary.project.name)) }
                .onSuccess { if (it != null) messages.send("Duplicated “${summary.project.name}”") }
                .onFailure { report(it, "Could not duplicate project") }
        }
    }

    /** Deletes the project, its tracks and clips. Imported media and the original files are kept. */
    fun delete(summary: ProjectSummary) {
        viewModelScope.launch {
            runCatching { repository.deleteProject(summary.project.id) }
                .onSuccess { messages.send("Deleted “${summary.project.name}”") }
                .onFailure { report(it, "Could not delete project") }
        }
    }

    private suspend fun report(error: Throwable, message: String) {
        CineMindLogger.e(error, message)
        messages.send(message)
    }
}
