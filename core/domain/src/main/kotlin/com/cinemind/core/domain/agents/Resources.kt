package com.cinemind.core.domain.agents

/**
 * What the phone can spare right now (as far as Android tells apps). [thermal] follows
 * PowerManager.THERMAL_STATUS_* (0 none … 3 severe … 6 shutdown); -1 = unknown.
 */
data class ResourceSnapshot(
    val availMemMb: Long,
    val totalMemMb: Long,
    val lowMemory: Boolean = false,
    val thermal: Int = -1,
    val batteryPercent: Int = 100,
    val charging: Boolean = false,
    val powerSave: Boolean = false,
)

/** Limits the decision engine plans within. */
data class ResourceBudget(
    /** Largest on-device model that may be loaded now (one at a time). */
    val maxModelMb: Int,
    val localModelsAllowed: Boolean,
    /** Optional AI agents (shot descriptions, transcription) may run. */
    val optionalAiAllowed: Boolean,
    /** Why the budget is limited, for the report ("phone is hot", …); empty when it isn't. */
    val reason: String,
)

/**
 * Turns a [ResourceSnapshot] into a [ResourceBudget]. Models are loaded one at a time and freed
 * after use; this decides whether one may be loaded at all, and how big. Conservative on purpose:
 * advertised context lengths and model sizes are not what a warm phone can sustain.
 */
object ResourcePolicy {

    /** Kept free for the editor, the preview player and Android itself. */
    const val RESERVE_MB = 1_800

    fun budget(s: ResourceSnapshot): ResourceBudget {
        val reasons = mutableListOf<String>()
        // A model needs room beyond its file size for activations and the KV cache.
        var max = ((s.availMemMb - RESERVE_MB) / 1.3).toLong().coerceAtLeast(0).toInt()
        // Never plan with more than ~45% of the phone's RAM even when it reports more free.
        if (s.totalMemMb > 0) max = minOf(max, (s.totalMemMb * 0.45).toInt())
        var local = true
        var optional = true

        if (s.lowMemory) {
            local = false
            reasons += "the phone is low on memory"
        }
        when {
            s.thermal >= 4 -> { local = false; optional = false; reasons += "the phone is too hot" }
            s.thermal == 3 -> { local = false; reasons += "the phone is hot" }
            s.thermal == 2 -> { max = minOf(max, 2_200); optional = false; reasons += "the phone is warm, so only small models run" }
        }
        if (!s.charging && (s.batteryPercent in 0..15 || (s.powerSave && s.batteryPercent in 0..30))) {
            optional = false
            max = minOf(max, 2_200)
            reasons += "the battery is low"
        }
        if (max < 1_200) {
            local = false
            if (reasons.isEmpty()) reasons += "not enough free memory for an AI model"
        }
        return ResourceBudget(max, local, optional, reasons.joinToString(", "))
    }
}
