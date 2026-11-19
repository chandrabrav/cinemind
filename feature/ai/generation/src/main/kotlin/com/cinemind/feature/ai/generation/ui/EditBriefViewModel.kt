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
import com.cinemind.feature.ai.generation.smartedit.SmartEditException
import com.cinemind.feature.ai.generation.smartedit.SmartEditProgress
import com.cinemind.feature.ai.generation.smartedit.SmartEditResult
import com.cinemind.feature.ai.generation.smartedit.SmartEditor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
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

/** Smart-edit run state shown on the brief screen. */
sealed interface SmartEditUi {
    data object Idle : SmartEditUi
    data class Working(val message: String, val progress: Float?) : SmartEditUi
    data class Failed(val message: String) : SmartEditUi
}

@OptIn(FlowPreview::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class EditBriefViewModel @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context,
    private val briefRepository: EditBriefRepository,
    private val projectRepository: ProjectRepository,
    private val smartEditor: SmartEditor,
) : ViewModel() {

    private val _smartEdit = MutableStateFlow<SmartEditUi>(SmartEditUi.Idle)
    val smartEdit: StateFlow<SmartEditUi> = _smartEdit.asStateFlow()

    /** Emits the new project's id when a smart edit is ready, so the screen can open it. */
    private val created = Channel<SmartEditResult>(Channel.BUFFERED)
    val createdFlow: Flow<SmartEditResult> = created.receiveAsFlow()
    private var smartJob: Job? = null

    fun createSmartEdit() {
        val id = projectId.value ?: return
        if (_smartEdit.value is SmartEditUi.Working) return
        smartJob = viewModelScope.launch {
            // Make sure the latest brief text is stored before it's read.
            _state.value.brief?.let { if (!_state.value.saved) briefRepository.saveBrief(it) }
            _smartEdit.value = SmartEditUi.Working("Reading your brief…", null)
            try {
                // Kept alive by a foreground service, so it finishes even if the user switches apps.
                val result = com.cinemind.core.common.BackgroundWork.run(appContext, "Making your Smart edit") { smartEditor.create(id) { p ->
                    _smartEdit.value = when (p) {
                        is SmartEditProgress.Analyzing ->
                            SmartEditUi.Working("Analysing clip ${p.index} of ${p.count}…", (p.index - 1f) / p.count)
                        SmartEditProgress.LoadingModel -> SmartEditUi.Working("Loading the on-device AI model…", null)
                        SmartEditProgress.ReadingBrief -> SmartEditUi.Working("AI is reading your brief…", null)
                        is SmartEditProgress.Looking ->
                            SmartEditUi.Working("AI is looking at moment ${p.index} of ${p.count}…", (p.index - 1f) / p.count)
                        SmartEditProgress.Planning -> SmartEditUi.Working("Choosing shots and planning the edit…", null)
                        is SmartEditProgress.AskingCloud -> SmartEditUi.Working("${p.engine} is planning the edit…", null)
                        SmartEditProgress.PlanningOnPhone -> SmartEditUi.Working("Thinking the edit through on your phone… this can take a few minutes", null)
                        SmartEditProgress.Building -> SmartEditUi.Working("Applying colour, text, captions and sound…", null)
                    }
                } }
                _smartEdit.value = SmartEditUi.Idle
                created.send(result)
            } catch (e: CancellationException) {
                _smartEdit.value = SmartEditUi.Idle
                throw e
            } catch (e: SmartEditException) {
                _smartEdit.value = SmartEditUi.Failed(e.message ?: "Smart edit failed")
            } catch (e: Exception) {
                CineMindLogger.e(e, "Smart edit failed")
                _smartEdit.value = SmartEditUi.Failed("Smart edit failed: ${e.message}")
            }
        }
    }

    fun cancelSmartEdit() {
        smartJob?.cancel()
        _smartEdit.value = SmartEditUi.Idle
    }

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
