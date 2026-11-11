package com.cinemind.feature.ai.generation.smartedit

import com.cinemind.core.domain.editing.ClipTransition
import com.cinemind.core.domain.editing.TimelineEditing
import com.cinemind.core.domain.editing.TransitionEditing
import com.cinemind.core.domain.model.Clip
import com.cinemind.core.domain.model.Project
import com.cinemind.core.domain.model.ProjectTimeline
import com.cinemind.core.domain.model.Track
import com.cinemind.core.domain.model.TrackType
import kotlin.math.ceil
import kotlin.math.pow

/** One chosen piece of footage. */
data class PlannedShot(
    val assetId: String,
    val sourceInUs: Long,
    val sourceOutUs: Long,
    val score: Float,
    val order: Int,
) {
    val lengthUs: Long get() = sourceOutUs - sourceInUs
}

data class SmartEditPlan(
    val shots: List<PlannedShot>,
    val intent: EditIntent,
    /** Short, human-readable explanations of the main decisions. */
    val decisions: List<String>,
)

/**
 * Chooses and orders shots from analysed footage to match an [EditIntent]. Pure and deterministic.
 *
 * 1. Cuts every video into candidate windows of the intent's shot length (photos are one candidate).
 * 2. Scores each window from its samples (sharpness, exposure, motion).
 * 3. Greedily picks the best windows, discouraging repeats from the same source and never
 *    overlapping footage, until the target length is reached; weak windows are skipped.
 * 4. Orders shots (chronological, or best shot first as a hook) and trims the last to the target.
 */
object SmartEditPlanner {

    const val MIN_SHOT_US = 600_000L
    private const val PHOTO_MAX_US = 3_000_000L
    private const val REPEAT_PENALTY = 0.85
    private const val SCENE_REPEAT_PENALTY = 0.7
    private const val WEAK = 0.30f
    private const val WEAK_STRICT = 0.45f

    /**
     * @param insights optional on-device model descriptions per source; when present they weight
     *   each window by the model's quality/interest score and discourage repeating the same scene.
     */
    fun plan(
        analyses: List<AssetAnalysis>,
        intent: EditIntent,
        insights: Map<String, List<TimedInsight>> = emptyMap(),
    ): SmartEditPlan {
        require(analyses.isNotEmpty()) { "Nothing to edit" }

        val sharpRef = MomentScoring.sharpnessReference(analyses.flatMap { a -> a.samples.map { it.sharpness } })
        fun insightOf(s: PlannedShot) = AiAssist.nearest(insights[s.assetId], (s.sourceInUs + s.sourceOutUs) / 2)?.insight
        val candidates = analyses.flatMap { candidatesFor(it, intent, sharpRef) }.map { c ->
            insightOf(c)?.let { c.copy(score = c.score * AiAssist.weight(it)) } ?: c
        }
        val totalUs = analyses.sumOf { if (it.isVideo) it.durationUs else minOf(PHOTO_MAX_US, intent.shotUs) }
        val target = (intent.targetUs ?: autoTargetUs(totalUs)).coerceAtMost(totalUs)

        val threshold = if (intent.strictQuality) WEAK_STRICT else WEAK
        val good = candidates.filter { it.score >= threshold }
        val pool = if (good.sumOf { it.lengthUs } >= target * 0.6) good else candidates
        val skippedWeak = candidates.size - good.size

        val picked = mutableListOf<PlannedShot>()
        val usesPerAsset = mutableMapOf<String, Int>()
        var totalPicked = 0L
        val remaining = pool.toMutableList()
        var avoidedRepeats = 0
        fun effective(c: PlannedShot): Float {
            var s = c.score * REPEAT_PENALTY.pow(usesPerAsset[c.assetId] ?: 0).toFloat()
            if (insights.isNotEmpty()) {
                val mine = insightOf(c)
                // Same scene filmed in another clip (repeats within one clip are handled by REPEAT_PENALTY).
                val repeats = picked.count { it.assetId != c.assetId && AiAssist.isRepeat(mine, insightOf(it)) }
                if (repeats > 0) s *= SCENE_REPEAT_PENALTY.pow(repeats).toFloat()
            }
            return s
        }
        while (totalPicked < target && remaining.isNotEmpty()) {
            val best = remaining.maxBy { effective(it) }
            remaining.remove(best)
            if (picked.any { it.assetId == best.assetId && overlaps(it, best) }) continue
            if (insights.isNotEmpty() && picked.any { it.assetId != best.assetId && AiAssist.isRepeat(insightOf(best), insightOf(it)) }) avoidedRepeats++
            picked += best
            usesPerAsset[best.assetId] = (usesPerAsset[best.assetId] ?: 0) + 1
            totalPicked += best.lengthUs
        }

        val ordered = order(picked, intent.order)
        val fitted = trimToTarget(ordered, target)

        val decisions = buildList {
            add("${fitted.size} shots from ${fitted.map { it.assetId }.distinct().size} of ${analyses.size} sources, ${seconds(fitted.sumOf { it.lengthUs })} long")
            add("Style ${intent.style.label} · pace ${intent.pacing.label} · shots about ${seconds(intent.shotUs)}")
            if (skippedWeak > 0 && pool === good) add("Skipped $skippedWeak dark, blurry or shaky moments")
            if (pool !== good) add("Footage is short or mostly weak, so some weaker moments were kept")
            if (insights.isNotEmpty()) {
                val seen = fitted.mapNotNull { insightOf(it)?.subject }.distinct().take(4)
                if (seen.isNotEmpty()) add("On-device AI saw: " + seen.joinToString(", ") { "“$it”" })
                if (avoidedRepeats == 0) add("No repeated scenes") else add("Kept $avoidedRepeats similar scene(s) because the footage was limited")
            }
            if (intent.order == ShotOrder.HOOK_FIRST) {
                val hook = fitted.firstOrNull()?.let { insightOf(it)?.subject }
                add("Opens with the strongest shot" + (hook?.let { " (“$it”)" } ?: "") + ", then follows the original order")
            }
            add(
                if (intent.transition == ClipTransition.CUT) "Hard cuts between shots"
                else "${intent.transition.label} when the source changes, cuts within a source",
            )
        }
        return SmartEditPlan(fitted, intent, decisions)
    }

    /** Builds an editable project (one video track) from a plan, keeping [source]'s format. */
    fun toTimeline(plan: SmartEditPlan, source: Project, name: String, now: Long = System.currentTimeMillis()): ProjectTimeline {
        val project = source.copy(
            id = java.util.UUID.randomUUID().toString(),
            name = name,
            createdAt = now,
            updatedAt = now,
            thumbnailUri = null,
        )
        val track = Track(projectId = project.id, type = TrackType.VIDEO, index = 0, name = "Video 1")
        var clips = TimelineEditing.relayout(
            plan.shots.map { s ->
                Clip(trackId = track.id, assetId = s.assetId, timelineInUs = 0, timelineOutUs = 0, sourceInUs = s.sourceInUs, sourceOutUs = s.sourceOutUs)
            },
        )
        val intent = plan.intent
        if (intent.transition != ClipTransition.CUT) {
            for (i in 1 until clips.size) {
                if (clips[i].assetId != clips[i - 1].assetId) {
                    clips = TransitionEditing.setTransition(clips, clips[i].id, intent.transition, intent.transitionMs)
                }
            }
        }
        clips = TransitionEditing.setOpeningAndEnding(clips, intent.openingMs, intent.endingMs)
        return ProjectTimeline(project.copy(durationUs = TimelineEditing.durationUs(clips)), listOf(track), clips)
    }

    fun autoTargetUs(totalUs: Long): Long = (totalUs * 0.6).toLong().coerceIn(8_000_000L, 45_000_000L).coerceAtMost(totalUs)

    internal fun candidatesFor(a: AssetAnalysis, intent: EditIntent, sharpRef: Float): List<PlannedShot> {
        val scored = a.samples.map { it.timeUs to MomentScoring.score(it, sharpRef, intent.strictQuality) }
        fun windowScore(from: Long, to: Long): Float {
            val inside = scored.filter { (t, _) -> t in from until to }
            if (inside.isNotEmpty()) return inside.map { it.second }.average().toFloat()
            val nearest = scored.minByOrNull { (t, _) -> kotlin.math.abs(t - (from + to) / 2) } ?: return 0.5f
            return nearest.second
        }

        if (!a.isVideo) {
            val len = minOf(PHOTO_MAX_US, (intent.shotUs * 1.2).toLong())
            return listOf(PlannedShot(a.assetId, 0, len, scored.firstOrNull()?.second ?: 0.6f, a.order))
        }
        if (a.durationUs <= 0) return emptyList()
        val shot = intent.shotUs
        if (a.durationUs <= (shot * 1.3).toLong()) {
            return listOf(PlannedShot(a.assetId, 0, a.durationUs, windowScore(0, a.durationUs), a.order))
        }
        val step = shot / 2
        val count = ceil((a.durationUs - shot).toDouble() / step).toInt() + 1
        return (0 until count).map { k ->
            val start = (k * step).coerceAtMost(a.durationUs - shot)
            PlannedShot(a.assetId, start, start + shot, windowScore(start, start + shot), a.order)
        }.distinctBy { it.sourceInUs }
    }

    private fun overlaps(a: PlannedShot, b: PlannedShot) = a.sourceInUs < b.sourceOutUs && b.sourceInUs < a.sourceOutUs

    private fun order(shots: List<PlannedShot>, order: ShotOrder): List<PlannedShot> {
        val chrono = shots.sortedWith(compareBy({ it.order }, { it.sourceInUs }))
        if (order == ShotOrder.CHRONOLOGICAL || chrono.size < 2) return chrono
        val hook = chrono.maxBy { it.score }
        return listOf(hook) + (chrono - hook)
    }

    /** Shortens (or drops) the final shot so the edit lands on the target length. */
    private fun trimToTarget(shots: List<PlannedShot>, targetUs: Long): List<PlannedShot> {
        val result = mutableListOf<PlannedShot>()
        var total = 0L
        for (s in shots) {
            val room = targetUs - total
            if (room <= 0) break
            if (s.lengthUs <= room) {
                result += s
                total += s.lengthUs
            } else {
                if (room >= MIN_SHOT_US) result += s.copy(sourceOutUs = s.sourceInUs + room)
                break
            }
        }
        return result.ifEmpty { shots.take(1) }
    }

    private fun seconds(us: Long): String = "${(us + 500_000) / 1_000_000} s"
}
