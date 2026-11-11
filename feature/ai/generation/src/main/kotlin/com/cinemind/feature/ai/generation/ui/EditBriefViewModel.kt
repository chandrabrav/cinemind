package com.cinemind.feature.ai.generation.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cinemind.core.common.CineMindLogger
import com.cinemind.core.domain.model.EditBrief
import com.cinemind.core.domain.model.EditBriefRules
import com.cinemind.core.domain.model.EditStyle
import com.cinemind.core.domain.model.Pacing
import com.cinemind.core.domain.model.Project
import com.cinemind.core.domain.model.ProjectFormat
import com.cinemind.core.domain.repository.EditBriefRepository
import com.cinemind.core.domain.repository.ProjectRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class EditBriefUiState(
    val loaded: Boolean = false,
    val brief: EditBrief? = null,
    val saved: Boolean = true,
)

@OptIn(FlowPreview::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class EditBriefViewModel @Inject constructor(
    private val briefRepository: EditBriefRepository,
    private val projectRepository: ProjectRepository,
) : ViewModel() {

    private val projectId = MutableStateFlow<String?>(null)

    val project: StateFlow<Project?> = projectId.filterNotNull()
        .flatMapLatest { projectRepository.observeProject(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _state = MutableStateFlow(EditBriefUiState())
    val state: StateFlow<EditBriefUiState> = _state.asStateFlow()

    fun load(id: String) {
        if (projectId.value == id) return
        projectId.value = id
        viewModelScope.launch {
            // Edits are made on a local copy; the stored brief is read once so typing isn't
            // overwritten by database echoes.
            val stored = briefRepository.observeBrief(id).first()
            _state.value = EditBriefUiState(loaded = true, brief = stored, saved = true)
            autosave()
        }
    }

    /** Persists the brief 600 ms after the user stops changing it. */
    private suspend fun autosave() {
        _state
            .drop(1)
            .debounce(600)
            .distinctUntilChanged { a, b -> a.brief == b.brief }
            .collect { s ->
                val brief = s.brief ?: return@collect
                if (s.saved) return@collect
                runCatching { briefRepository.saveBrief(brief) }
                    .onSuccess { _state.update { cur -> if (cur.brief == brief) cur.copy(saved = true) else cur } }
                    .onFailure { CineMindLogger.e(it, "Saving brief failed") }
            }
    }

    private fun edit(change: (EditBrief) -> EditBrief) {
        _state.update { s -> s.brief?.let { s.copy(brief = change(it), saved = false) } ?: s }
    }

    fun setPrompt(text: String) = edit { it.copy(prompt = EditBriefRules.clampPrompt(text)) }

    fun addSuggestion(text: String) = edit { it.copy(prompt = EditBriefRules.appendSuggestion(it.prompt, text)) }

    fun setStyle(style: EditStyle) = edit { it.copy(style = style) }

    fun setTargetDuration(seconds: Int?) = edit { it.copy(targetDurationSec = seconds) }

    fun setPacing(pacing: Pacing) = edit { it.copy(pacing = pacing) }

    /** Format belongs to the project (it changes the preview frame and future export), so it is saved at once. */
    fun setFormat(format: ProjectFormat) {
        val id = projectId.value ?: return
        viewModelScope.launch {
            runCatching { projectRepository.setProjectFormat(id, format) }
                .onFailure { CineMindLogger.e(it, "Changing format failed") }
        }
    }

    /**
     * Saves pending edits immediately (leaving the screen / app going to background).
     * Runs as NonCancellable so the write completes even while the ViewModel is being cleared.
     */
    fun flush() {
        val s = _state.value
        val brief = s.brief ?: return
        if (s.saved) return
        viewModelScope.launch(NonCancellable) {
            runCatching { briefRepository.saveBrief(brief) }
                .onSuccess { _state.update { cur -> if (cur.brief == brief) cur.copy(saved = true) else cur } }
                .onFailure { CineMindLogger.e(it, "Saving brief failed") }
        }
    }

    override fun onCleared() {
        flush()
        super.onCleared()
    }
}
