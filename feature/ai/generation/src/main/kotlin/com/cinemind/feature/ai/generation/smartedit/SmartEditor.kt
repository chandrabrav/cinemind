package com.cinemind.feature.ai.generation.smartedit

import com.cinemind.core.common.CineMindLogger
import com.cinemind.core.domain.model.TrackType
import com.cinemind.core.domain.repository.EditBriefRepository
import com.cinemind.core.domain.repository.MediaAssetRepository
import com.cinemind.core.domain.repository.ProjectRepository
import com.cinemind.feature.ai.llm.ondevice.AiPreferences
import com.cinemind.feature.ai.llm.ondevice.OnDeviceLlm
import com.cinemind.feature.ai.llm.ondevice.Prompts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject

sealed interface SmartEditProgress {
    data class Analyzing(val index: Int, val count: Int) : SmartEditProgress
    data object LoadingModel : SmartEditProgress
    data object ReadingBrief : SmartEditProgress
    data class Looking(val index: Int, val count: Int) : SmartEditProgress
    data object Planning : SmartEditProgress
}

data class SmartEditResult(val projectId: String, val decisions: List<String>, val usedAi: Boolean)

class SmartEditException(message: String) : Exception(message)

/**
 * Brief + footage → a new, fully editable project. The source project is never modified.
 * Pipeline: analyse each source (on-device measurements) → if an on-device model is installed and
 * enabled, let it read the brief and describe key frames → plan shots → build timeline → save.
 * Any model problem falls back to the measurement-only edit; the user still gets a result.
 */
class SmartEditor @Inject constructor(
    private val projectRepository: ProjectRepository,
    private val assetRepository: MediaAssetRepository,
    private val briefRepository: EditBriefRepository,
    private val analyzer: FrameAnalyzer,
    private val describer: AiFrameDescriber,
    private val llm: OnDeviceLlm,
    private val aiPrefs: AiPreferences,
) {
    suspend fun create(projectId: String, onProgress: (SmartEditProgress) -> Unit = {}): SmartEditResult {
        val timeline = projectRepository.getProjectTimeline(projectId) ?: throw SmartEditException("Project not found")
        val track = timeline.tracks.firstOrNull { it.type == TrackType.VIDEO }
        val clips = timeline.clips.filter { it.trackId == track?.id }.sortedBy { it.timelineInUs }
        val assetIds = clips.map { it.assetId }.distinct()
        if (assetIds.isEmpty()) throw SmartEditException("Add some clips first.")

        val assets = assetIds.mapNotNull { assetRepository.getAsset(it) }
        if (assets.isEmpty()) throw SmartEditException("The project's media is no longer available.")

        val analyses = assets.mapIndexed { i, asset ->
            onProgress(SmartEditProgress.Analyzing(i + 1, assets.size))
            analyzer.analyze(asset, order = i)
        }

        val brief = briefRepository.observeBrief(projectId).first()
        var intent = BriefInterpreter.interpret(brief)
        var insights: Map<String, List<TimedInsight>> = emptyMap()
        val aiNotes = mutableListOf<String>()

        if (aiPrefs.useAiInSmartEdit && llm.isAvailable) {
            try {
                onProgress(SmartEditProgress.LoadingModel)
                llm.ensureLoaded()

                if (brief.prompt.isNotBlank()) {
                    onProgress(SmartEditProgress.ReadingBrief)
                    val answer = Prompts.parseBrief(llm.ask(Prompts.brief(brief.prompt), maxOutputTokens = 120))
                    intent = AiAssist.mergeBrief(brief, intent, answer)
                    if (answer != null) aiNotes += "On-device AI read your brief"
                }

                val sharpRef = MomentScoring.sharpnessReference(analyses.flatMap { a -> a.samples.map { it.sharpness } })
                // Keep total model calls bounded: up to 3 frames per source, at most MAX_FRAMES overall.
                val perSource = (MAX_FRAMES / analyses.size).coerceIn(1, 3)
                val plan = analyses.zip(assets).map { (a, asset) -> asset to AiAssist.keyframes(a, perSource, sharpRef, intent.strictQuality) }
                val total = plan.sumOf { it.second.size }
                var done = 0
                insights = plan.associate { (asset, times) ->
                    val described = times.map { t ->
                        onProgress(SmartEditProgress.Looking(++done, total))
                        describer.describe(asset, listOf(t))
                    }.flatten()
                    asset.id to described
                }.filterValues { it.isNotEmpty() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                CineMindLogger.e(e, "On-device AI failed; using basic analysis")
                insights = emptyMap()
                aiNotes += "On-device AI couldn't run (${e.message?.take(80)}); used basic analysis"
            } finally {
                // Free the model's memory for editing and export.
                withContext(NonCancellable) { llm.unload() }
            }
        } else if (aiPrefs.useAiInSmartEdit) {
            aiNotes += "Tip: download an on-device AI model (✦ on the home screen) for smarter shot choices"
        }

        onProgress(SmartEditProgress.Planning)
        val plan = SmartEditPlanner.plan(analyses, intent, insights)
        if (plan.shots.isEmpty()) throw SmartEditException("Couldn't find usable shots in this footage.")

        val name = (timeline.project.name.take(60) + if (insights.isNotEmpty()) " · AI edit" else " · Smart edit")
        val result = SmartEditPlanner.toTimeline(plan, timeline.project, name)
        val newId = projectRepository.createProjectWithTimeline(result)
        briefRepository.saveBrief(brief.copy(projectId = newId))
        // Keep the source project's music and original-sound level.
        val audio = projectRepository.getProjectAudio(projectId)
        audio.music?.let { projectRepository.saveMusic(newId, it) }
        if (audio.originalVolume < 1f) projectRepository.setOriginalAudioVolume(newId, audio.originalVolume)
        return SmartEditResult(newId, plan.decisions + aiNotes, usedAi = insights.isNotEmpty())
    }

    private companion object {
        const val MAX_FRAMES = 24
    }
}
