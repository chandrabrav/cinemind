package com.cinemind.core.domain.aigen

/**
 * How far CineMind may reach beyond the phone. Chosen by the user in Cloud AI settings.
 * HYBRID is the default and matches how the app behaved before modes existed.
 */
enum class AiMode(val label: String, val note: String) {
    OFFLINE("Offline", "Everything stays on this phone. Cloud planning and sending to other apps are off."),
    HYBRID("Hybrid", "On-device first. Cloud AI refines the plan when you've added a key and allowed it."),
    CLOUD("Cloud-assisted", "Cloud AI thinks longer about every plan: better ideas, slower, costs more. The phone is the fallback."),
    ;

    companion object {
        fun from(name: String?): AiMode = entries.firstOrNull { it.name == name } ?: HYBRID
    }
}

/** Work the app can hand to an engine. */
enum class AiTask {
    UNDERSTAND_PROMPT,
    ANALYSE_FOOTAGE,
    PLAN_EDIT,
    GENERATE_MEDIA,
    REFERENCE_REMAKE,
    EDIT_TIMELINE,
    RENDER,
}

/** Who does the work. NATIVE is CineMind's own editor and renderer, which stay the only ones. */
enum class AiEngine { NATIVE, GEMMA, HEURISTIC, CLAUDE, HANDOFF, UNAVAILABLE }

/** What the router knows about the phone right now. */
data class AiReadiness(
    val gemmaReady: Boolean,
    val claudeReady: Boolean,
)

/**
 * Picks the engine for a task. Pure and deterministic, so every choice can be tested and explained.
 * Gemma stays the primary intelligence; the cloud only adds to it, and only with the user's say-so.
 * Media generation has no in-app engine: it is handed to an outside service the user signs in to.
 */
object AiRouter {

    fun route(task: AiTask, mode: AiMode, ready: AiReadiness): AiEngine = when (task) {
        AiTask.EDIT_TIMELINE, AiTask.RENDER -> AiEngine.NATIVE
        AiTask.ANALYSE_FOOTAGE -> if (ready.gemmaReady) AiEngine.GEMMA else AiEngine.HEURISTIC
        AiTask.UNDERSTAND_PROMPT -> if (ready.gemmaReady) AiEngine.GEMMA else AiEngine.HEURISTIC
        AiTask.PLAN_EDIT -> when {
            mode == AiMode.OFFLINE -> local(ready)
            ready.claudeReady -> AiEngine.CLAUDE
            else -> local(ready)
        }
        AiTask.GENERATE_MEDIA, AiTask.REFERENCE_REMAKE ->
            if (mode == AiMode.OFFLINE) AiEngine.UNAVAILABLE else AiEngine.HANDOFF
    }

    /** True when cloud planning may run (used by the Smart Edit pipeline and the assistant). */
    fun cloudPlanningAllowed(mode: AiMode, claudeReady: Boolean): Boolean =
        mode != AiMode.OFFLINE && claudeReady

    private fun local(ready: AiReadiness) = if (ready.gemmaReady) AiEngine.GEMMA else AiEngine.HEURISTIC
}
