package com.cinemind.feature.ai.generation.smartedit

import android.content.Context
import android.net.Uri
import com.cinemind.core.common.CineMindLogger
import com.cinemind.core.domain.aiedit.BuiltEdit
import com.cinemind.core.domain.aiedit.CreativeDirector
import com.cinemind.core.domain.aiedit.DirectorBrief
import com.cinemind.core.domain.aiedit.EditPlan
import com.cinemind.core.domain.aiedit.EditPlanExecutor
import com.cinemind.core.domain.aiedit.EditPlanValidator
import com.cinemind.core.domain.aiedit.FootageMoment
import com.cinemind.core.domain.aiedit.FootageSource
import com.cinemind.core.domain.aiedit.MeasuredShot
import com.cinemind.core.domain.aiedit.PlanRequest
import com.cinemind.core.domain.aiedit.PlanShot
import com.cinemind.core.domain.aiedit.PlanSource
import com.cinemind.core.domain.aiedit.PlanStep
import com.cinemind.core.domain.aiedit.StepStatus
import com.cinemind.core.domain.audio.SfxSynth
import com.cinemind.core.domain.captions.CaptionPresets
import com.cinemind.core.domain.editing.OverlayEditing
import com.cinemind.core.domain.model.Clip
import com.cinemind.core.domain.model.MediaAsset
import com.cinemind.core.domain.model.TrackType
import com.cinemind.core.domain.repository.CaptionRepository
import com.cinemind.core.domain.repository.EditBriefRepository
import com.cinemind.core.domain.repository.MediaAssetRepository
import com.cinemind.core.domain.repository.ProjectRepository
import com.cinemind.core.domain.repository.TextLayerRepository
import com.cinemind.feature.ai.cloud.CloudAiException
import com.cinemind.feature.ai.cloud.EditAssistant
import com.cinemind.feature.ai.llm.ondevice.AiPreferences
import com.cinemind.feature.ai.llm.ondevice.OnDeviceLlm
import com.cinemind.feature.ai.llm.ondevice.Prompts
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

sealed interface SmartEditProgress {
    data class Analyzing(val index: Int, val count: Int) : SmartEditProgress
    data object LoadingModel : SmartEditProgress
    data object ReadingBrief : SmartEditProgress
    data class Looking(val index: Int, val count: Int) : SmartEditProgress
    data object Planning : SmartEditProgress
    data class AskingCloud(val engine: String) : SmartEditProgress
    data object Building : SmartEditProgress
}

/**
 * What a smart edit produced: the new project, the creative decisions, every executed step
 * (applied, adjusted or skipped — nothing is claimed that didn't happen) and open questions.
 */
data class SmartEditResult(
    val projectId: String,
    val decisions: List<String>,
    val usedAi: Boolean,
    val engine: String = "On-device",
    val summary: String = "",
    val steps: List<PlanStep> = emptyList(),
    val questions: List<String> = emptyList(),
)

class SmartEditException(message: String) : Exception(message)

/**
 * Brief + footage → a new, fully edited and editable project. The source project is never
 * modified, so the AI edit can always be reverted by going back to it.
 *
 * Pipeline: analyse every source (on-device measurements across the whole clip) → optionally the
 * on-device model reads the brief and describes key moments → choose shots → the creative
 * director plans the complete edit (colour, ramps, transitions, text, captions, sound, music) —
 * or, when the user enabled Cloud AI, Claude plans it from a text footage report → the plan is
 * validated against the project and the engine → executed into real timeline objects → saved.
 */
class SmartEditor @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val projectRepository: ProjectRepository,
    private val assetRepository: MediaAssetRepository,
    private val briefRepository: EditBriefRepository,
    private val captionRepository: CaptionRepository,
    private val textRepository: TextLayerRepository,
    private val analyzer: FrameAnalyzer,
    private val describer: AiFrameDescriber,
    private val llm: OnDeviceLlm,
    private val aiPrefs: AiPreferences,
    private val cloud: EditAssistant,
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
        }

        onProgress(SmartEditProgress.Planning)
        val selection = SmartEditPlanner.plan(analyses, intent, insights)
        if (selection.shots.isEmpty()) throw SmartEditException("Couldn't find usable shots in this footage.")

        // ---- what the planners know
        val sources = analyses.map { a -> PlanSource(a.assetId, a.isVideo, a.durationUs, assets.first { it.id == a.assetId }.displayName) }
        val sourceIndex = sources.withIndex().associate { (i, s) -> s.assetId to i + 1 }
        val sharpRef = MomentScoring.sharpnessReference(analyses.flatMap { a -> a.samples.map { it.sharpness } })
        val measured = selection.shots.map { s ->
            val a = analyses.first { it.assetId == s.assetId }
            val inside = a.samples.filter { it.timeUs in s.sourceInUs..s.sourceOutUs }.ifEmpty { a.samples }
            MeasuredShot(
                shot = PlanShot(sourceIndex.getValue(s.assetId), s.sourceInUs / 1e6, s.sourceOutUs / 1e6),
                score = s.score,
                brightness = if (inside.isEmpty()) -1f else inside.map { it.brightness }.average().toFloat(),
                motion = if (inside.isEmpty()) -1f else inside.map { (it.motion / 0.2f).coerceIn(0f, 1f) }.average().toFloat(),
                seen = AiAssist.nearest(insights[s.assetId], (s.sourceInUs + s.sourceOutUs) / 2)?.insight?.let { "${it.subject}, ${it.scene}" }.orEmpty(),
            )
        }
        val captions = runCatching { captionRepository.getCaptions(projectId) }.getOrDefault(emptyList())
        val audio = projectRepository.getProjectAudio(projectId)
        val filmedAt = assets.mapNotNull { it.dateTaken }.minOrNull()
        val director = CreativeDirector.direct(
            measured,
            DirectorBrief(
                style = intent.style, pacing = intent.pacing, prompt = brief.prompt, projectName = timeline.project.name,
                transition = intent.transition, transitionMs = intent.transitionMs, openingMs = intent.openingMs, endingMs = intent.endingMs,
                hasMusic = audio.music != null, hasSpeech = captions.isNotEmpty(), filmedAt = filmedAt,
            ),
        )
        val dateText = filmedAt?.let { SimpleDateFormat("d MMM yyyy", Locale.ENGLISH).format(Date(it)) }
        val ground = listOfNotNull(brief.prompt, timeline.project.name, dateText, captions.joinToString(" ") { it.text }).joinToString(" ")

        // ---- optional cloud plan (only with the user's own key and consent; text only)
        var engine = if (insights.isNotEmpty()) "On-device AI" else "On-device"
        var chosen: EditPlan = director
        if (cloud.usesCloud) {
            onProgress(SmartEditProgress.AskingCloud(cloud.engineName))
            try {
                val request = PlanRequest(
                    request = brief.prompt,
                    style = intent.style.label,
                    pacing = intent.pacing.label,
                    targetSeconds = brief.targetDurationSec ?: intent.targetUs?.let { (it / 1_000_000).toInt() },
                    aspect = timeline.project.aspectRatio,
                    sources = analyses.map { a ->
                        FootageSource(
                            isVideo = a.isVideo,
                            seconds = a.durationUs / 1e6,
                            moments = a.samples.map { p ->
                                FootageMoment(
                                    seconds = p.timeUs / 1e6,
                                    quality = MomentScoring.score(p, sharpRef, false),
                                    brightness = p.brightness,
                                    motion = (p.motion / 0.2f).coerceIn(0f, 1f),
                                    seen = AiAssist.nearest(insights[a.assetId], p.timeUs)?.takeIf { kotlin.math.abs(it.timeUs - p.timeUs) < 400_000 }
                                        ?.insight?.let { "${it.subject}, ${it.scene}" }.orEmpty(),
                                )
                            },
                            label = assets.first { it.id == a.assetId }.displayName,
                        )
                    },
                    speech = captions.joinToString(" ") { "[${"%.1f".format(Locale.ROOT, it.startUs / 1e6)}s] ${it.text}" },
                    hasMusic = audio.music != null,
                    filmedOn = dateText,
                    baseline = director,
                )
                val plan = cloud.planEdit(request)
                if (plan != null) {
                    val check = EditPlanValidator.validate(plan, sources, ground, hasCaptions = captions.isNotEmpty())
                    if (check.usable) {
                        chosen = plan
                        engine = cloud.engineName
                    } else {
                        aiNotes += "${cloud.engineName}'s plan had no usable shots; used the on-device plan"
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: CloudAiException) {
                aiNotes += "${cloud.engineName} couldn't plan this (${e.message}); used the on-device plan"
            } catch (e: Exception) {
                CineMindLogger.e(e, "Cloud plan failed")
                aiNotes += "Cloud AI failed (${e.message?.take(80)}); used the on-device plan"
            }
        }

        onProgress(SmartEditProgress.Building)
        val validated = EditPlanValidator.validate(chosen, sources, ground, hasCaptions = captions.isNotEmpty())
            .takeIf { it.usable } ?: EditPlanValidator.validate(director, sources, ground, hasCaptions = captions.isNotEmpty())
        if (!validated.usable) throw SmartEditException("Couldn't build an edit from this footage.")

        val name = timeline.project.name.take(60) + if (engine == "On-device") " · Smart edit" else " · AI edit"
        val built = EditPlanExecutor.execute(validated, sources, timeline.project, name, timeline, captions)
        val newId = projectRepository.createProjectWithTimeline(built.timeline)
        val steps = built.steps.toMutableList()
        persistExtras(newId, built, audio, steps)
        runCatching { briefRepository.saveBrief(brief.copy(projectId = newId)) }

        CineMindLogger.d("Smart edit $newId by $engine: " + steps.joinToString(" | ") { "${it.status} ${it.area}: ${it.text}" })
        return SmartEditResult(
            projectId = newId,
            decisions = listOf(validated.plan.summary).filter { it.isNotBlank() } + validated.plan.decisions + selection.decisions.take(1) + aiNotes,
            usedAi = engine != "On-device",
            engine = engine,
            summary = validated.plan.summary,
            steps = steps,
            questions = validated.plan.questions,
        )
    }

    /** Saves the parts beyond the main track. A failure is reported as a skipped step, never hidden. */
    private suspend fun persistExtras(newId: String, built: BuiltEdit, audio: com.cinemind.core.domain.audio.ProjectAudio, steps: MutableList<PlanStep>) {
        suspend fun attempt(area: String, what: String, block: suspend () -> Unit) {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                CineMindLogger.e(e, "Smart edit: $what failed")
                steps += PlanStep(area, StepStatus.SKIPPED, "Couldn't save $what (${e.message?.take(60)})")
            }
        }
        val texts = built.texts.map { it.copy(projectId = newId) }
        if (texts.isNotEmpty()) attempt("Text", "the titles") { textRepository.save(newId, texts) }
        if (built.captions.isNotEmpty()) attempt("Captions", "the captions") { captionRepository.saveCaptions(newId, built.captions.map { it.copy(projectId = newId) }) }
        built.captionPreset?.let { id -> CaptionPresets.byId(id)?.let { p -> attempt("Captions", "the caption style") { captionRepository.saveStyle(p.applyTo(newId)) } } }

        val a = built.audio
        audio.music?.let { m ->
            attempt("Music", "the music") {
                projectRepository.saveMusic(newId, m.copy(
                    volume = if (a.musicVolume >= 0) a.musicVolume.toFloat() else m.volume,
                    fadeInMs = if (a.musicFadeInSeconds >= 0) (a.musicFadeInSeconds * 1000).toLong() else m.fadeInMs,
                    fadeOutMs = if (a.musicFadeOutSeconds >= 0) (a.musicFadeOutSeconds * 1000).toLong() else m.fadeOutMs,
                ))
            }
        }
        val original = if (a.originalVolume >= 0) a.originalVolume.toFloat() else audio.originalVolume
        if (original < 1f) attempt("Music", "the original sound level") { projectRepository.setOriginalAudioVolume(newId, original) }

        if (built.sounds.isNotEmpty()) attempt("Sound", "the sound effects") {
            val total = built.timeline.project.durationUs
            val tracks = mutableListOf<Pair<String, MutableList<Clip>>>()
            for ((effect, at) in built.sounds) {
                val asset = soundAsset(effect)
                val length = minOf(asset.durationUs, total - at)
                if (length <= 0) continue
                var placed = false
                for ((trackId, list) in tracks) {
                    val base = Clip(trackId = trackId, assetId = asset.id, timelineInUs = 0, timelineOutUs = 0, sourceInUs = 0, sourceOutUs = length)
                    val next = OverlayEditing.place(list, base, at, length, total) ?: continue
                    list.clear(); list += next
                    placed = true
                    break
                }
                if (!placed && tracks.size < 3) {
                    val t = projectRepository.addSfxTrack(newId)
                    val base = Clip(trackId = t.id, assetId = asset.id, timelineInUs = 0, timelineOutUs = 0, sourceInUs = 0, sourceOutUs = length)
                    OverlayEditing.place(emptyList(), base, at, length, total)?.let { tracks += t.id to it.toMutableList() }
                }
            }
            tracks.forEach { (trackId, list) -> projectRepository.saveTrackClips(newId, trackId, list) }
        }
    }

    /** The synthesised effect as a stored audio file (created once, reused afterwards). */
    private suspend fun soundAsset(effect: com.cinemind.core.domain.audio.SoundEffect): MediaAsset {
        val asset = withContext(Dispatchers.Default) {
            val dir = File(appContext.filesDir, "sfx").apply { mkdirs() }
            val file = File(dir, effect.name.lowercase(Locale.ROOT) + ".wav")
            val samples = SfxSynth.render(effect)
            if (!file.exists() || file.length() < 44) file.writeBytes(SfxSynth.wav(samples))
            MediaAsset(
                uri = Uri.fromFile(file).toString(),
                mimeType = "audio/wav",
                width = 0,
                height = 0,
                durationUs = SfxSynth.durationUs(samples.size),
                hasAudio = true,
                displayName = effect.label,
                sizeBytes = file.length(),
            )
        }
        assetRepository.getAssetByUri(asset.uri)?.let { return it }
        assetRepository.createAsset(asset)
        return asset
    }

    private companion object {
        const val MAX_FRAMES = 24
    }
}
