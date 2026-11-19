package com.cinemind.feature.ai.generation.smartedit

import android.content.Context
import android.net.Uri
import com.cinemind.core.common.CineMindLogger
import com.cinemind.core.domain.agents.AgentId
import com.cinemind.core.domain.agents.DecisionEngine
import com.cinemind.core.domain.agents.Goal
import com.cinemind.core.domain.agents.ModelRequest
import com.cinemind.core.domain.agents.ProjectContext
import com.cinemind.core.domain.agents.RequestIntent
import com.cinemind.core.domain.agents.RuntimeKind
import com.cinemind.core.domain.aiedit.BuiltEdit
import com.cinemind.core.domain.aiedit.EditPlanJson
import com.cinemind.core.domain.model.EditStyle
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
    /** The on-phone planning model is thinking the edit through. */
    data object PlanningOnPhone : SmartEditProgress
    data object EnhancingAudio : SmartEditProgress
    data object Transcribing : SmartEditProgress
    /** Cutting the speaker out so text can sit behind them. */
    data object CuttingOut : SmartEditProgress
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
    /** Which agents ran and where (on this phone / cloud), and which were skipped and why. */
    val howMade: List<String> = emptyList(),
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
    private val runtime: AgentRuntime,
    private val transcriber: com.cinemind.feature.captions.speech.SpeechTranscriber,
) {
    /**
     * A follow-up on an earlier AI edit: [revision] says what changes and what stays; the new
     * version is made from the original footage ([create]'s projectId) and [previousId] is the
     * version being revised (its music is kept when asked). The previous version is never touched.
     */
    data class RevisionRun(val revision: com.cinemind.core.domain.aiedit.Revision, val previousId: String)

    suspend fun create(projectId: String, onProgress: (SmartEditProgress) -> Unit = {}, revisionRun: RevisionRun? = null): SmartEditResult =
        // Analysis, planning, model loading and transcription are heavy: off the main thread (ANRs on the Vivo).
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { createOnWorker(projectId, onProgress, revisionRun) }

    private suspend fun createOnWorker(projectId: String, onProgress: (SmartEditProgress) -> Unit, revisionRun: RevisionRun?): SmartEditResult {
        val revision = revisionRun?.revision
        val timeline = projectRepository.getProjectTimeline(projectId) ?: throw SmartEditException("Project not found")
        val track = timeline.tracks.firstOrNull { it.type == TrackType.VIDEO }
        val clips = timeline.clips.filter { it.trackId == track?.id }.sortedBy { it.timelineInUs }
        val assetIds = clips.map { it.assetId }.distinct()
        if (assetIds.isEmpty()) throw SmartEditException("Add some clips first.")

        val assets = assetIds.mapNotNull { assetRepository.getAsset(it) }
        if (assets.isEmpty()) throw SmartEditException("The project's media is no longer available.")

        // Execution trace for the developer diagnostics (agents, runtimes, durations, failures).
        val traceStart = System.currentTimeMillis()
        val trace = mutableListOf<com.cinemind.core.domain.agents.TraceEntry>()
        fun record(agent: AgentId, kind: RuntimeKind, model: String?, status: com.cinemind.core.domain.agents.TaskStatus, startMs: Long, note: String = "", attempt: Int = 1) {
            trace += com.cinemind.core.domain.agents.TraceEntry(java.util.UUID.randomUUID().toString().take(8), null, agent, kind, model, status, startMs, System.currentTimeMillis() - startMs, attempt, note, availMemMb())
        }
        val analysisStart = System.currentTimeMillis()
        // Footage analysis is reused between runs (same file = same measurements).
        var reusedAnalyses = 0
        val analyses = assets.mapIndexed { i, asset ->
            onProgress(SmartEditProgress.Analyzing(i + 1, assets.size))
            val key = cacheKey(asset) + "#" + i
            analysisCache[key]?.also { reusedAnalyses++ } ?: analyzer.analyze(asset, order = i).also { remember(analysisCache, key, it) }
        }

        val ownBrief = revision?.brief?.copy(projectId = projectId) ?: briefRepository.observeBrief(projectId).first()
        // Sample videos and reference images steer pace and look: the planner reads their measured style after the user's words.
        val references = ReferenceStore.load(appContext, projectId)
        val brief = ownBrief.copy(prompt = ownBrief.prompt + com.cinemind.core.domain.aiedit.StyleReferences.promptBlock(references))
        var intent = BriefInterpreter.interpret(brief)
        var insights: Map<String, List<TimedInsight>> = emptyMap()
        val aiNotes = mutableListOf<String>()
        val captions = if (revision?.dropCaptions == true) emptyList() else runCatching { captionRepository.getCaptions(projectId) }.getOrDefault(emptyList())
        val keepMusic = revision != null && com.cinemind.core.domain.agents.Aspect.MUSIC in revision.intent.keep
        val audio = revisionRun?.takeIf { keepMusic }?.let { runCatching { projectRepository.getProjectAudio(it.previousId) }.getOrNull() }
            ?: projectRepository.getProjectAudio(projectId)
        val cachedInsights = assets.mapNotNull { a -> insightCache[cacheKey(a)]?.let { a.id to it } }.toMap()

        val goals = revision?.intent?.goals ?: goalsOf(brief.prompt)
        // ---- CineMind Decision Engine: which agents this request needs, and where each runs.
        val orchestration = DecisionEngine.plan(
            revision?.intent ?: RequestIntent(
                goals = goals,
                creative = brief.prompt.isNotBlank() || brief.style != EditStyle.AUTO,
                targetSeconds = brief.targetDurationSec,
            ),
            ProjectContext(
                clipCount = clips.size,
                videoCount = assets.count { it.isVideo },
                totalSeconds = analyses.sumOf { it.durationUs } / 1e6,
                hasSpeech = if (captions.isNotEmpty()) true else null,
                hasMusic = audio.music != null,
                // What a revision may reuse instead of recomputing.
                available = buildSet {
                    if (revision != null) {
                        add(com.cinemind.core.domain.agents.DataKind.FOOTAGE_REPORT)
                        add(com.cinemind.core.domain.agents.DataKind.CREATIVE_BRIEF)
                        if (cachedInsights.size == assets.size) add(com.cinemind.core.domain.agents.DataKind.SHOT_DESCRIPTIONS)
                        if (keepMusic) add(com.cinemind.core.domain.agents.DataKind.MUSIC_PLAN)
                    }
                },
            ),
            runtime.models(),
            runtime.budget(),
            runtime.policy(),
        )
        CineMindLogger.i("Decision engine: " + orchestration.explain().joinToString(" | "))
        record(AgentId.VIDEO_ANALYSIS, RuntimeKind.DETERMINISTIC, null, com.cinemind.core.domain.agents.TaskStatus.SUCCEEDED, analysisStart, "${analyses.size} sources")
        // What actually ran (never what was only planned).
        val howMade = mutableListOf<String>()
        if (revision != null) howMade += "Revision: " + revision.understood.joinToString(", ").ifBlank { "your note" }
        howMade += "${AgentId.VIDEO_ANALYSIS.label}: " + if (reusedAnalyses == analyses.size) "reused the earlier measurements" else "measured ${analyses.size - reusedAnalyses} source${if (analyses.size - reusedAnalyses == 1) "" else "s"} on this phone"
        if (keepMusic) howMade += "${AgentId.MUSIC_DIRECTOR.label}: kept the music of the version you revised"
        // A revision that doesn't need to look again reuses what was seen before.
        if (orchestration.step(AgentId.VISUAL_INTELLIGENCE) == null && cachedInsights.isNotEmpty() && revision != null) {
            insights = cachedInsights
            howMade += "${AgentId.VISUAL_INTELLIGENCE.label}: reused what it saw last time"
        }

        val creativeStep = orchestration.step(AgentId.CREATIVE_DIRECTOR)
        val visualStep = orchestration.step(AgentId.VISUAL_INTELLIGENCE)
        if (creativeStep?.runtime == RuntimeKind.ON_DEVICE || visualStep != null) {
            try {
                onProgress(SmartEditProgress.LoadingModel)

                if (brief.prompt.isNotBlank() && creativeStep?.runtime == RuntimeKind.ON_DEVICE) {
                    onProgress(SmartEditProgress.ReadingBrief)
                    val briefStart = System.currentTimeMillis()
                    val model = runtime.model(creativeStep.modelId)
                    val answer = model?.let { Prompts.parseBrief(it.infer(ModelRequest(Prompts.brief(brief.prompt), maxTokens = 120))) }
                    intent = AiAssist.mergeBrief(brief, intent, answer)
                    if (answer != null) {
                        aiNotes += "On-device AI read your brief"
                        howMade += "${AgentId.CREATIVE_DIRECTOR.label}: read your brief on this phone"
                    }
                    record(AgentId.CREATIVE_DIRECTOR, RuntimeKind.ON_DEVICE, creativeStep.modelId, if (answer != null) com.cinemind.core.domain.agents.TaskStatus.SUCCEEDED else com.cinemind.core.domain.agents.TaskStatus.FAILED, briefStart, if (answer != null) "" else "reply not readable; rules used")
                }
                if (visualStep == null) throw SkipVision()
                val visionStart = System.currentTimeMillis()

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
                assets.forEach { a -> insights[a.id]?.let { remember(insightCache, cacheKey(a), it) } }
                record(AgentId.VISUAL_INTELLIGENCE, RuntimeKind.ON_DEVICE, visualStep.modelId, com.cinemind.core.domain.agents.TaskStatus.SUCCEEDED, visionStart, "${insights.values.sumOf { it.size }} moments")
                if (insights.isNotEmpty()) howMade += "${AgentId.VISUAL_INTELLIGENCE.label}: looked at ${insights.values.sumOf { it.size }} key moments on this phone"
            } catch (e: CancellationException) {
                throw e
            } catch (_: SkipVision) {
                // Seeing the shots wasn't needed for this request.
            } catch (e: Exception) {
                CineMindLogger.e(e, "On-device AI failed; using basic analysis")
                record(AgentId.VISUAL_INTELLIGENCE, RuntimeKind.ON_DEVICE, visualStep?.modelId, com.cinemind.core.domain.agents.TaskStatus.FAILED, System.currentTimeMillis(), e.message?.take(80).orEmpty())
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

        // ---- Editing planner: the engine's chosen runtime first, then its fallbacks in order
        // (cloud only with the user's own key and consent, text only; the rules always work).
        var engine = if (insights.isNotEmpty()) "On-device AI" else "On-device"
        var chosen: EditPlan = director
        run {
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
                var step = orchestration.step(AgentId.EDIT_PLANNER)
                var attempt = 0
                while (step != null) {
                    val current = step
                    val label = when (current.runtime) {
                        RuntimeKind.CLOUD -> cloud.engineName
                        RuntimeKind.ON_DEVICE -> "The on-phone planner"
                        RuntimeKind.DETERMINISTIC -> "The built-in director"
                    }
                    val planStart = System.currentTimeMillis()
                    attempt++
                    val plan: EditPlan? = try {
                        when (current.runtime) {
                            RuntimeKind.DETERMINISTIC -> director
                            RuntimeKind.CLOUD -> {
                                onProgress(SmartEditProgress.AskingCloud(cloud.engineName))
                                cloud.planEdit(request)
                            }
                            RuntimeKind.ON_DEVICE -> {
                                onProgress(SmartEditProgress.PlanningOnPhone)
                                planOnPhone(current.modelId, request)
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: CloudAiException) {
                        aiNotes += "$label couldn't plan this (${e.message}); tried the next option"
                        null
                    } catch (e: Exception) {
                        CineMindLogger.e(e, "Planner ${current.runtime} ${current.modelId} failed")
                        aiNotes += "$label failed (${e.message?.take(80)}); tried the next option"
                        null
                    }
                    if (plan != null && EditPlanValidator.validate(plan, sources, ground, hasCaptions = captions.isNotEmpty()).usable) {
                        record(AgentId.EDIT_PLANNER, current.runtime, current.modelId, com.cinemind.core.domain.agents.TaskStatus.SUCCEEDED, planStart, "", attempt)
                        chosen = plan
                        when (current.runtime) {
                            RuntimeKind.CLOUD -> { engine = cloud.engineName; howMade += "${AgentId.EDIT_PLANNER.label}: planned in the cloud (text summary only)" }
                            RuntimeKind.ON_DEVICE -> { engine = "On-device AI"; howMade += "${AgentId.EDIT_PLANNER.label}: thought the edit through on this phone" }
                            RuntimeKind.DETERMINISTIC -> howMade += "${AgentId.EDIT_PLANNER.label}: built-in creative director (no model)"
                        }
                        break
                    }
                    record(AgentId.EDIT_PLANNER, current.runtime, current.modelId, com.cinemind.core.domain.agents.TaskStatus.FAILED, planStart, if (plan == null) "no plan" else "plan had no usable shots", attempt)
                    if (plan != null) aiNotes += "$label's plan had no usable shots; tried the next option"
                    step = DecisionEngine.recover(current)
                }
        }
        if (orchestration.uses(AgentId.MUSIC_DIRECTOR)) {
            howMade += "${AgentId.MUSIC_DIRECTOR.label}: " + if (audio.music != null) "set music level and fades for your song" else "suggested a music mood (no song yet)"
        }
        if (orchestration.uses(AgentId.AUDIO_INTELLIGENCE)) {
            howMade += "${AgentId.AUDIO_INTELLIGENCE.label}: " + if (captions.isNotEmpty()) "kept your captions in time with the new cut" else "no transcript yet, so speech wasn't checked (add captions first)"
        }

        onProgress(SmartEditProgress.Building)
        val buildStart = System.currentTimeMillis()
        // A revision's hard limits (no slow motion, straight cuts, …) hold whatever the planner did.
        val fit = { p: EditPlan -> revision?.let { com.cinemind.core.domain.aiedit.RevisionReader.constrain(p, it) } ?: p }
        val validated = EditPlanValidator.validate(fit(chosen), sources, ground, hasCaptions = captions.isNotEmpty())
            .takeIf { it.usable } ?: EditPlanValidator.validate(fit(director), sources, ground, hasCaptions = captions.isNotEmpty())
        if (!validated.usable) throw SmartEditException("Couldn't build an edit from this footage.")
        val fixes = validated.steps.count { it.status != StepStatus.APPLIED }
        howMade += "${AgentId.QUALITY_ASSURANCE.label}: checked the plan against your real media" + if (fixes > 0) " ($fixes fixed or left out)" else ""

        val name = timeline.project.name.take(60) + if (engine == "On-device") " · Smart edit" else " · AI edit"
        val built = EditPlanExecutor.execute(validated, sources, timeline.project, name, timeline, captions)
        val newId = projectRepository.createProjectWithTimeline(built.timeline)
        val steps = built.steps.toMutableList()
        persistExtras(newId, built, audio, steps)
        runCatching { briefRepository.saveBrief(ownBrief.copy(projectId = newId)) }
        ReferenceStore.save(appContext, newId, references)
        if (references.isNotEmpty()) howMade += "Style references: followed the pace and look of " + references.joinToString(", ") { it.name }
        howMade += "${AgentId.RENDERING.label}: built a new, editable project (your original is unchanged)"
        orchestration.skipped.forEach { (agent, why) -> howMade += "Not used — ${agent.label}: $why" }
        record(AgentId.QUALITY_ASSURANCE, RuntimeKind.DETERMINISTIC, null, com.cinemind.core.domain.agents.TaskStatus.SUCCEEDED, buildStart, "$fixes fixed or left out")
        record(AgentId.RENDERING, RuntimeKind.DETERMINISTIC, null, com.cinemind.core.domain.agents.TaskStatus.SUCCEEDED, buildStart, "new project")

        // ---- Audio sub-workflow (run by the workflow engine): voice clean-up and transcription side
        // by side, then captions. Each agent decides for itself whether it is needed.
        val audioSteps = orchestration.steps.filter { it.agent in AUDIO_AGENTS }
        var transcript: List<com.cinemind.core.domain.captions.Caption>? = null
        if (audioSteps.isNotEmpty()) {
            val noisy = Regex("noise|noisy|wind|traffic|crowd|loud", RegexOption.IGNORE_CASE).containsMatchIn(brief.prompt)
            val runner = com.cinemind.core.domain.agents.WorkflowRunner(
                run = { task ->
                    when (task.agent) {
                        AgentId.AUDIO_ENHANCEMENT -> {
                            onProgress(SmartEditProgress.EnhancingAudio)
                            val level = if (noisy) com.cinemind.core.domain.audio.EnhanceLevel.STRONG else com.cinemind.core.domain.audio.EnhanceLevel.LIGHT
                            com.cinemind.feature.export.engine.VoiceEnhanceStore.set(appContext, newId, com.cinemind.core.domain.audio.VoiceEnhance(level))
                            synchronized(howMade) { howMade += "${AgentId.AUDIO_ENHANCEMENT.label}: ${level.label.lowercase()} noise removal on the voices (Audio → Enhance voice to change it)" }
                            com.cinemind.core.domain.agents.AgentOutcome.Done(level.name)
                        }
                        AgentId.AUDIO_INTELLIGENCE -> when {
                            Goal.CAPTIONS !in goals -> com.cinemind.core.domain.agents.AgentOutcome.Skip("no subtitles asked for")
                            captions.isNotEmpty() -> com.cinemind.core.domain.agents.AgentOutcome.Skip("kept your captions, re-timed to the new cut")
                            else -> {
                                onProgress(SmartEditProgress.Transcribing)
                                val result = transcriber.transcribe(
                                    newId, speechSources(built, assets),
                                    com.cinemind.core.domain.captions.SpokenLanguage.AUTO, com.cinemind.core.domain.captions.CaptionScript.ROMAN,
                                ) {}
                                transcript = result.captions
                                if (result.captions.isEmpty()) com.cinemind.core.domain.agents.AgentOutcome.Skip("no speech heard")
                                else com.cinemind.core.domain.agents.AgentOutcome.Done("${result.captions.size} captions in ${result.totalMs / 1000} s")
                            }
                        }
                        AgentId.CAPTIONS -> transcript?.takeIf { it.isNotEmpty() }?.let { list ->
                            captionRepository.saveCaptions(newId, list.map { it.copy(projectId = newId) })
                            if (built.captionPreset == null) captionRepository.saveStyle(CaptionPresets.REELS.applyTo(newId))
                            synchronized(howMade) { howMade += "${AgentId.CAPTIONS.label}: ${list.size} subtitles from the speech, timed to the new cut" }
                            com.cinemind.core.domain.agents.AgentOutcome.Done("${list.size} captions")
                        } ?: com.cinemind.core.domain.agents.AgentOutcome.Skip("nothing new to caption")
                        else -> com.cinemind.core.domain.agents.AgentOutcome.Done()
                    }
                },
                memoryMb = ::availMemMb,
            )
            runCatching { trace += runner.execute(orchestration.copy(steps = audioSteps)) }
                .onFailure { if (it is CancellationException) throw it; CineMindLogger.w("Audio workflow failed: ${it.message}") }
        }
        runCatching { speechAwareText(newId, built, assets, transcript, ownBrief.prompt, onProgress, howMade) }
            .onFailure { if (it is CancellationException) throw it; CineMindLogger.w("Speech-aware text failed: ${it.message}") }
        com.cinemind.feature.ai.llm.ondevice.TraceStore.save(
            appContext,
            com.cinemind.core.domain.agents.ExecutionTrace(
                request = (if (revision != null) "Revision: " else "") + brief.prompt.take(120),
                startedMs = traceStart,
                planned = orchestration.explain(),
                entries = trace,
            ),
        )

        CineMindLogger.d("Smart edit $newId by $engine: " + steps.joinToString(" | ") { "${it.status} ${it.area}: ${it.text}" })
        return SmartEditResult(
            projectId = newId,
            decisions = listOf(validated.plan.summary).filter { it.isNotBlank() } + validated.plan.decisions + selection.decisions.take(1) + aiNotes,
            usedAi = engine != "On-device",
            engine = engine,
            summary = validated.plan.summary,
            steps = steps,
            questions = validated.plan.questions,
            howMade = howMade,
        )
    }

    /** What the brief asks for beyond a new edit (clear voices, subtitles). */
    private fun goalsOf(prompt: String): Set<Goal> = buildSet {
        add(Goal.NEW_EDIT)
        val p = prompt.lowercase(Locale.ROOT)
        if (listOf("clear voice", "clean voice", "clearer voice", "voice clear", "clean audio", "clear audio", "noise", "crisp audio", "clean sound", "clear sound").any { it in p }) add(Goal.CLEAN_AUDIO)
        if (listOf("subtitle", "caption").any { it in p }) add(Goal.CAPTIONS)
    }

    private fun availMemMb(): Long = runCatching {
        val info = android.app.ActivityManager.MemoryInfo()
        appContext.getSystemService(android.app.ActivityManager::class.java)?.getMemoryInfo(info)
        info.availMem / (1024 * 1024)
    }.getOrDefault(-1)

    /** The new edit's video clips as speech sources (trimmed range, mapped back to the timeline). */
    private fun speechSources(built: BuiltEdit, assets: List<MediaAsset>): List<com.cinemind.feature.captions.speech.SpeechSource> {
        val video = built.timeline.tracks.firstOrNull { it.type == TrackType.VIDEO } ?: return emptyList()
        val byId = assets.associateBy { it.id }
        return built.timeline.clips.filter { it.trackId == video.id }.sortedBy { it.timelineInUs }.mapNotNull { c ->
            val a = byId[c.assetId]?.takeIf { it.isVideo && it.hasAudio } ?: return@mapNotNull null
            com.cinemind.feature.captions.speech.SpeechSource(a.uri, c.sourceInUs, c.sourceOutUs, c.timelineInUs) { off -> com.cinemind.core.domain.editing.SpeedMath.sourceToTimeline(c, off) }
        }
    }

    /**
     * Text designed from what is said (talking videos): the speech is transcribed when there are no
     * captions yet (Whisper), the speaker is cut out so the hook title can sit behind them, and
     * keyword callouts pop up beside the speaker as each key word is said. The automatic opening
     * title is replaced by the hook. Nothing happens for footage without speech.
     */
    private suspend fun speechAwareText(
        newId: String,
        built: BuiltEdit,
        assets: List<MediaAsset>,
        transcript: List<com.cinemind.core.domain.captions.Caption>?,
        prompt: String,
        onProgress: (SmartEditProgress) -> Unit,
        howMade: MutableList<String>,
    ) {
        val sources = speechSources(built, assets)
        if (sources.isEmpty()) return
        var spoken = transcript.orEmpty().ifEmpty { built.captions }
        if (spoken.isEmpty() && llm.speechModel() != null) {
            onProgress(SmartEditProgress.Transcribing)
            spoken = runCatching {
                transcriber.transcribe(newId, sources, com.cinemind.core.domain.captions.SpokenLanguage.AUTO, com.cinemind.core.domain.captions.CaptionScript.ROMAN) {}.captions
            }.getOrDefault(emptyList())
        }
        if (spoken.isEmpty()) return

        // Cut out the speaker (main-track videos; an existing cutout is reused).
        val video = built.timeline.tracks.firstOrNull { it.type == TrackType.VIDEO }
        val speakerAssets = built.timeline.clips.filter { it.trackId == video?.id }.mapNotNull { c -> assets.firstOrNull { it.id == c.assetId && it.isVideo } }.distinctBy { it.id }.take(3)
        onProgress(SmartEditProgress.CuttingOut)
        val maker = com.cinemind.feature.editor.timeline.matte.MatteMaker(appContext)
        val cutOut = speakerAssets.isNotEmpty() && speakerAssets.all { a ->
            com.cinemind.feature.export.engine.MatteStore.existing(appContext, a.id) != null ||
                runCatching { maker.make(a) {} }.onFailure { if (it is CancellationException) throw it; CineMindLogger.w("Cutout failed: ${it.message}") }.isSuccess
        }

        val total = built.timeline.project.durationUs
        val graphics = com.cinemind.core.domain.aiedit.SpeechGraphics.plan(newId, spoken, total, prompt, behindSpeaker = cutOut, idFor = { java.util.UUID.randomUUID().toString() })
        if (graphics.isEmpty()) return
        val hook = graphics.firstOrNull { it.startUs == 0L && it.styleObj.behindSubject == cutOut && it.size >= 0.12f }
        val existing = textRepository.get(newId)
        // The hook replaces the automatic opening title (it said the project name, not what you said).
        val kept = if (hook == null) existing else existing.filterNot { it.kindEnum == com.cinemind.core.domain.text.TextKind.TEXT && it.startUs < hook.endUs }
        textRepository.save(newId, kept + graphics)
        // A talking video gets bold, readable captions low in the frame, clear of the callouts.
        if (built.captionPreset == null && (transcript.orEmpty().isNotEmpty() || built.captions.isNotEmpty())) {
            runCatching { captionRepository.saveStyle(CaptionPresets.HYPE.applyTo(newId)) }
        }
        val callouts = graphics.size - (if (hook != null) 1 else 0)
        synchronized(howMade) {
            howMade += "Speech-aware text: " + listOfNotNull(
                hook?.let { "hook “${it.text}”" + if (cutOut) " behind you" else "" },
                if (callouts > 0) "$callouts key words pop up as you say them" else null,
            ).joinToString(", ")
        }
    }

    private class SkipVision : Exception()

    /**
     * The edit plan from the on-phone planning model (Spark, or Gemma when no planner is
     * installed). The footage report is shortened to fit a phone-sized context window; the reply
     * is validated by the caller exactly like a cloud plan. The model is freed afterwards.
     */
    private suspend fun planOnPhone(modelId: String?, request: PlanRequest): EditPlan? {
        val model = runtime.model(modelId) ?: return null
        val compact = request.copy(
            sources = request.sources.map { s ->
                val every = (s.moments.size / 8).coerceAtLeast(1)
                s.copy(moments = s.moments.filterIndexed { i, _ -> i % every == 0 }.take(8))
            }.take(12),
            speech = request.speech.take(1_200),
        )
        return try {
            val text = model.infer(
                ModelRequest(
                    prompt = EditPlanJson.userPrompt(compact),
                    system = EditPlanJson.systemPrompt,
                    schema = EditPlanJson.schema,
                    maxTokens = 2_000,
                ),
            )
            EditPlanJson.parse(text)
        } finally {
            withContext(NonCancellable) { llm.unload() }
        }
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
        val AUDIO_AGENTS = setOf(AgentId.AUDIO_ENHANCEMENT, AgentId.AUDIO_INTELLIGENCE, AgentId.CAPTIONS)
        private const val CACHE_SIZE = 40

        // Per-file results kept while the app runs, so revisions don't measure or look again.
        val analysisCache = LinkedHashMap<String, AssetAnalysis>()
        val insightCache = LinkedHashMap<String, List<TimedInsight>>()

        fun cacheKey(a: MediaAsset) = "${a.id}:${a.sizeBytes}:${a.durationUs}"

        fun <V> remember(cache: LinkedHashMap<String, V>, key: String, value: V) = synchronized(cache) {
            cache[key] = value
            while (cache.size > CACHE_SIZE) cache.remove(cache.keys.first())
        }
    }
}
