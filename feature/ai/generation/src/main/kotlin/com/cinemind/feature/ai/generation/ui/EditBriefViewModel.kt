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
import kotlinx.coroutines.cancelAndJoin
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
    data class Working(val message: String, val progress: Float?, val elapsedSeconds: Int = 0) : SmartEditUi
    data class Failed(val message: String) : SmartEditUi
}

@OptIn(FlowPreview::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class EditBriefViewModel @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context,
    private val briefRepository: EditBriefRepository,
    private val projectRepository: ProjectRepository,
    private val smartEditor: SmartEditor,
    private val referenceAnalyzer: com.cinemind.feature.ai.generation.smartedit.ReferenceAnalyzer,
    val voice: com.cinemind.feature.ai.generation.smartedit.VoicePrompt,
    private val importer: com.cinemind.feature.media.importer.MediaImporter,
    private val assetRepository: com.cinemind.core.domain.repository.MediaAssetRepository,
) : ViewModel() {

    // ---- Music for the edit (a song the user owns, picked from the phone; the smart edit sets its level, fades and start) ----

    private val _music = MutableStateFlow<String?>(null)
    /** Name of the song this edit will use, or null. */
    val music: StateFlow<String?> = _music.asStateFlow()

    fun setMusic(uri: android.net.Uri) {
        val id = projectId.value ?: return
        viewModelScope.launch {
            val asset = runCatching { importer.import(uri) }.onFailure { if (it is CancellationException) throw it }.getOrNull()
            if (asset == null || !asset.isAudioFile) {
                android.widget.Toast.makeText(appContext, "That file can't be used as music", android.widget.Toast.LENGTH_SHORT).show()
                return@launch
            }
            projectRepository.saveMusic(id, com.cinemind.core.domain.audio.MusicSettings(assetId = asset.id, volume = 0.7f))
            _music.value = asset.displayName
        }
    }

    fun removeMusic() {
        val id = projectId.value ?: return
        viewModelScope.launch {
            projectRepository.saveMusic(id, null)
            _music.value = null
        }
    }

    // ---- Sample videos and reference images (their measured style steers pace and look) ----

    private val _references = MutableStateFlow<List<com.cinemind.core.domain.aiedit.StyleReference>>(emptyList())
    val references: StateFlow<List<com.cinemind.core.domain.aiedit.StyleReference>> = _references.asStateFlow()

    /** What the reference analysis is doing right now, or null. */
    private val _referenceWork = MutableStateFlow<String?>(null)
    val referenceWork: StateFlow<String?> = _referenceWork.asStateFlow()

    fun addReference(uri: android.net.Uri, kind: com.cinemind.core.domain.aiedit.ReferenceKind) {
        val id = projectId.value ?: return
        if (_references.value.size >= com.cinemind.core.domain.aiedit.StyleReferences.MAX || _referenceWork.value != null) return
        viewModelScope.launch {
            runCatching { referenceAnalyzer.analyze(uri, kind) { step -> _referenceWork.value = step } }
                .onSuccess { ref ->
                    val all = _references.value + ref
                    _references.value = all
                    com.cinemind.feature.ai.generation.smartedit.ReferenceStore.save(appContext, id, all)
                    // A sample video sets the pacing to match its cutting (still changeable below).
                    com.cinemind.core.domain.aiedit.StyleReferences.pacing(all)?.let { setPacing(it) }
                }
                .onFailure { if (it is CancellationException) throw it; CineMindLogger.w("Reference failed: ${it.message}") }
            _referenceWork.value = null
        }
    }

    fun removeReference(index: Int) {
        val id = projectId.value ?: return
        val all = _references.value.filterIndexed { i, _ -> i != index }
        _references.value = all
        com.cinemind.feature.ai.generation.smartedit.ReferenceStore.save(appContext, id, all)
    }

    // ---- Speak instead of typing ----

    sealed interface VoiceUi {
        data object Idle : VoiceUi
        data object Listening : VoiceUi
        data object Transcribing : VoiceUi
        data class Failed(val message: String) : VoiceUi
    }

    private val _voiceUi = MutableStateFlow<VoiceUi>(VoiceUi.Idle)
    val voiceUi: StateFlow<VoiceUi> = _voiceUi.asStateFlow()

    private var voiceTarget: (String) -> Unit = {}

    fun startListening(onText: (String) -> Unit) {
        voiceTarget = onText
        _voiceUi.value = if (voice.start()) VoiceUi.Listening else VoiceUi.Failed("The microphone isn't available right now.")
    }

    fun cancelListening() {
        voice.cancel()
        _voiceUi.value = VoiceUi.Idle
    }

    /** Stops listening; the words go to the box that started it (the prompt, or "change this edit"). */
    fun finishListening() {
        val onText = voiceTarget
        _voiceUi.value = VoiceUi.Transcribing
        viewModelScope.launch {
            val text = voice.finish()
            if (text.isBlank()) _voiceUi.value = VoiceUi.Failed("Didn't catch that. Try again a little closer to the phone.")
            else { _voiceUi.value = VoiceUi.Idle; onText(text) }
        }
    }

    fun dismissVoiceError() { _voiceUi.value = VoiceUi.Idle }

    fun voiceUnavailable() { _voiceUi.value = VoiceUi.Failed("Voice input isn't available on this phone. Download Whisper under AI models → On this phone to speak your prompt.") }

    /** Adds spoken or recognised words to the prompt. */
    fun appendToPrompt(text: String) = edit { b -> b.copy(prompt = EditBriefRules.clampPrompt(if (b.prompt.isBlank()) text.trim() else b.prompt.trimEnd() + " " + text.trim())) }

    private val _smartEdit = MutableStateFlow<SmartEditUi>(SmartEditUi.Idle)
    val smartEdit: StateFlow<SmartEditUi> = _smartEdit.asStateFlow()

    /** Emits the new project's id when a smart edit is ready, so the screen can open it. */
    private val created = Channel<SmartEditResult>(Channel.BUFFERED)
    val createdFlow: Flow<SmartEditResult> = created.receiveAsFlow()
    private var smartJob: Job? = null

    fun createSmartEdit() {
        val id = projectId.value ?: return
        run(id, null)
    }

    /**
     * A new version of this AI edit with [text]'s changes ("make it 30 s, keep the music"), made
     * from the original footage; this version stays as it is.
     */
    fun revise(text: String) {
        val id = projectId.value ?: return
        val source = lineage.getString(id, null) ?: return
        val brief = _state.value.brief ?: return
        if (text.isBlank()) return
        val seconds = (project.value?.durationUs ?: 0L) / 1e6
        val revision = com.cinemind.core.domain.aiedit.RevisionReader.read(text, brief, seconds)
        run(source, SmartEditor.RevisionRun(revision, previousId = id))
    }

    /** True when this project is an AI edit that can be revised (its original is known). */
    val canRevise: Boolean get() = projectId.value?.let { lineage.contains(it) } == true

    /** Which original project each AI edit (and each revision of it) was made from. */
    private val lineage by lazy { appContext.getSharedPreferences("smart_edit_lineage", android.content.Context.MODE_PRIVATE) }

    // ---- Overall progress: each step owns a share of 0..100 %, steps that can tell report inside
    // their share, and steps that can't (model thinking) creep forward so the number never stalls.
    @Volatile private var pct = 0f
    @Volatile private var bandEnd = 0.02f
    @Volatile private var message = ""
    @Volatile private var startedAt = 0L
    @Volatile private var bandStart = 0f
    private var lastRun: Pair<String, SmartEditor.RevisionRun?>? = null

    private fun show() {
        _smartEdit.value = SmartEditUi.Working(message, pct.coerceIn(0f, 0.99f), ((System.currentTimeMillis() - startedAt) / 1000).toInt())
    }

    private fun stage(text: String, from: Float, to: Float, within: Float = 0f) {
        message = text
        bandStart = from
        bandEnd = to
        pct = maxOf(pct, from + (to - from) * within.coerceIn(0f, 1f))
        show()
    }

    private fun onProgress(p: SmartEditProgress): Unit = when (p) {
        is SmartEditProgress.Analyzing -> stage("Analysing clip ${p.index} of ${p.count}…", 0f, 0.10f, (p.index - 1f) / p.count)
        SmartEditProgress.LoadingModel -> stage("Loading the on-device AI model…", 0.10f, 0.14f)
        SmartEditProgress.ReadingBrief -> stage("AI is reading your brief…", 0.14f, 0.18f)
        is SmartEditProgress.Looking -> stage("AI is looking at moment ${p.index} of ${p.count}…", 0.18f, 0.38f, (p.index - 1f) / p.count)
        SmartEditProgress.Planning -> stage("Choosing shots and planning the edit…", 0.38f, 0.40f)
        is SmartEditProgress.AskingCloud -> stage("${p.engine} is planning the edit…", 0.40f, 0.62f)
        SmartEditProgress.PlanningOnPhone -> stage("Thinking the edit through on your phone…", 0.40f, 0.62f)
        SmartEditProgress.Building -> stage("Applying colour, text and sound…", 0.62f, 0.66f)
        SmartEditProgress.EnhancingAudio -> stage("Cleaning up the voices…", 0.66f, 0.68f)
        SmartEditProgress.Transcribing -> stage("Listening to what is said…", 0.68f, 0.84f)
        SmartEditProgress.CuttingOut -> stage("Cutting you out so the title can sit behind you…", 0.84f, 0.98f)
        is SmartEditProgress.Partial -> { pct = maxOf(pct, bandStart + (bandEnd - bandStart) * p.fraction.coerceIn(0f, 1f)); show() }
    }

    /** Stops the smart edit and starts it again from the beginning (same brief). */
    fun restartSmartEdit() {
        val again = lastRun ?: return
        val job = smartJob
        viewModelScope.launch {
            job?.cancelAndJoin()
            _smartEdit.value = SmartEditUi.Idle
            run(again.first, again.second)
        }
    }

    private fun run(id: String, revisionRun: SmartEditor.RevisionRun?) {
        if (_smartEdit.value is SmartEditUi.Working) return
        lastRun = id to revisionRun
        smartJob = viewModelScope.launch {
            // Make sure the latest brief text is stored before it's read.
            _state.value.brief?.let { if (!_state.value.saved) briefRepository.saveBrief(it) }
            pct = 0f
            startedAt = System.currentTimeMillis()
            stage("Reading your brief…", 0f, 0.02f)
            val ticker = launch {
                while (true) {
                    kotlinx.coroutines.delay(700)
                    pct = maxOf(pct, pct + (bandEnd - pct) * 0.025f)
                    show()
                }
            }
            try {
                // Kept alive by a foreground service, so it finishes even if the user switches apps.
                val result = try {
                    com.cinemind.core.common.BackgroundWork.run(appContext, "Making your Smart edit") { smartEditor.create(id, ::onProgress, revisionRun) }
                } finally {
                    ticker.cancel()
                }
                // Remember the original so this edit (and later versions) can be revised.
                lineage.edit().putString(result.projectId, lineage.getString(id, null) ?: id).apply()
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

    // ---- Prompt library: built-in briefs by genre, plus the user's own, favourites and recents.

    data class LibraryUi(val custom: List<com.cinemind.core.domain.aiedit.PromptTemplate>, val favourites: Set<String>, val recents: List<String>)

    private val libraryPrefs by lazy { appContext.getSharedPreferences("prompt_library", android.content.Context.MODE_PRIVATE) }
    private val _library = MutableStateFlow(LibraryUi(emptyList(), emptySet(), emptyList()))
    val library: StateFlow<LibraryUi> = _library.asStateFlow()

    private fun readLibrary() {
        _library.value = LibraryUi(
            custom = com.cinemind.core.domain.aiedit.PromptLibrary.decodeCustom(libraryPrefs.getString("custom", null)),
            favourites = libraryPrefs.getString("favourites", "").orEmpty().split(',').filter { it.isNotBlank() }.toSet(),
            recents = libraryPrefs.getString("recents", "").orEmpty().split(',').filter { it.isNotBlank() },
        )
    }

    /** Fills the brief from a template: words, style, pacing and length. */
    fun useTemplate(t: com.cinemind.core.domain.aiedit.PromptTemplate) {
        edit { it.copy(prompt = EditBriefRules.clampPrompt(t.prompt), style = t.style, pacing = t.pacing, targetDurationSec = t.seconds ?: it.targetDurationSec) }
        val recents = com.cinemind.core.domain.aiedit.PromptLibrary.pushRecent(_library.value.recents, t.id)
        libraryPrefs.edit().putString("recents", recents.joinToString(",")).apply()
        readLibrary()
    }

    fun toggleFavourite(id: String) {
        val favs = _library.value.favourites.let { if (id in it) it - id else it + id }
        libraryPrefs.edit().putString("favourites", favs.joinToString(",")).apply()
        readLibrary()
    }

    /** Saves the current brief as the user's own template. */
    fun saveAsTemplate(title: String, genre: com.cinemind.core.domain.aiedit.Genre) {
        val b = _state.value.brief ?: return
        if (b.prompt.isBlank()) return
        val t = com.cinemind.core.domain.aiedit.PromptTemplate(
            id = "mine-" + System.currentTimeMillis(), genre = genre, title = title.ifBlank { b.prompt.take(30) }.take(60),
            prompt = b.prompt, style = b.style, pacing = b.pacing, seconds = b.targetDurationSec, custom = true,
        )
        libraryPrefs.edit().putString("custom", com.cinemind.core.domain.aiedit.PromptLibrary.encodeCustom(listOf(t) + _library.value.custom)).apply()
        readLibrary()
    }

    fun deleteTemplate(id: String) {
        libraryPrefs.edit().putString("custom", com.cinemind.core.domain.aiedit.PromptLibrary.encodeCustom(_library.value.custom.filter { it.id != id })).apply()
        readLibrary()
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
        readLibrary()
        _references.value = com.cinemind.feature.ai.generation.smartedit.ReferenceStore.load(appContext, id)
        viewModelScope.launch {
            _music.value = runCatching { projectRepository.getProjectAudio(id).music?.let { assetRepository.getAsset(it.assetId)?.displayName } }.getOrNull()
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
        voice.cancel()
        flush()
        super.onCleared()
    }
}
