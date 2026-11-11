package com.cinemind.feature.ai.generation.smartedit

import com.cinemind.core.domain.model.TrackType
import com.cinemind.core.domain.repository.EditBriefRepository
import com.cinemind.core.domain.repository.MediaAssetRepository
import com.cinemind.core.domain.repository.ProjectRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject

sealed interface SmartEditProgress {
    data class Analyzing(val index: Int, val count: Int) : SmartEditProgress
    data object Planning : SmartEditProgress
}

data class SmartEditResult(val projectId: String, val decisions: List<String>)

class SmartEditException(message: String) : Exception(message)

/**
 * Brief + footage → a new, fully editable project. The source project is never modified.
 * Pipeline: read brief → analyse each source (on-device) → plan shots → build timeline → save.
 */
class SmartEditor @Inject constructor(
    private val projectRepository: ProjectRepository,
    private val assetRepository: MediaAssetRepository,
    private val briefRepository: EditBriefRepository,
    private val analyzer: FrameAnalyzer,
) {
    suspend fun create(projectId: String, onProgress: (SmartEditProgress) -> Unit = {}): SmartEditResult {
        val timeline = projectRepository.getProjectTimeline(projectId) ?: throw SmartEditException("Project not found")
        val track = timeline.tracks.firstOrNull { it.type == TrackType.VIDEO }
        val clips = timeline.clips.filter { it.trackId == track?.id }.sortedBy { it.timelineInUs }
        // Analyse each source once, in the order it first appears.
        val assetIds = clips.map { it.assetId }.distinct()
        if (assetIds.isEmpty()) throw SmartEditException("Add some clips first.")

        val assets = assetIds.mapNotNull { assetRepository.getAsset(it) }
        if (assets.isEmpty()) throw SmartEditException("The project's media is no longer available.")

        val analyses = assets.mapIndexed { i, asset ->
            onProgress(SmartEditProgress.Analyzing(i + 1, assets.size))
            analyzer.analyze(asset, order = i)
        }

        onProgress(SmartEditProgress.Planning)
        val brief = briefRepository.observeBrief(projectId).first()
        val plan = SmartEditPlanner.plan(analyses, BriefInterpreter.interpret(brief))
        if (plan.shots.isEmpty()) throw SmartEditException("Couldn't find usable shots in this footage.")

        val name = (timeline.project.name.take(60) + " · Smart edit")
        val result = SmartEditPlanner.toTimeline(plan, timeline.project, name)
        val newId = projectRepository.createProjectWithTimeline(result)
        briefRepository.saveBrief(brief.copy(projectId = newId))
        // Keep the source project's music and original-sound level.
        val audio = projectRepository.getProjectAudio(projectId)
        audio.music?.let { projectRepository.saveMusic(newId, it) }
        if (audio.originalVolume < 1f) projectRepository.setOriginalAudioVolume(newId, audio.originalVolume)
        return SmartEditResult(newId, plan.decisions)
    }
}
