package com.cinemind.core.domain.agents

import com.cinemind.core.domain.aigen.AiMode

/** What the user wants, as structured data (read by a model when one is available, else by rules). */
enum class Goal {
    /** A new edit from the project's footage. */
    NEW_EDIT,
    /** Change an existing edit ("make it 30 seconds", "smoother transitions"). */
    REVISE_EDIT,
    /** A plain timeline operation (trim, reverse, split, speed): no AI at all. */
    DIRECT_OPERATION,
    FIND_BEST_SCENES,
    SYNC_TO_MUSIC,
    CAPTIONS,
    /** Clearer voices: noise reduction and levelling of the clips' sound. */
    CLEAN_AUDIO,
}

/** Parts of an edit a revision may change or must keep. */
enum class Aspect { SHOTS, DURATION, PACING, SPEED, TRANSITIONS, LOOK, MUSIC, CAPTIONS, TITLES }

data class RequestIntent(
    val goals: Set<Goal>,
    /** The request is about mood, style or story (needs creative direction). */
    val creative: Boolean = false,
    val change: Set<Aspect> = emptySet(),
    /** "Keep the same music": these are never redone until the user says otherwise. */
    val keep: Set<Aspect> = emptySet(),
    val targetSeconds: Int? = null,
)

/** Facts about the project (measured, never guessed by a model). */
data class ProjectContext(
    val clipCount: Int,
    val videoCount: Int,
    val totalSeconds: Double,
    /** null = not measured yet. */
    val hasSpeech: Boolean? = null,
    val hasMusic: Boolean = false,
    /** Agent outputs already validated in this project (reused by revisions). */
    val available: Set<DataKind> = emptySet(),
)

/** User settings that bound where work may run. */
data class Policy(
    val mode: AiMode,
    /** The user agreed to send text summaries to their cloud service. */
    val cloudConsent: Boolean,
    /** Optional AI agents may be skipped (e.g. "Use AI in Smart edit" off). */
    val useOnDeviceAi: Boolean = true,
    /** Agents switched off in the developer diagnostics. */
    val disabled: Set<AgentId> = emptySet(),
)

/** One agent in the plan, with where it runs and what to try if that fails. */
data class PlannedStep(
    val agent: AgentId,
    val stage: Int,
    val runtime: RuntimeKind,
    /** Model id, or null for deterministic code. */
    val modelId: String?,
    /** Next options, in order, if this one fails ("deterministic" last when the agent has it). */
    val fallbacks: List<Pair<RuntimeKind, String?>>,
    val reason: String,
)

data class ExecutionPlan(
    val steps: List<PlannedStep>,
    val skipped: Map<AgentId, String>,
    val capabilities: Set<Capability>,
    /** Data reused from earlier work instead of being recomputed. */
    val reused: Set<DataKind>,
) {
    val agents: Set<AgentId> get() = steps.map { it.agent }.toSet()
    val stages: List<List<PlannedStep>> get() = steps.groupBy { it.stage }.toSortedMap().values.toList()
    fun step(id: AgentId): PlannedStep? = steps.firstOrNull { it.agent == id }
    fun uses(id: AgentId): Boolean = steps.any { it.agent == id }

    /** Steps of a stage that may run at the same time: at most one on-device model at once. */
    fun parallelGroups(stage: List<PlannedStep>): List<List<PlannedStep>> {
        val (model, other) = stage.partition { it.runtime == RuntimeKind.ON_DEVICE }
        if (model.isEmpty()) return listOf(other)
        return listOf(other + model.first()) + model.drop(1).map { listOf(it) }
    }

    /** Plain-language summary for the report and diagnostics. */
    fun explain(): List<String> = steps.map { s ->
        "Stage ${s.stage + 1} · ${s.agent.label}: " + when (s.runtime) {
            RuntimeKind.DETERMINISTIC -> "on this phone (no AI model)"
            RuntimeKind.ON_DEVICE -> "on this phone (${s.modelId})"
            RuntimeKind.CLOUD -> "cloud (${s.modelId})"
        } + " — ${s.reason}"
    } + skipped.map { (a, why) -> "Skipped ${a.label}: $why" }
}

/**
 * CineMind Decision Engine. From what the user wants, what the project contains, which models are
 * available, the phone's resources and the user's privacy choices, it decides which agents are
 * needed (and only those), where each runs, what can run together and what to try when something
 * fails. Pure and deterministic: the same inputs always give the same, explainable plan.
 */
object DecisionEngine {

    fun plan(
        intent: RequestIntent,
        context: ProjectContext,
        models: List<ModelDescriptor>,
        budget: ResourceBudget,
        policy: Policy,
    ): ExecutionPlan {
        val skipped = linkedMapOf<AgentId, String>()
        val caps = requiredCapabilities(intent, context, skipped)
        val reused = reusable(intent, context)

        // Capabilities → agents, then pull in the producers of everything they require.
        val chosen = linkedMapOf<AgentId, AgentSpec>()
        caps.forEach { c -> AgentRegistry.forCapability(c)?.let { a -> if (a.id in policy.disabled) skipped[a.id] = "switched off in developer settings" else chosen[a.id] = a } }
        var grew = true
        while (grew) {
            grew = false
            for (a in chosen.values.toList()) for (d in a.requires) {
                if (d == DataKind.USER_REQUEST || d == DataKind.PROJECT_MEDIA || d in reused) continue
                val p = AgentRegistry.producerOf(d) ?: continue
                if (p.id in policy.disabled) { skipped[p.id] = "switched off in developer settings"; continue }
                if (p.id !in chosen && p.id !in skipped) { chosen[p.id] = p; grew = true }
            }
        }

        // Where each agent runs.
        val placed = linkedMapOf<AgentId, Triple<RuntimeKind, String?, List<Pair<RuntimeKind, String?>>>>()
        val reasons = mutableMapOf<AgentId, String>()
        for (a in chosen.values) {
            val options = options(a, models, budget, policy)
            if (options.isEmpty()) {
                skipped[a.id] = when {
                    !policy.useOnDeviceAi && a.optional -> "AI help is turned off in settings"
                    budget.optionalAiAllowed.not() && a.optional -> budget.reason
                    else -> "no model on this phone can do it" + if (policy.mode == AiMode.OFFLINE) " (Offline mode)" else ""
                }
                continue
            }
            val (kind, id) = options.first()
            placed[a.id] = Triple(kind, id, options.drop(1))
            reasons[a.id] = reasonFor(a, kind, intent, context)
        }
        // A required agent that can't run makes the agents that depend on it pointless.
        placed.keys.toList().forEach { id ->
            val a = AgentRegistry.byId(id)
            val missing = a.requires.filter { it != DataKind.USER_REQUEST && it != DataKind.PROJECT_MEDIA && it !in reused }
                .filter { d -> AgentRegistry.producerOf(d)?.id?.let { it !in placed } ?: true }
            if (missing.isNotEmpty()) {
                placed.remove(id)
                skipped[id] = "needs ${missing.joinToString { it.name.lowercase().replace('_', ' ') }}, which isn't available"
            }
        }

        // Stages: an agent runs after every placed producer of what it requires or uses.
        val stage = mutableMapOf<AgentId, Int>()
        fun stageOf(id: AgentId, depth: Int = 0): Int = stage[id] ?: run {
            require(depth < 20) { "Agent dependencies form a loop" }
            val a = AgentRegistry.byId(id)
            val deps = (a.requires + a.uses).mapNotNull { d -> AgentRegistry.producerOf(d)?.id?.takeIf { it in placed && it != id && d !in reused } }
            val s = if (deps.isEmpty()) 0 else deps.maxOf { stageOf(it, depth + 1) } + 1
            stage[id] = s
            s
        }
        val steps = placed.map { (id, p) ->
            PlannedStep(id, stageOf(id), p.first, p.second, p.third, reasons.getValue(id))
        }.sortedWith(compareBy({ it.stage }, { it.agent.ordinal }))

        return ExecutionPlan(steps, skipped, caps, reused)
    }

    /** What the request actually needs, given the project. */
    internal fun requiredCapabilities(intent: RequestIntent, ctx: ProjectContext, skipped: MutableMap<AgentId, String>): Set<Capability> {
        val c = linkedSetOf<Capability>()
        val g = intent.goals
        if (Goal.DIRECT_OPERATION in g && g.size == 1) return setOf(Capability.TIMELINE_OPERATION)
        if (Goal.NEW_EDIT in g) {
            c += listOf(Capability.VIDEO_ANALYSIS, Capability.EDIT_PLANNING, Capability.QUALITY_CHECK, Capability.RENDERING)
            if (intent.creative) c += Capability.CREATIVE_DIRECTION
            // Seeing the shots matters when there is a choice to make or a story to tell.
            if (ctx.clipCount >= 2 && (intent.creative || ctx.totalSeconds > 20)) c += Capability.VISUAL_UNDERSTANDING
            // Speech decides where cuts may fall; only worth checking when there is video sound.
            if (ctx.videoCount > 0 && ctx.hasSpeech != false) c += Capability.AUDIO_ANALYSIS
            if (ctx.hasMusic) c += Capability.BEAT_ANALYSIS
        }
        if (Goal.FIND_BEST_SCENES in g) c += listOf(Capability.VIDEO_ANALYSIS, Capability.VISUAL_UNDERSTANDING)
        if (Goal.SYNC_TO_MUSIC in g) {
            if (ctx.hasMusic) c += listOf(Capability.BEAT_ANALYSIS, Capability.EDIT_PLANNING, Capability.QUALITY_CHECK, Capability.RENDERING)
            else c += Capability.MUSIC_DIRECTION // nothing to sync to yet: suggest music instead
        }
        if (Goal.CAPTIONS in g) {
            if (ctx.hasSpeech == false) skipped[AgentId.CAPTIONS] = "no speech was found in this project"
            else c += listOf(Capability.SPEECH_TRANSCRIPTION, Capability.CAPTIONING)
        }
        if (Goal.CLEAN_AUDIO in g) {
            if (ctx.videoCount == 0) skipped[AgentId.AUDIO_ENHANCEMENT] = "the project has no clips with sound"
            else c += listOf(Capability.AUDIO_ENHANCEMENT, Capability.RENDERING)
        }
        if (Goal.REVISE_EDIT in g) c += RevisionPlanner.capabilitiesFor(intent)
        if (Goal.DIRECT_OPERATION in g) c += Capability.TIMELINE_OPERATION
        if (c.isEmpty()) c += Capability.UNDERSTAND_REQUEST
        // An explicit "keep the music" removes music work even if another goal asked for it.
        if (Aspect.MUSIC in intent.keep) { c -= Capability.MUSIC_DIRECTION }
        return c
    }

    /** Validated earlier results a revision may reuse instead of recomputing. */
    internal fun reusable(intent: RequestIntent, ctx: ProjectContext): Set<DataKind> {
        if (Goal.REVISE_EDIT !in intent.goals) return emptySet()
        return ctx.available - RevisionPlanner.invalidated(intent)
    }

    /** Runtime options for [a], best first; empty when it can't run at all. */
    private fun options(a: AgentSpec, models: List<ModelDescriptor>, budget: ResourceBudget, policy: Policy): List<Pair<RuntimeKind, String?>> {
        if (a.deterministic) return listOf(RuntimeKind.DETERMINISTIC to null)
        val local = if (!policy.useOnDeviceAi || !budget.localModelsAllowed) emptyList() else models
            .filter { it.kind == RuntimeKind.ON_DEVICE && it.canDo(a.needs) && it.memoryMb <= budget.maxModelMb }
            .sortedWith(compareByDescending<ModelDescriptor> { score(it, a) }.thenBy { it.memoryMb })
        val cloud = if (!a.cloudSafe || policy.mode == AiMode.OFFLINE || !policy.cloudConsent) emptyList() else models
            .filter { it.kind == RuntimeKind.CLOUD && it.canDo(a.needs) }
            .sortedByDescending { it.quality }
        val ordered = mutableListOf<Pair<RuntimeKind, String?>>()
        val bestLocal = local.firstOrNull()
        // Cloud first when the user asked for it, or when nothing local is good enough for this job.
        val cloudFirst = cloud.isNotEmpty() && (policy.mode == AiMode.CLOUD || bestLocal == null || (a.outputTokens >= 1_000 && bestLocal.quality < 3))
        if (cloudFirst) ordered += cloud.map { RuntimeKind.CLOUD to it.id }
        ordered += local.map { RuntimeKind.ON_DEVICE to it.id }
        if (!cloudFirst) ordered += cloud.map { RuntimeKind.CLOUD to it.id }
        if (a.deterministicFallback) ordered += RuntimeKind.DETERMINISTIC to null
        if (a.optional && ordered.none { it.first != RuntimeKind.DETERMINISTIC } && !a.deterministicFallback) return emptyList()
        if (a.optional && !budget.optionalAiAllowed) return if (a.deterministicFallback) listOf(RuntimeKind.DETERMINISTIC to null) else emptyList()
        return ordered
    }

    /** Quality first, then speed for the expected output size (a slow model on a long plan loses). */
    private fun score(m: ModelDescriptor, a: AgentSpec): Float {
        val seconds = if (m.tokensPerSecond > 0f) a.outputTokens / m.tokensPerSecond else 999f
        // Every 10 s of waiting costs a quality point: a slow model only wins when much better.
        return m.quality * 10f - (seconds / 10f).coerceAtMost(40f)
    }

    private fun reasonFor(a: AgentSpec, kind: RuntimeKind, intent: RequestIntent, ctx: ProjectContext): String = when (a.id) {
        AgentId.CREATIVE_DIRECTOR -> "the request is about mood and style"
        AgentId.VISUAL_INTELLIGENCE -> "choosing between shots needs to see them"
        AgentId.VIDEO_ANALYSIS -> "measures every clip (sharpness, motion, exposure, faces)"
        AgentId.AUDIO_INTELLIGENCE -> if (Goal.CAPTIONS in intent.goals) "captions need the words" else "keeps cuts out of the middle of speech"
        AgentId.AUDIO_ENHANCEMENT -> "cleans up the voices (noise reduction, levelling) without touching the originals"
        AgentId.MUSIC_DIRECTOR -> if (ctx.hasMusic) "places cuts and fades against the music" else "no music yet: suggests what to look for"
        AgentId.EDIT_PLANNER -> "turns the findings into a structured edit plan"
        AgentId.CAPTIONS -> "writes timed captions from the speech"
        AgentId.QUALITY_ASSURANCE -> "checks the plan against the real media before anything changes"
        AgentId.RENDERING -> "applies the checked plan to the timeline"
    } + if (kind == RuntimeKind.DETERMINISTIC && a.needs.isNotEmpty()) " (simplified: no suitable model)" else ""

    /**
     * After a failure of [failed]: the next option to try, or null when the agent should be
     * skipped (optional) or the request stopped (required).
     */
    fun recover(failed: PlannedStep): PlannedStep? {
        val next = failed.fallbacks.firstOrNull() ?: return null
        return failed.copy(runtime = next.first, modelId = next.second, fallbacks = failed.fallbacks.drop(1), reason = failed.reason + " · retried after a failure")
    }
}

/** Which parts of an edit a follow-up changes, so only the affected agents run again. */
object RevisionPlanner {

    fun capabilitiesFor(intent: RequestIntent): Set<Capability> {
        val c = linkedSetOf<Capability>()
        val changes = intent.change - intent.keep
        if (changes.any { it in setOf(Aspect.SHOTS, Aspect.DURATION, Aspect.PACING, Aspect.SPEED, Aspect.TRANSITIONS, Aspect.LOOK, Aspect.TITLES) } || intent.targetSeconds != null) {
            c += listOf(Capability.EDIT_PLANNING, Capability.QUALITY_CHECK, Capability.RENDERING)
        }
        if (Aspect.LOOK in changes || intent.creative) c += Capability.CREATIVE_DIRECTION
        if (Aspect.SHOTS in changes) c += Capability.VISUAL_UNDERSTANDING
        if (Aspect.MUSIC in changes) c += listOf(Capability.MUSIC_DIRECTION, Capability.RENDERING)
        if (Aspect.CAPTIONS in changes) c += listOf(Capability.CAPTIONING, Capability.RENDERING)
        if (c.isEmpty()) c += listOf(Capability.EDIT_PLANNING, Capability.QUALITY_CHECK, Capability.RENDERING)
        return c
    }

    /** Earlier results a revision makes stale (everything else is reused). */
    fun invalidated(intent: RequestIntent): Set<DataKind> {
        val out = mutableSetOf(DataKind.EDIT_PLAN, DataKind.QA_REPORT, DataKind.TIMELINE)
        val changes = intent.change - intent.keep
        if (Aspect.LOOK in changes || intent.creative) out += DataKind.CREATIVE_BRIEF
        if (Aspect.MUSIC in changes) out += DataKind.MUSIC_PLAN
        if (Aspect.CAPTIONS in changes) out += DataKind.CAPTIONS
        return out
    }
}
