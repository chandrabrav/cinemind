package com.cinemind.core.domain.agents

/**
 * Contracts of the CineMind multi-agent layer: what an agent can do, what it consumes and
 * produces, and what a model / runtime offers. Agents never name a model; the decision engine
 * matches their needs to whatever the model registry has available at that moment.
 */

/** Something the editor must be able to do to fulfil a request. */
enum class Capability {
    UNDERSTAND_REQUEST,
    CREATIVE_DIRECTION,
    VISUAL_UNDERSTANDING,
    VIDEO_ANALYSIS,
    AUDIO_ANALYSIS,
    AUDIO_ENHANCEMENT,
    SPEECH_TRANSCRIPTION,
    BEAT_ANALYSIS,
    MUSIC_DIRECTION,
    EDIT_PLANNING,
    CAPTIONING,
    QUALITY_CHECK,
    RENDERING,
    TIMELINE_OPERATION,
}

/** Typed data passed between agents (never free-form text). */
enum class DataKind {
    USER_REQUEST,
    PROJECT_MEDIA,
    CREATIVE_BRIEF,
    FOOTAGE_REPORT,
    SHOT_DESCRIPTIONS,
    AUDIO_PROFILE,
    /** The clip sound with voice clean-up applied (non-destructive: a setting, originals untouched). */
    CLEAN_AUDIO,
    TRANSCRIPT,
    BEAT_GRID,
    MUSIC_PLAN,
    EDIT_PLAN,
    CAPTIONS,
    QA_REPORT,
    TIMELINE,
}

/** Allow-listed, typed operations agents may use. Nothing else: no shell, no free file access. */
enum class Tool {
    BRIEF_READER,
    FRAME_SAMPLER,
    FRAME_METRICS,
    AUDIO_DECODER,
    SPEECH_DETECTOR,
    SPEECH_RECOGNISER,
    VOICE_ENHANCER,
    BEAT_DETECTOR,
    PLAN_VALIDATOR,
    TIMELINE_COMPILER,
    RENDERER,
}

/** What a model can do. Agents ask for skills, never for a particular model. */
enum class ModelSkill { TEXT_REASONING, STRUCTURED_OUTPUT, VISION, AUDIO, /** Speech to timed text (Whisper; Gemma's audio encoder too). */ TRANSCRIPTION }

enum class RuntimeKind { DETERMINISTIC, ON_DEVICE, CLOUD }

enum class AgentId(val label: String) {
    CREATIVE_DIRECTOR("Creative director"),
    VISUAL_INTELLIGENCE("Visual intelligence"),
    VIDEO_ANALYSIS("Video analysis"),
    AUDIO_INTELLIGENCE("Speech (transcription)"),
    AUDIO_ENHANCEMENT("Voice clean-up"),
    MUSIC_DIRECTOR("Music director"),
    EDIT_PLANNER("Editing planner"),
    CAPTIONS("Captions & subtitles"),
    QUALITY_ASSURANCE("Quality check"),
    RENDERING("Timeline & rendering"),
}

/**
 * One agent's declaration.
 * @param requires inputs it cannot work without; @param uses inputs that improve it when present.
 * @param needs model skills it wants; empty = always deterministic.
 * @param deterministicFallback it still works (more simply) with no model at all.
 * @param cloudSafe it may run in the cloud: its inputs are text / measurements, never media.
 * @param optional the request can succeed without it (it is then skipped, with a reason).
 */
data class AgentSpec(
    val id: AgentId,
    val capabilities: Set<Capability>,
    val requires: Set<DataKind>,
    val uses: Set<DataKind> = emptySet(),
    val produces: DataKind,
    val tools: Set<Tool>,
    val needs: Set<ModelSkill> = emptySet(),
    val deterministicFallback: Boolean = true,
    val cloudSafe: Boolean = false,
    val optional: Boolean = false,
    /** Rough output size when a model is used, for latency estimates. */
    val outputTokens: Int = 0,
    /** Bumped when the agent's contract changes (shown in diagnostics). */
    val version: Int = 1,
) {
    val deterministic: Boolean get() = needs.isEmpty()
}

/** The agents CineMind has, each backed by existing, tested code (see docs/AI_ORCHESTRATION.md). */
object AgentRegistry {

    val CREATIVE_DIRECTOR = AgentSpec(
        AgentId.CREATIVE_DIRECTOR, setOf(Capability.UNDERSTAND_REQUEST, Capability.CREATIVE_DIRECTION),
        requires = setOf(DataKind.USER_REQUEST), produces = DataKind.CREATIVE_BRIEF,
        tools = setOf(Tool.BRIEF_READER), needs = setOf(ModelSkill.TEXT_REASONING, ModelSkill.STRUCTURED_OUTPUT),
        deterministicFallback = true, cloudSafe = true, outputTokens = 120,
    )
    val VISUAL_INTELLIGENCE = AgentSpec(
        AgentId.VISUAL_INTELLIGENCE, setOf(Capability.VISUAL_UNDERSTANDING),
        requires = setOf(DataKind.PROJECT_MEDIA, DataKind.FOOTAGE_REPORT), produces = DataKind.SHOT_DESCRIPTIONS,
        tools = setOf(Tool.FRAME_SAMPLER), needs = setOf(ModelSkill.VISION),
        deterministicFallback = false, cloudSafe = false, optional = true, outputTokens = 160,
    )
    val VIDEO_ANALYSIS = AgentSpec(
        AgentId.VIDEO_ANALYSIS, setOf(Capability.VIDEO_ANALYSIS),
        requires = setOf(DataKind.PROJECT_MEDIA), produces = DataKind.FOOTAGE_REPORT,
        tools = setOf(Tool.FRAME_SAMPLER, Tool.FRAME_METRICS),
    )
    val AUDIO_INTELLIGENCE = AgentSpec(
        AgentId.AUDIO_INTELLIGENCE, setOf(Capability.AUDIO_ANALYSIS, Capability.SPEECH_TRANSCRIPTION),
        requires = setOf(DataKind.PROJECT_MEDIA), produces = DataKind.TRANSCRIPT,
        tools = setOf(Tool.AUDIO_DECODER, Tool.SPEECH_DETECTOR, Tool.SPEECH_RECOGNISER), needs = setOf(ModelSkill.TRANSCRIPTION),
        deterministicFallback = true, cloudSafe = false, optional = true, outputTokens = 256, version = 2,
    )
    /** CineMind's on-device voice enhancer (DSP: low cut, spectral noise reduction, presence, leveller). */
    val AUDIO_ENHANCEMENT = AgentSpec(
        AgentId.AUDIO_ENHANCEMENT, setOf(Capability.AUDIO_ENHANCEMENT),
        requires = setOf(DataKind.PROJECT_MEDIA), produces = DataKind.CLEAN_AUDIO,
        tools = setOf(Tool.AUDIO_DECODER, Tool.VOICE_ENHANCER),
    )
    val MUSIC_DIRECTOR = AgentSpec(
        AgentId.MUSIC_DIRECTOR, setOf(Capability.BEAT_ANALYSIS, Capability.MUSIC_DIRECTION),
        requires = setOf(DataKind.PROJECT_MEDIA), uses = setOf(DataKind.CREATIVE_BRIEF), produces = DataKind.MUSIC_PLAN,
        tools = setOf(Tool.AUDIO_DECODER, Tool.BEAT_DETECTOR),
    )
    val EDIT_PLANNER = AgentSpec(
        AgentId.EDIT_PLANNER, setOf(Capability.EDIT_PLANNING),
        requires = setOf(DataKind.FOOTAGE_REPORT),
        uses = setOf(DataKind.CREATIVE_BRIEF, DataKind.SHOT_DESCRIPTIONS, DataKind.TRANSCRIPT, DataKind.MUSIC_PLAN),
        produces = DataKind.EDIT_PLAN, tools = setOf(Tool.PLAN_VALIDATOR),
        needs = setOf(ModelSkill.TEXT_REASONING, ModelSkill.STRUCTURED_OUTPUT),
        deterministicFallback = true, cloudSafe = true, outputTokens = 1_500,
    )
    val CAPTIONS = AgentSpec(
        AgentId.CAPTIONS, setOf(Capability.CAPTIONING),
        requires = setOf(DataKind.TRANSCRIPT), uses = setOf(DataKind.CREATIVE_BRIEF), produces = DataKind.CAPTIONS,
        tools = setOf(Tool.SPEECH_RECOGNISER),
    )
    val QUALITY_ASSURANCE = AgentSpec(
        AgentId.QUALITY_ASSURANCE, setOf(Capability.QUALITY_CHECK),
        requires = setOf(DataKind.EDIT_PLAN), produces = DataKind.QA_REPORT, tools = setOf(Tool.PLAN_VALIDATOR),
    )
    val RENDERING = AgentSpec(
        AgentId.RENDERING, setOf(Capability.RENDERING, Capability.TIMELINE_OPERATION),
        requires = emptySet(), uses = setOf(DataKind.EDIT_PLAN, DataKind.QA_REPORT, DataKind.CAPTIONS, DataKind.CLEAN_AUDIO),
        produces = DataKind.TIMELINE, tools = setOf(Tool.TIMELINE_COMPILER, Tool.RENDERER),
    )

    val ALL: List<AgentSpec> = listOf(
        CREATIVE_DIRECTOR, VISUAL_INTELLIGENCE, VIDEO_ANALYSIS, AUDIO_INTELLIGENCE, AUDIO_ENHANCEMENT, MUSIC_DIRECTOR,
        EDIT_PLANNER, CAPTIONS, QUALITY_ASSURANCE, RENDERING,
    )

    fun byId(id: AgentId): AgentSpec = ALL.first { it.id == id }

    /** The agent that provides [c] (one per capability; extend by adding agents, not branches). */
    fun forCapability(c: Capability): AgentSpec? = ALL.firstOrNull { c in it.capabilities }

    fun producerOf(kind: DataKind): AgentSpec? = ALL.firstOrNull { it.produces == kind }
}

/**
 * A model or runtime the engine may use, as the model registry reports it right now.
 * [quality] 1..5 is the expected plan quality for this app (from the benchmark where measured).
 */
data class ModelDescriptor(
    val id: String,
    val kind: RuntimeKind,
    val skills: Set<ModelSkill>,
    val memoryMb: Int,
    val tokensPerSecond: Float,
    val quality: Int,
    val available: Boolean,
) {
    fun canDo(needs: Set<ModelSkill>) = available && skills.containsAll(needs)
}

/** One structured request to any model (local or cloud). */
data class ModelRequest(
    val prompt: String,
    val system: String = "",
    /** JSON schema the reply must follow, or null for plain text. */
    val schema: Map<String, Any>? = null,
    /** On-device only: media never goes to the cloud through this contract. */
    val imagePath: String? = null,
    val audioPath: String? = null,
    val maxTokens: Int = 512,
    /** Think longer when the runtime supports it. */
    val deep: Boolean = false,
)

/**
 * The adapter every model sits behind (Gemma, Spark, cloud services), so agents and the decision
 * engine never depend on a particular model. Loading is explicit so the runtime can keep only one
 * large model in memory at a time.
 */
interface CineMindModel {
    val descriptor: ModelDescriptor
    suspend fun load()
    suspend fun infer(request: ModelRequest): String
    suspend fun unload()
}
