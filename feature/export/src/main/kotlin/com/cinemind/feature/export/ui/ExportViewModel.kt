package com.cinemind.feature.export.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cinemind.core.domain.model.Project
import com.cinemind.core.domain.repository.ProjectRepository
import com.cinemind.feature.export.engine.ExportManager
import com.cinemind.feature.export.engine.ExportState
import com.cinemind.feature.export.plan.ExportPlanner
import com.cinemind.feature.export.plan.ExportQuality
import com.cinemind.feature.export.plan.ExportResolution
import com.cinemind.feature.export.plan.ExportSettings
import com.cinemind.feature.export.plan.OutputSize
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ExportEstimate(val size: OutputSize, val videoBitrateBps: Int, val sizeBytes: Long)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ExportViewModel @Inject constructor(
    projectRepository: ProjectRepository,
    private val exportManager: ExportManager,
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
) : ViewModel() {

    /** Set when the previous export never finished because the app closed (stage it reached). */
    private val _interrupted = MutableStateFlow(
        if (exportManager.state.value.isActive) null
        else com.cinemind.core.common.Diagnostics.interruptedJob(context)?.getProperty("stage"),
    )
    val interrupted: StateFlow<String?> = _interrupted

    fun dismissInterrupted() {
        com.cinemind.core.common.Diagnostics.clearInterrupted(context)
        _interrupted.value = null
    }

    private val projectId = MutableStateFlow<String?>(null)

    val project: StateFlow<Project?> = projectId.filterNotNull()
        .flatMapLatest { projectRepository.observeProject(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _settings = MutableStateFlow(ExportSettings())
    val settings: StateFlow<ExportSettings> = _settings

    val exportState: StateFlow<ExportState> = exportManager.state

    fun load(id: String) {
        if (projectId.value == id) return
        projectId.value = id
        // Default the frame rate to the project's own.
        viewModelScope.launch {
            val p = project.filterNotNull().first()
            val fps = p.frameRate.takeIf { it in ExportPlanner.FRAME_RATES } ?: 30
            _settings.value = _settings.value.copy(frameRate = fps)
        }
    }

    fun setResolution(r: ExportResolution) { _settings.value = _settings.value.copy(resolution = r) }
    fun setFrameRate(fps: Int) { _settings.value = _settings.value.copy(frameRate = fps) }
    fun setQuality(q: ExportQuality) { _settings.value = _settings.value.copy(quality = q) }

    fun estimate(project: Project, settings: ExportSettings): ExportEstimate {
        val size = ExportPlanner.outputSize(project.resolutionWidth, project.resolutionHeight, settings.resolution)
        val bitrate = ExportPlanner.videoBitrateBps(size, settings.frameRate, settings.quality)
        return ExportEstimate(size, bitrate, ExportPlanner.estimatedSizeBytes(project.durationUs, bitrate))
    }

    fun startExport() {
        val id = projectId.value ?: return
        exportManager.start(id, _settings.value)
    }

    fun cancel() = exportManager.cancel()

    fun dismissResult() = exportManager.dismiss()
}
