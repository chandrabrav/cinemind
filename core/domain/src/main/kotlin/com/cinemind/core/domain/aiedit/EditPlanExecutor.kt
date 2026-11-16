package com.cinemind.core.domain.aiedit

import com.cinemind.core.domain.audio.SoundEffect
import com.cinemind.core.domain.captions.Caption
import com.cinemind.core.domain.color.BuiltInLooks
import com.cinemind.core.domain.color.ColorGrade
import com.cinemind.core.domain.editing.ClipTransition
import com.cinemind.core.domain.editing.SpeedMath
import com.cinemind.core.domain.editing.SpeedRampPreset
import com.cinemind.core.domain.editing.TimelineEditing
import com.cinemind.core.domain.editing.TransitionEditing
import com.cinemind.core.domain.model.Clip
import com.cinemind.core.domain.model.Project
import com.cinemind.core.domain.model.ProjectTimeline
import com.cinemind.core.domain.model.Track
import com.cinemind.core.domain.model.TrackType
import com.cinemind.core.domain.text.TextAnim
import com.cinemind.core.domain.text.TextKind
import com.cinemind.core.domain.text.TextLayer
import com.cinemind.core.domain.text.TextPreset
import com.cinemind.core.domain.text.TextStyle
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToLong

/** Everything an executed plan produces; the caller stores it as one new project. */
data class BuiltEdit(
    val timeline: ProjectTimeline,
    val texts: List<TextLayer>,
    val captions: List<Caption>,
    /** Caption preset id to store, or null to keep the default. */
    val captionPreset: String?,
    val audio: PlanAudio,
    /** Sound effects and when they start on the new timeline. */
    val sounds: List<Pair<SoundEffect, Long>>,
    val steps: List<PlanStep>,
)

/**
 * Turns a [ValidatedPlan] into real editor objects — main-track clips with trims, speeds, ramps,
 * colour grades and transitions; text layers with animations; captions carried over from the
 * source project and re-timed to the new cut; music and sound settings. Pure: the caller saves
 * the result as a new project, so the original is never touched and the AI edit is fully
 * editable and revertible.
 */
object EditPlanExecutor {

    private const val MIN_TEXT_US = 800_000L

    fun execute(
        validated: ValidatedPlan,
        sources: List<PlanSource>,
        template: Project,
        name: String,
        sourceTimeline: ProjectTimeline? = null,
        sourceCaptions: List<Caption> = emptyList(),
        now: Long = System.currentTimeMillis(),
        newId: () -> String = { UUID.randomUUID().toString() },
    ): BuiltEdit {
        val plan = validated.plan
        require(plan.shots.isNotEmpty()) { "Nothing to edit" }
        val steps = validated.steps.toMutableList()
        fun done(area: String, text: String, status: StepStatus = StepStatus.APPLIED) { steps += PlanStep(area, status, text) }

        val project = template.copy(id = newId(), name = name, createdAt = now, updatedAt = now, thumbnailUri = null)
        val track = Track(id = newId(), projectId = project.id, type = TrackType.VIDEO, index = 0, name = "Video 1")

        // ---- shots, speed, ramps, grade
        val baseGrade = plan.grade?.let { g ->
            val lookId = when {
                g.look.isEmpty() -> null
                BuiltInLooks.byKey(g.look) != null -> BuiltInLooks.byKey(g.look)!!.id
                else -> g.look
            }
            ColorGrade(
                lutId = lookId,
                lutIntensity = g.intensity.toFloat(),
                exposure = g.exposure.toFloat(),
                contrast = g.contrast.toFloat(),
                saturation = g.saturation.toFloat(),
                temperature = g.temperature.toFloat(),
                tint = g.tint.toFloat(),
                highlights = g.highlights.toFloat(),
                shadows = g.shadows.toFloat(),
            )
        } ?: ColorGrade.NONE
        var ramps = 0
        var corrected = 0
        var clips = plan.shots.map { s ->
            val src = sources[s.source - 1]
            var clip = Clip(
                id = newId(),
                trackId = track.id,
                assetId = src.assetId,
                timelineInUs = 0,
                timelineOutUs = 0,
                sourceInUs = (s.fromSeconds * 1e6).roundToLong(),
                sourceOutUs = (s.toSeconds * 1e6).roundToLong(),
                speed = s.speed.toFloat(),
            )
            SpeedRampPreset.entries.firstOrNull { it.name == s.ramp }?.let { r ->
                clip = clip.copy(speed = 1f, speedCurve = SpeedMath.encode(r.pointsFor(clip)))
                ramps++
            }
            val grade = if (s.exposure != 0.0) {
                corrected++
                baseGrade.copy(exposure = (baseGrade.exposure + s.exposure.toFloat()).coerceIn(-2f, 2f))
            } else baseGrade
            clip.copy(grade = grade.encode())
        }
        clips = TimelineEditing.relayout(clips)
        done("Shots", "${clips.size} shots from ${clips.map { it.assetId }.distinct().size} source(s), ${secs(TimelineEditing.durationUs(clips))} long")
        if (plan.shots.any { it.speed != 1.0 && it.ramp.isEmpty() }) done("Speed", "Speed changed on ${plan.shots.count { it.speed != 1.0 && it.ramp.isEmpty() }} shot(s)")
        if (ramps > 0) done("Speed", "Speed ramps on $ramps shot(s): " + plan.shots.filter { it.ramp.isNotEmpty() }.map { r -> SpeedRampPreset.valueOf(r.ramp).label }.distinct().joinToString())
        if (!baseGrade.isNeutral) {
            val look = baseGrade.lutId?.let { id -> BuiltInLooks.byId(id)?.name ?: id }
            done("Colour", listOfNotNull(look?.let { "Look “$it” at ${(baseGrade.lutIntensity * 100).toInt()}%" }, if (baseGrade.hasAdjustments) "balanced exposure, contrast and colour" else null).joinToString(" with ") + " on every shot")
        }
        if (corrected > 0) done("Colour", "Exposure matched on $corrected shot(s) so the look stays consistent")

        // ---- transitions
        var transitions = 0
        for (i in 1 until clips.size) {
            val type = ClipTransition.fromName(plan.shots[i].transitionIn)
            if (type == ClipTransition.CUT) continue
            val before = clips
            clips = TransitionEditing.setTransition(clips, clips[i].id, type, (plan.shots[i].transitionSeconds * 1000).roundToLong())
            if (clips !== before) transitions++
        }
        clips = TransitionEditing.setOpeningAndEnding(clips, (plan.openingSeconds * 1000).roundToLong(), (plan.endingSeconds * 1000).roundToLong())
        if (transitions > 0) {
            val kinds = (1 until clips.size).map { ClipTransition.fromName(clips[it].transitionInType) }.filter { it != ClipTransition.CUT }.map { it.label }.distinct()
            done("Transitions", "$transitions transition(s) (${kinds.joinToString()}), cuts elsewhere")
        } else done("Transitions", "Hard cuts between shots")
        if (plan.openingSeconds > 0 || plan.endingSeconds > 0) done("Transitions", "Fade in ${secs((plan.openingSeconds * 1e6).toLong())}, fade out ${secs((plan.endingSeconds * 1e6).toLong())}")

        val totalUs = TimelineEditing.durationUs(clips)
        val timeline = ProjectTimeline(project.copy(durationUs = totalUs), listOf(track), clips)

        // ---- text layers
        val texts = mutableListOf<TextLayer>()
        for (t in plan.texts) {
            val role = TextRole.valueOf(t.role)
            if (totalUs < MIN_TEXT_US) break
            val lengthUs = (t.seconds * 1e6).roundToLong()
            // An end card always sits on the real end (speed ramps change the cut's length).
            val wanted = if (role == TextRole.END_CARD) totalUs - lengthUs else (t.atSeconds * 1e6).roundToLong()
            val start = wanted.coerceIn(0, totalUs - MIN_TEXT_US)
            val end = (start + lengthUs).coerceAtMost(totalUs)
            val lane = texts.size
            texts += layerFor(project.id, role, t, start, end, lane, newId())
        }
        if (texts.isNotEmpty()) {
            texts.groupBy { TextRole.valueOf(plan.texts[texts.indexOf(it)].role) }.forEach { (role, list) ->
                done("Text", "${role.label}: " + list.joinToString { "“${it.text}”" })
            }
        }

        // ---- captions (re-timed to the new cut; speech that was cut out goes with it)
        val captions = if (sourceTimeline != null && sourceCaptions.isNotEmpty()) remapCaptions(sourceCaptions, sourceTimeline, clips, project.id, newId) else emptyList()
        if (sourceCaptions.isNotEmpty()) {
            done("Captions", "Kept ${captions.size} of ${sourceCaptions.size} captions, re-timed to the new cut" +
                if (captions.size < sourceCaptions.size) " (the rest were in footage that was cut)" else "")
        }
        if (plan.captionPreset.isNotEmpty()) done("Captions", "Caption style: " + (com.cinemind.core.domain.captions.CaptionPresets.byId(plan.captionPreset)?.label ?: plan.captionPreset))

        // ---- audio
        val sounds = plan.sounds.mapNotNull { s ->
            val at = (s.atSeconds * 1e6).roundToLong()
            if (at >= totalUs) { done("Sound", "“${s.name.lowercase(Locale.ROOT)}” at ${secs(at)} is after the end", StepStatus.SKIPPED); null }
            else SoundEffect.valueOf(s.name) to at
        }
        if (sounds.isNotEmpty()) done("Sound", sounds.joinToString { (e, at) -> "${e.label} at ${secs(at)}" })
        val a = plan.audio
        if (a.musicVolume >= 0 || a.musicFadeInSeconds >= 0 || a.musicFadeOutSeconds >= 0) {
            done("Music", listOfNotNull(
                if (a.musicVolume >= 0) "volume ${(a.musicVolume * 100).toInt()}%" else null,
                if (a.musicFadeInSeconds >= 0) "fade in ${fmt(a.musicFadeInSeconds)} s" else null,
                if (a.musicFadeOutSeconds >= 0) "fade out ${fmt(a.musicFadeOutSeconds)} s" else null,
            ).joinToString().replaceFirstChar { it.uppercase() } + " (when the project has music)")
        }
        if (a.originalVolume >= 0) done("Music", "Original sound at ${(a.originalVolume * 100).toInt()}%")
        if (a.musicMood.isNotEmpty()) done("Music", "Suggested music: ${a.musicMood} — pick a track you own in Audio → Music", StepStatus.ADJUSTED)

        return BuiltEdit(timeline, texts, captions, plan.captionPreset.ifEmpty { null }, a, sounds, steps)
    }

    private fun layerFor(projectId: String, role: TextRole, t: PlanText, start: Long, end: Long, lane: Int, id: String): TextLayer {
        if (role == TextRole.STICKER) {
            val style = TextStyle(shadow = false, animIn = anim(t.animIn) ?: TextAnim.POP, animOut = anim(t.animOut) ?: TextAnim.FADE)
            return TextLayer(id = id, projectId = projectId, startUs = start, endUs = end, kind = TextKind.STICKER.name, text = t.text,
                x = 0.78f, y = 0.22f, size = 0.12f, style = style.encode(), lane = lane)
        }
        val preset = TextPreset.entries.firstOrNull { it.name == t.preset } ?: when (role) {
            TextRole.TITLE -> TextPreset.BOLD_TITLE
            TextRole.SECTION -> TextPreset.CINEMA
            TextRole.LOWER_THIRD -> TextPreset.LOWER_THIRD
            TextRole.LABEL, TextRole.CALLOUT -> TextPreset.LABEL
            TextRole.END_CARD -> TextPreset.MINIMAL
            TextRole.STICKER -> TextPreset.BOLD_TITLE
        }
        val (x, y) = when (role) {
            TextRole.LOWER_THIRD -> 0.36f to 0.8f
            TextRole.LABEL -> 0.5f to 0.2f
            TextRole.CALLOUT -> 0.5f to 0.35f
            TextRole.END_CARD, TextRole.SECTION -> 0.5f to 0.5f
            else -> 0.5f to preset.y
        }
        val style = preset.style.copy(animIn = anim(t.animIn) ?: preset.style.animIn, animOut = anim(t.animOut) ?: preset.style.animOut)
        return TextLayer(id = id, projectId = projectId, startUs = start, endUs = end, text = t.text, x = x, y = y, size = preset.size, style = style.encode(), lane = lane)
    }

    private fun anim(name: String): TextAnim? = TextAnim.entries.firstOrNull { it.name == name }

    /**
     * Moves captions from the source project's timeline onto the new cut: each caption follows
     * the footage it was spoken over. Captions over footage that isn't in the new cut are dropped;
     * word timings are re-estimated (they no longer match after speed changes).
     */
    fun remapCaptions(captions: List<Caption>, source: ProjectTimeline, newClips: List<Clip>, projectId: String, newId: () -> String): List<Caption> {
        val mainTrack = source.tracks.firstOrNull { it.type == TrackType.VIDEO }?.id
        val oldClips = source.clips.filter { it.trackId == mainTrack }.sortedBy { it.timelineInUs }
        fun toSource(t: Long): Pair<String, Long>? {
            val c = oldClips.firstOrNull { t >= it.timelineInUs && t < it.timelineOutUs } ?: return null
            return c.assetId to (c.sourceInUs + SpeedMath.timelineToSource(c, t - c.timelineInUs))
        }
        fun toNew(assetId: String, src: Long): Pair<Clip, Long>? {
            val c = newClips.firstOrNull { it.assetId == assetId && src >= it.sourceInUs && src < it.sourceOutUs } ?: return null
            return c to (c.timelineInUs + SpeedMath.sourceToTimeline(c, src - c.sourceInUs))
        }
        return captions.sortedBy { it.startUs }.mapNotNull { cap ->
            val (asset, srcStart) = toSource(cap.startUs) ?: return@mapNotNull null
            val (clip, start) = toNew(asset, srcStart) ?: return@mapNotNull null
            val srcEnd = toSource((cap.endUs - 1).coerceAtLeast(cap.startUs))?.takeIf { it.first == asset }?.second ?: clip.sourceOutUs
            val end = toNew(asset, srcEnd.coerceAtMost(clip.sourceOutUs - 1))?.takeIf { it.first.id == clip.id }?.second ?: clip.timelineOutUs
            if (end - start < 200_000) return@mapNotNull null
            cap.copy(id = newId(), projectId = projectId, startUs = start, endUs = end.coerceAtMost(clip.timelineOutUs), wordTimes = "")
        }.sortedBy { it.startUs }.fold(mutableListOf<Caption>()) { acc, c ->
            // Keep captions from overlapping where shots were reordered.
            val prev = acc.lastOrNull()
            if (prev == null || c.startUs >= prev.endUs) acc += c
            else if (c.startUs >= prev.startUs + 200_000) { acc[acc.lastIndex] = prev.copy(endUs = c.startUs); acc += c }
            acc
        }
    }

    private fun secs(us: Long): String = fmt(us / 1e6) + " s"
    private fun fmt(v: Double): String = String.format(Locale.ROOT, "%.1f", v)
}
