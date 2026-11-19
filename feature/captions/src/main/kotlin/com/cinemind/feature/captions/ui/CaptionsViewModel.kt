package com.cinemind.feature.captions.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cinemind.core.domain.captions.Caption
import com.cinemind.core.domain.captions.CaptionEditing
import com.cinemind.core.domain.captions.CaptionPreset
import com.cinemind.core.domain.captions.CaptionScript
import com.cinemind.core.domain.captions.CaptionStyle
import com.cinemind.core.domain.captions.SpokenLanguage
import com.cinemind.core.domain.editing.EditHistory
import com.cinemind.core.domain.repository.CaptionRepository
import com.cinemind.feature.ai.llm.ondevice.AudioNotSupportedException
import com.cinemind.feature.ai.llm.ondevice.ModelNotInstalledException
import com.cinemind.feature.ai.llm.ondevice.OnDeviceLlm
import com.cinemind.feature.captions.speech.SpeechSource
import com.cinemind.feature.captions.speech.SpeechTranscriber
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Speech-to-text job as shown in the captions sheet. */
data class SpeechJobUi(
    val running: Boolean = false,
    val progress: Float = 0f,
    val message: String = "",
    val error: String? = null,
    /** Short report after a finished run, e.g. "12 captions from 3 clips · 41 s". */
    val summary: String? = null,
)

data class CaptionsUi(
    val loaded: Boolean = false,
    val captions: List<Caption> = emptyList(),
    val style: CaptionStyle = CaptionStyle.default(""),
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val job: SpeechJobUi = SpeechJobUi(),
    /** An on-device model that can listen is installed. */
    val speechModelReady: Boolean = false,
    val language: SpokenLanguage = SpokenLanguage.AUTO,
    val script: CaptionScript = CaptionScript.HINGLISH,
)

/**
 * The caption layer of one project: generation (speech-to-text), editing, style, undo/redo.
 * Every change is saved immediately (autosave), so captions survive leaving the editor or a crash.
 */
@HiltViewModel
class CaptionsViewModel @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context,
    private val repository: CaptionRepository,
    private val transcriber: SpeechTranscriber,
    private val llm: OnDeviceLlm,
) : ViewModel() {

    private val _ui = MutableStateFlow(CaptionsUi())
    val ui: StateFlow<CaptionsUi> = _ui.asStateFlow()

    private var projectId: String? = null
    private var history = EditHistory<List<Caption>>(emptyList())
    private var job: Job? = null

    fun load(id: String) {
        if (projectId == id) return
        projectId = id
        viewModelScope.launch {
            val captions = repository.getCaptions(id)
            val style = repository.getStyle(id)
            history = EditHistory(captions)
            _ui.value = _ui.value.copy(loaded = true, captions = captions, style = style, speechModelReady = llm.supportsAudio || llm.speechModel() != null)
                .withHistory()
        }
    }

    fun refreshModelState() {
        _ui.value = _ui.value.copy(speechModelReady = llm.supportsAudio || llm.speechModel() != null)
    }

    fun setLanguage(language: SpokenLanguage) { _ui.value = _ui.value.copy(language = language) }
    fun setScript(script: CaptionScript) { _ui.value = _ui.value.copy(script = script) }

    /** Transcribes [sources] and replaces the caption layer (one undo step). */
    fun generate(sources: List<SpeechSource>) {
        val id = projectId ?: return
        if (job?.isActive == true) return
        if (sources.isEmpty()) {
            _ui.value = _ui.value.copy(job = SpeechJobUi(error = "No video clips with sound on the timeline."))
            return
        }
        val state = _ui.value
        job = viewModelScope.launch {
            _ui.value = _ui.value.copy(job = SpeechJobUi(running = true, message = "Starting…"))
            try {
                // Kept alive by a foreground service, so it finishes even if the user switches apps.
                val result = com.cinemind.core.common.BackgroundWork.run(appContext, "Writing captions from speech") {
                    transcriber.transcribe(id, sources, state.language, state.script) { p ->
                        _ui.value = _ui.value.copy(job = _ui.value.job.copy(progress = p.fraction, message = p.message))
                    }
                }
                val summary = buildString {
                    append("${result.captions.size} caption${if (result.captions.size == 1) "" else "s"}")
                    append(" · ${result.totalMs / 1000} s on this phone")
                    if (result.clipsWithoutSound > 0) append(" · ${result.clipsWithoutSound} clip(s) had no speech")
                    if (result.captions.any { it.uncertain }) append(" · check the ones marked ●")
                }
                if (result.captions.isEmpty()) {
                    _ui.value = _ui.value.copy(job = SpeechJobUi(error = "No speech was recognised. Music-only or very noisy clips can't be captioned; you can add captions by hand."))
                } else {
                    commit(result.captions)
                    _ui.value = _ui.value.copy(job = SpeechJobUi(summary = summary))
                }
            } catch (e: CancellationException) {
                _ui.value = _ui.value.copy(job = SpeechJobUi(message = "Cancelled"))
                throw e
            } catch (e: ModelNotInstalledException) {
                _ui.value = _ui.value.copy(job = SpeechJobUi(error = "Install an on-device AI model first (On-device AI). Until then you can add captions by hand."))
            } catch (e: AudioNotSupportedException) {
                _ui.value = _ui.value.copy(job = SpeechJobUi(error = "${e.message}. You can still add captions by hand."))
            } catch (e: Exception) {
                _ui.value = _ui.value.copy(job = SpeechJobUi(error = "Speech-to-text failed: ${e.message ?: e.javaClass.simpleName}"))
            }
        }
    }

    /** Translates every caption to English (separate from transcription). One undo step. */
    fun translateAllToEnglish() {
        if (job?.isActive == true || _ui.value.captions.isEmpty()) return
        job = viewModelScope.launch {
            _ui.value = _ui.value.copy(job = SpeechJobUi(running = true, message = "Translating…"))
            try {
                val out = com.cinemind.core.common.BackgroundWork.run(appContext, "Translating captions") {
                    transcriber.translateToEnglish(_ui.value.captions) { p ->
                        _ui.value = _ui.value.copy(job = _ui.value.job.copy(progress = p.fraction, message = p.message))
                    }
                }
                commit(out)
                _ui.value = _ui.value.copy(job = SpeechJobUi(summary = "Translated to English. Undo to get the original back."))
            } catch (e: CancellationException) {
                _ui.value = _ui.value.copy(job = SpeechJobUi())
                throw e
            } catch (e: Exception) {
                _ui.value = _ui.value.copy(job = SpeechJobUi(error = "Translation failed: ${e.message ?: e.javaClass.simpleName}"))
            }
        }
    }

    fun cancelJob() { job?.cancel() }
    fun dismissJobMessage() { if (_ui.value.job.running.not()) _ui.value = _ui.value.copy(job = SpeechJobUi()) }

    fun editText(id: String, text: String) = commit(CaptionEditing.editText(_ui.value.captions, id, text))

    fun retime(id: String, startUs: Long, endUs: Long, totalUs: Long) =
        commit(CaptionEditing.retime(_ui.value.captions, id, startUs, endUs, totalUs))

    /** Adds a caption at [atUs]; returns its id, or null when another caption is already there. */
    fun addAt(atUs: Long, totalUs: Long): String? {
        val id = projectId ?: return null
        val c = CaptionEditing.insertAt(_ui.value.captions, id, atUs, totalUs) ?: return null
        commit(CaptionEditing.sorted(_ui.value.captions + c))
        return c.id
    }

    fun delete(id: String) = commit(_ui.value.captions.filterNot { it.id == id })

    fun split(id: String, atUs: Long) {
        val c = _ui.value.captions.firstOrNull { it.id == id } ?: return
        val (a, b) = CaptionEditing.split(c, atUs) ?: return
        commit(CaptionEditing.sorted(_ui.value.captions.filterNot { it.id == id } + a + b))
    }

    fun clearAll() = commit(emptyList())

    fun undo() { if (history.undo()) applyHistory() }
    fun redo() { if (history.redo()) applyHistory() }

    fun updateStyle(transform: (CaptionStyle) -> CaptionStyle) {
        val id = projectId ?: return
        val next = transform(_ui.value.style).copy(projectId = id)
        _ui.value = _ui.value.copy(style = next)
        viewModelScope.launch { repository.saveStyle(next) }
    }

    fun applyPreset(preset: CaptionPreset) = updateStyle { preset.applyTo(it.projectId) }

    private fun commit(next: List<Caption>) {
        if (next == _ui.value.captions) return
        history.push(next)
        applyHistory()
    }

    private fun applyHistory() {
        val id = projectId ?: return
        val captions = history.present
        _ui.value = _ui.value.copy(captions = captions).withHistory()
        viewModelScope.launch { repository.saveCaptions(id, captions) }
    }

    private fun CaptionsUi.withHistory() = copy(canUndo = history.canUndo, canRedo = history.canRedo)

    /** For screenshots/tests: wait until loaded. */
    suspend fun awaitLoaded() { ui.first { it.loaded } }
}
