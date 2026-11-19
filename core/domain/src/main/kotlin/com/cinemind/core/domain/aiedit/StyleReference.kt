package com.cinemind.core.domain.aiedit

import com.cinemind.core.domain.model.Pacing
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** What the user attached to the brief as an example to follow. */
enum class ReferenceKind(val label: String) { VIDEO("Sample video"), IMAGE("Reference image") }

/**
 * One sampled frame of a reference, measured on the phone (tiny frames, nothing uploaded).
 * [brightness], [saturation] and [contrast] are 0..1; [warmth] is -1 (blue) .. 1 (orange);
 * [change] is how different this frame's tones are from the previous sample (0..1, a cut is high).
 */
data class ReferenceFrame(
    val timeUs: Long,
    val brightness: Float,
    val saturation: Float,
    val warmth: Float,
    val contrast: Float,
    val change: Float = 0f,
)

/**
 * The style of a sample video or reference image, as numbers and as words the planner can use:
 * how fast it cuts, how much it moves, and its look. The user's own footage is still what gets
 * edited; a reference only steers pace and look ("make it like this").
 */
data class StyleReference(
    val kind: ReferenceKind,
    val name: String,
    val durationUs: Long = 0,
    /** Average shot length in seconds (videos with enough samples), else null. */
    val shotSec: Float? = null,
    val motion: Float = 0f,
    val brightness: Float = 0.5f,
    val contrast: Float = 0.2f,
    val saturation: Float = 0.3f,
    val warmth: Float = 0f,
    /** What the on-device vision model saw, if one is installed (one sentence). */
    val seen: String = "",
) {
    /** Pacing that matches the reference's cutting, or null when unknown. */
    val pacing: Pacing? get() = shotSec?.let {
        when {
            it < 1.8f -> Pacing.FAST
            it > 4.5f -> Pacing.CALM
            else -> Pacing.BALANCED
        }
    }

    /** Short words for the chip under the prompt. */
    fun summary(): String = buildList {
        shotSec?.let { add(if (it < 1.8f) "fast cuts" else if (it > 4.5f) "slow, long shots" else "steady cuts") }
        add(lookWords().joinToString(", "))
    }.filter { it.isNotBlank() }.joinToString(" · ")

    /** One line for the planner. */
    fun describe(): String = buildString {
        append(kind.label).append(" “").append(name.take(60)).append("”")
        if (kind == ReferenceKind.VIDEO && durationUs > 0) append(" (").append((durationUs / 1_000_000).coerceAtLeast(1)).append(" s)")
        append(": ")
        val parts = mutableListOf<String>()
        shotSec?.let { s ->
            val pace = when (pacing) { Pacing.FAST -> "fast cuts"; Pacing.CALM -> "slow, long shots"; else -> "steady cuts" }
            parts += "$pace, about ${String.format(Locale.ROOT, "%.1f", s)} s per shot"
        }
        if (kind == ReferenceKind.VIDEO) parts += when {
            motion > 0.12f -> "lots of movement"
            motion > 0.05f -> "some movement"
            else -> "mostly still shots"
        }
        parts += lookWords()
        append(parts.joinToString("; "))
        if (seen.isNotBlank()) append(". It shows: ").append(seen.trim().removeSuffix(".").take(200))
        append('.')
    }

    private fun lookWords(): List<String> = listOf(
        when {
            brightness < 0.32f -> "dark and moody"
            brightness > 0.62f -> "bright and airy"
            else -> "natural exposure"
        },
        when {
            contrast > 0.26f -> "high contrast"
            contrast < 0.13f -> "soft, low contrast"
            else -> ""
        },
        when {
            saturation > 0.45f -> "vivid colours"
            saturation < 0.18f -> "muted colours"
            else -> ""
        },
        when {
            warmth > 0.08f -> "warm tones"
            warmth < -0.08f -> "cool tones"
            else -> ""
        },
    ).filter { it.isNotBlank() }

    /** One tab-separated line (stored per project on the phone). */
    fun encode(): String = listOf(
        kind.name, name.replace('\t', ' ').replace('\n', ' '), durationUs.toString(), shotSec?.toString() ?: "",
        motion.toString(), brightness.toString(), contrast.toString(), saturation.toString(), warmth.toString(),
        seen.replace('\t', ' ').replace('\n', ' '),
    ).joinToString("\t")

    companion object {
        fun decode(line: String): StyleReference? {
            val p = line.split('\t')
            if (p.size < 10) return null
            val kind = ReferenceKind.entries.firstOrNull { it.name == p[0] } ?: return null
            return StyleReference(
                kind = kind, name = p[1], durationUs = p[2].toLongOrNull() ?: 0, shotSec = p[3].toFloatOrNull(),
                motion = p[4].toFloatOrNull() ?: 0f, brightness = p[5].toFloatOrNull() ?: 0.5f, contrast = p[6].toFloatOrNull() ?: 0.2f,
                saturation = p[7].toFloatOrNull() ?: 0.3f, warmth = p[8].toFloatOrNull() ?: 0f, seen = p[9],
            )
        }
    }
}

object StyleReferences {

    /** Up to this many references per brief. */
    const val MAX = 4

    /** A sample counts as a new shot when its tones change this much from the previous one. */
    const val CUT_CHANGE = 0.32f

    /**
     * Builds the style of a video from frames sampled evenly over [durationUs] (or a still image
     * from one frame). Shot length = sampled time / (cuts + 1); it needs at least 6 samples.
     */
    fun from(kind: ReferenceKind, name: String, durationUs: Long, frames: List<ReferenceFrame>, seen: String = ""): StyleReference {
        if (frames.isEmpty()) return StyleReference(kind, name, durationUs, seen = seen)
        val sorted = frames.sortedBy { it.timeUs }
        val cuts = sorted.drop(1).count { it.change >= CUT_CHANGE }
        val span = (sorted.last().timeUs - sorted.first().timeUs).coerceAtLeast(0)
        val stepUs = if (sorted.size > 1) span / (sorted.size - 1) else 0
        val shot = if (kind == ReferenceKind.VIDEO && sorted.size >= 6 && span > 0) ((span + stepUs) / 1e6f) / (cuts + 1) else null
        // Movement inside shots: changes below the cut threshold.
        val within = sorted.drop(1).map { it.change }.filter { it < CUT_CHANGE }
        return StyleReference(
            kind = kind,
            name = name,
            durationUs = durationUs,
            shotSec = shot,
            motion = if (within.isEmpty()) 0f else within.average().toFloat(),
            brightness = sorted.map { it.brightness }.average().toFloat(),
            contrast = sorted.map { it.contrast }.average().toFloat(),
            saturation = sorted.map { it.saturation }.average().toFloat(),
            warmth = sorted.map { it.warmth }.average().toFloat(),
            seen = seen,
        )
    }

    /** Tone change between two 16-bin brightness histograms (each summing to 1): half the L1 distance, 0..1. */
    fun histogramChange(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f
        var d = 0f
        for (i in a.indices) d += abs(a[i] - b[i])
        return (d / 2f).coerceIn(0f, 1f)
    }

    /** What the planner reads after the user's own words. Empty when there are no references. */
    fun promptBlock(refs: List<StyleReference>): String {
        if (refs.isEmpty()) return ""
        return buildString {
            append("\n\nStyle references from the user (match their pace and look; edit only the user's own footage):")
            refs.take(MAX).forEachIndexed { i, r -> append("\n").append(i + 1).append(". ").append(r.describe()) }
        }
    }

    /** The pacing most references agree on, or null. */
    fun pacing(refs: List<StyleReference>): Pacing? =
        refs.mapNotNull { it.pacing }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key

    /** Percent text for a 0..1 value (diagnostics). */
    fun pct(v: Float): String = "${(v * 100).roundToInt()}%"
}
