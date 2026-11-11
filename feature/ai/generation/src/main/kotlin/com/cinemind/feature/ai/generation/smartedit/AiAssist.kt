package com.cinemind.feature.ai.generation.smartedit

import com.cinemind.core.domain.editing.ClipTransition
import com.cinemind.core.domain.model.EditBrief
import com.cinemind.core.domain.model.EditStyle
import com.cinemind.core.domain.model.Pacing
import com.cinemind.feature.ai.llm.ondevice.BriefAnswer
import com.cinemind.feature.ai.llm.ondevice.Prompts
import com.cinemind.feature.ai.llm.ondevice.ShotInsight
import kotlin.math.abs

/** A model description of a source at a moment in time. */
data class TimedInsight(val timeUs: Long, val insight: ShotInsight)

/** Pure helpers that fold on-device model output into the rule-based smart edit. */
object AiAssist {

    /**
     * Moments worth showing the model: the best-scoring sample in each of [perSource] equal parts of
     * a video (so the whole clip is covered), or the single sample of a photo.
     */
    fun keyframes(a: AssetAnalysis, perSource: Int, sharpRef: Float, strict: Boolean): List<Long> {
        if (a.samples.isEmpty()) return emptyList()
        if (!a.isVideo || a.durationUs <= 0) return listOf(a.samples.first().timeUs)
        val parts = perSource.coerceAtLeast(1)
        return (0 until parts).mapNotNull { p ->
            val from = a.durationUs * p / parts
            val to = a.durationUs * (p + 1) / parts
            a.samples.filter { it.timeUs in from until to }
                .maxByOrNull { MomentScoring.score(it, sharpRef, strict) }?.timeUs
        }
    }

    /** 0.55..1.0 weight from the model's view of quality and interest. */
    fun weight(insight: ShotInsight): Float =
        0.55f + 0.45f * ((insight.quality * 0.4f + insight.interest * 0.6f) / 10f)

    /** The insight closest in time to [timeUs] for that source, or null. */
    fun nearest(insights: List<TimedInsight>?, timeUs: Long): TimedInsight? =
        insights?.minByOrNull { abs(it.timeUs - timeUs) }

    /** True if two shots show (nearly) the same thing. */
    fun isRepeat(a: ShotInsight?, b: ShotInsight?): Boolean =
        a != null && b != null && Prompts.similarity(a.subject + " " + a.scene, b.subject + " " + b.scene) >= 0.5f

    /**
     * Combines the model's reading of the brief with the keyword reading. Choices the user made
     * explicitly in the brief screen always win; then the model; then keywords.
     */
    fun mergeBrief(brief: EditBrief, keywords: EditIntent, ai: BriefAnswer?): EditIntent {
        if (ai == null) return keywords
        val style = if (brief.style != EditStyle.AUTO) brief.style else ai.style?.let { styleOf(it) } ?: keywords.style
        val pacing = if (brief.pacing != Pacing.BALANCED) brief.pacing else ai.pacing?.let { pacingOf(it) } ?: keywords.pacing
        val targetUs = brief.targetDurationSec?.let { it * 1_000_000L } ?: ai.targetSeconds?.let { it * 1_000_000L } ?: keywords.targetUs
        val order = when (ai.order) {
            "chronological" -> ShotOrder.CHRONOLOGICAL
            "hook_first" -> ShotOrder.HOOK_FIRST
            else -> keywords.order
        }
        val transition = ai.transition?.let { t -> ClipTransition.entries.firstOrNull { it.name.equals(t, ignoreCase = true) } }
            ?: keywords.transition
        val transitionMs = when {
            transition == ClipTransition.CUT -> 0L
            keywords.transitionMs > 0 -> keywords.transitionMs
            else -> 500L
        }
        return keywords.copy(
            style = style,
            pacing = pacing,
            targetUs = targetUs,
            order = order,
            strictQuality = ai.strictQuality ?: keywords.strictQuality,
            transition = transition,
            transitionMs = transitionMs,
            shotUs = BriefInterpreter.shotLengthUs(style, pacing),
        )
    }

    private fun styleOf(s: String) = EditStyle.entries.firstOrNull { it.name.equals(s, ignoreCase = true) }
    private fun pacingOf(s: String) = Pacing.entries.firstOrNull { it.name.equals(s, ignoreCase = true) }
}
