package com.cinemind.feature.editor.timeline.handoff

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cinemind.core.common.CineMindLogger
import com.cinemind.core.domain.aigen.AiEngine
import com.cinemind.core.domain.aigen.AiReadiness
import com.cinemind.core.domain.aigen.AiRouter
import com.cinemind.core.domain.aigen.AiTask
import com.cinemind.core.domain.aigen.GenerationJob
import com.cinemind.core.domain.aigen.GenerationProvider
import com.cinemind.core.domain.aigen.GenerationProviders
import com.cinemind.core.domain.aigen.HandoffBriefs
import com.cinemind.core.domain.aigen.JobStatus
import com.cinemind.core.domain.repository.GenerationJobRepository
import com.cinemind.feature.ai.cloud.CloudKeyStore
import com.cinemind.feature.media.importer.MediaImporter
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Reference remake through an outside provider (KATANA in Claude). CineMind prepares and hands
 * over; the provider does the generation in the user's own account; the user imports the result.
 * Every job is stored so its provenance survives restarts, and nothing is reported as done until
 * the user has actually imported a file.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class RemakeViewModel @Inject constructor(
    @ApplicationContext context: Context,
    private val jobs: GenerationJobRepository,
    private val importer: MediaImporter,
    private val keys: CloudKeyStore,
) : ViewModel() {

    private val sender = HandoffSender(context)

    data class Draft(
        val reference: Uri? = null,
        val referenceName: String = "",
        val referenceUs: Long? = null,
        val photos: List<Uri> = emptyList(),
        val instructions: String = "",
        val aspect: String? = null,
    )

    val provider: GenerationProvider = GenerationProviders.KATANA

    private val projectId = MutableStateFlow<String?>(null)
    private val _draft = MutableStateFlow(Draft())
    val draft: StateFlow<Draft> = _draft.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** False in Offline mode: nothing may leave the phone. */
    val allowed: StateFlow<Boolean> = keys.settings
        .map { AiRouter.route(AiTask.REFERENCE_REMAKE, it.mode, AiReadiness(gemmaReady = false, claudeReady = false)) == AiEngine.HANDOFF }
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val projectJobs: StateFlow<List<GenerationJob>> = projectId.filterNotNull()
        .flatMapLatest { jobs.observe(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun bind(projectId: String, aspect: String?) {
        this.projectId.value = projectId
        _draft.update { it.copy(aspect = aspect) }
    }

    fun setReference(uri: Uri) = viewModelScope.launch {
        val us = sender.durationUs(uri)
        _draft.update { it.copy(reference = uri, referenceName = sender.displayName(uri), referenceUs = us) }
    }

    fun addPhotos(uris: List<Uri>) = _draft.update { d -> d.copy(photos = (d.photos + uris).distinct().take(provider.maxPhotos)) }
    fun removePhoto(uri: Uri) = _draft.update { it.copy(photos = it.photos - uri) }
    fun setInstructions(text: String) = _draft.update { it.copy(instructions = text.take(HandoffBriefs.MAX_INSTRUCTIONS)) }
    fun clearMessage() { _message.value = null }

    fun brief() = _draft.value.let { d ->
        HandoffBriefs.compose(provider, d.instructions, d.referenceUs, d.photos.size, d.aspect)
    }

    /** Copies the media, records the job and opens Claude (or the share sheet). */
    fun send() {
        val project = projectId.value ?: return
        val d = _draft.value
        val brief = brief()
        if (!allowed.value) { _message.value = "Offline mode is on (Settings → Cloud AI)."; return }
        if (!brief.ready) { _message.value = brief.problems.first(); return }
        viewModelScope.launch {
            _busy.value = true
            val now = System.currentTimeMillis()
            var job = GenerationJob(
                projectId = project,
                providerId = provider.id,
                task = AiTask.REFERENCE_REMAKE.name,
                instructions = d.instructions.trim(),
                brief = brief.text,
                referenceUri = d.reference?.toString(),
                photoUris = d.photos.joinToString("\n"),
                createdAt = now,
            )
            try {
                val uris = sender.stage(job.id, d.reference, d.photos)
                val target = sender.send(provider, brief.text, uris)
                job = job.moveTo(JobStatus.HANDED_OFF, System.currentTimeMillis())
                jobs.save(job)
                _draft.update { Draft(aspect = it.aspect) }
                _message.value = when (target) {
                    HandoffSender.Target.PROVIDER_APP -> "Sent to ${provider.appName}. The brief is also copied, so paste it if it's missing."
                    HandoffSender.Target.CHOOSER -> "${provider.appName} isn't installed, so pick an app. The brief is copied to paste."
                }
                CineMindLogger.i("Handoff ${job.id} to ${provider.id} via $target")
            } catch (e: Exception) {
                sender.discard(job.id)
                _message.value = e.message ?: "Couldn't hand over the files"
                CineMindLogger.w("Handoff failed: ${e.message}")
            } finally {
                _busy.value = false
            }
        }
    }

    /** Opens the official KATANA page instead (the brief goes on the clipboard). */
    fun openPage() {
        if (!allowed.value) { _message.value = "Offline mode is on (Settings → Cloud AI)."; return }
        runCatching { sender.openPage(provider, brief().text) }
            .onSuccess { _message.value = "Opened ${provider.launchUrl}. The brief is copied." }
            .onFailure { _message.value = it.message }
    }

    /**
     * Imports the finished video the user downloaded, links it to the job, and hands the URI back
     * so the editor can add it to the timeline like any other clip.
     */
    fun importResult(jobId: String, uri: Uri, onImported: (Uri) -> Unit) = viewModelScope.launch {
        val job = jobs.get(jobId) ?: return@launch
        runCatching { importer.import(uri) }
            .onSuccess { asset ->
                if (!asset.isVideo && !asset.isImage) {
                    _message.value = "That isn't a video or photo"
                    return@onSuccess
                }
                jobs.save(job.moveTo(JobStatus.IMPORTED, System.currentTimeMillis(), outputAssetId = asset.id))
                sender.discard(jobId)
                _message.value = "Imported and added to the end of your video"
                onImported(uri)
            }
            .onFailure { _message.value = "Couldn't import that file: ${it.message}" }
    }

    fun cancel(jobId: String) = viewModelScope.launch {
        val job = jobs.get(jobId) ?: return@launch
        if (job.statusEnum.canMoveTo(JobStatus.CANCELLED)) {
            jobs.save(job.moveTo(JobStatus.CANCELLED, System.currentTimeMillis()))
            sender.discard(jobId)
        }
    }
}
