package com.cinemind.core.domain.editing

import com.cinemind.core.domain.audio.Waveform
import com.cinemind.core.domain.model.Clip
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Result of an automatic timeline edit, for the "what changed" message. */
data class AutoEditResult(val clips: List<Clip>, val changes: Int, val removedUs: Long = 0, val note: String = "")

/**
 * Talking-head tools that work from the clip's own sound (20 ms peak buckets, see [Waveform]):
 * jump cuts that remove pauses, and quick zoom punch-ins on the loudest, most emphatic moments.
 * Pure; the editor applies the result as one undo step.
 */
object TalkingHead {

    data class SilenceSettings(
        /** Pauses at least this long are cut. */
        val minPauseUs: Long = 500_000,
        /** Kept on each side of a cut so words aren't clipped. */
        val padUs: Long = 120_000,
        /** Alternate a slight zoom on each new piece, so jump cuts feel deliberate. */
        val zoomAlternate: Boolean = true,
    )

    /** Pauses in the source range [fromUs, toUs) of one sound, as source-time ranges to remove. */
    fun pauses(peaks: FloatArray, fromUs: Long, toUs: Long, s: SilenceSettings): List<LongRange> {
        val b = Waveform.BUCKET_US
        val b0 = (fromUs / b).toInt().coerceIn(0, peaks.size)
        val b1 = ((toUs + b - 1) / b).toInt().coerceIn(b0, peaks.size)
        if (b1 - b0 < 25) return emptyList()
        val values = peaks.copyOfRange(b0, b1)
        val sorted = values.sorted()
        val floor = sorted[(sorted.size * 0.10).toInt()]
        val speech = sorted[(sorted.size * 0.90).toInt().coerceAtMost(sorted.size - 1)]
        // No clear difference between talking and pauses (music, constant noise): don't cut.
        if (speech < 0.05f || speech < floor * 3f) return emptyList()
        val threshold = floor + 0.18f * (speech - floor)
        val out = mutableListOf<LongRange>()
        var start = -1
        for (i in 0..values.size) {
            val quiet = i < values.size && values[i] < threshold
            if (quiet && start < 0) start = i
            if (!quiet && start >= 0) {
                val runStart = (b0 + start) * b
                val runEnd = (b0 + i) * b
                if (runEnd - runStart >= s.minPauseUs) {
                    val cutFrom = (runStart + s.padUs).coerceAtLeast(fromUs)
                    val cutTo = (runEnd - s.padUs).coerceAtMost(toUs)
                    if (cutTo - cutFrom >= 100_000) out += cutFrom until cutTo
                }
                start = -1
            }
        }
        return out
    }

    /**
     * Removes pauses from every main-track clip that has speech (photos, clips with speed ramps
     * and clips without a clear voice are left alone). Pieces of one clip keep its look; the
     * clip's own opening transition stays on its first piece.
     */
    fun removeSilences(clips: List<Clip>, peaksFor: (String) -> FloatArray?, s: SilenceSettings = SilenceSettings()): AutoEditResult {
        var removed = 0L
        var cuts = 0
        val out = mutableListOf<Clip>()
        for (c in clips) {
            val peaks = peaksFor(c.assetId)
            val ranges = if (peaks == null || c.speedCurve.isNotBlank()) emptyList() else pauses(peaks, c.sourceInUs, c.sourceOutUs, s)
            if (ranges.isEmpty()) { out += c; continue }
            val keep = mutableListOf<LongRange>()
            var from = c.sourceInUs
            for (r in ranges) {
                if (r.first > from) keep += from until r.first
                from = r.last + 1
            }
            if (from < c.sourceOutUs) keep += from until c.sourceOutUs
            val kept = keep.filter { it.last - it.first >= 150_000 }
            if (kept.isEmpty()) { out += c; continue }
            val framing = ClipFraming.decode(c.framing)
            kept.forEachIndexed { i, r ->
                val zoomed = s.zoomAlternate && i % 2 == 1 && framing.keys.isEmpty()
                val f = if (zoomed) framing.copy(keys = listOf(MotionKey(sourceUs = r.first, scale = 1.12f))) else framing
                out += c.copy(
                    id = if (i == 0) c.id else UUID.randomUUID().toString(),
                    sourceInUs = r.first,
                    sourceOutUs = r.last + 1,
                    transitionInType = if (i == 0) c.transitionInType else ClipTransition.CUT.name,
                    transitionInMs = if (i == 0) c.transitionInMs else 0,
                    transitionOutMs = if (i == kept.lastIndex) c.transitionOutMs else 0,
                    framing = f.encode(),
                )
            }
            val before = c.sourceOutUs - c.sourceInUs
            val after = kept.sumOf { it.last + 1 - it.first }
            removed += ((before - after) / c.speed.coerceAtLeast(0.01f)).toLong()
            cuts += kept.size - 1
        }
        val laid = TimelineEditing.relayout(out)
        return AutoEditResult(laid, cuts, removed, if (cuts == 0) "No clear pauses found" else "Removed $cuts pause${if (cuts == 1) "" else "s"}")
    }

    /**
     * Adds a quick zoom-in on the most emphatic moments (loud peaks well above the voice level),
     * at most one every [spacingUs], on clips without their own motion keyframes.
     */
    fun punchIns(clips: List<Clip>, peaksFor: (String) -> FloatArray?, spacingUs: Long = 3_500_000, zoom: Float = 1.15f): AutoEditResult {
        var added = 0
        val out = clips.map { c ->
            val framing = ClipFraming.decode(c.framing)
            val peaks = peaksFor(c.assetId)
            if (peaks == null || framing.keys.isNotEmpty() || c.speedCurve.isNotBlank()) return@map c
            val moments = emphasis(peaks, c.sourceInUs, c.sourceOutUs, spacingUs)
            if (moments.isEmpty()) return@map c
            val keys = mutableListOf<MotionKey>()
            for (t in moments) {
                val start = (t - 120_000).coerceAtLeast(c.sourceInUs)
                val hold = (t + 1_100_000).coerceAtMost(c.sourceOutUs - 1)
                val end = (t + 1_400_000).coerceAtMost(c.sourceOutUs)
                if (end - start < 600_000) continue
                keys += MotionKey(start, scale = 1f, ease = SpeedEase.EASE_OUT)
                keys += MotionKey(t, scale = zoom, ease = SpeedEase.LINEAR)
                keys += MotionKey(hold, scale = zoom, ease = SpeedEase.EASE_IN_OUT)
                keys += MotionKey(end, scale = 1f, ease = SpeedEase.EASE_IN_OUT)
                added++
            }
            if (keys.isEmpty()) c else c.copy(framing = framing.copy(keys = keys.sortedBy { it.sourceUs }.distinctBy { it.sourceUs }).encode())
        }
        return AutoEditResult(out, added, note = if (added == 0) "No emphatic moments found" else "Added $added punch-in${if (added == 1) "" else "s"}")
    }

    /** Loud peaks (well above the speaking level) in a source range, spaced apart. */
    internal fun emphasis(peaks: FloatArray, fromUs: Long, toUs: Long, spacingUs: Long): List<Long> {
        val b = Waveform.BUCKET_US
        val b0 = (fromUs / b).toInt().coerceIn(0, peaks.size)
        val b1 = ((toUs + b - 1) / b).toInt().coerceIn(b0, peaks.size)
        if (b1 - b0 < 50) return emptyList()
        val values = peaks.copyOfRange(b0, b1)
        val sorted = values.sorted()
        val voice = sorted[(sorted.size * 0.75).toInt()]
        if (voice < 0.05f) return emptyList()
        val bar = max(sorted[(sorted.size * 0.97).toInt().coerceAtMost(sorted.size - 1)], voice * 1.25f)
        val picks = mutableListOf<Long>()
        val order = values.indices.filter { values[it] >= bar }.sortedByDescending { values[it] }
        val margin = 600_000L
        for (i in order) {
            val t = (b0 + i) * b
            if (t < fromUs + margin || t > toUs - 1_500_000) continue
            if (picks.any { abs(it - t) < spacingUs }) continue
            picks += t
            if (picks.size >= ((toUs - fromUs) / spacingUs).toInt().coerceAtLeast(1)) break
        }
        return picks.sorted()
    }
}

/**
 * Auto-reframe: crops each clip around where its subject is (from the AI cutout), so a
 * landscape shot fills a vertical reel without losing the person, and vice versa. A crop keeps
 * full sharpness (unlike zooming the fitted picture). When the subject moves far, the clip is
 * split so each piece gets its own framing.
 */
object Reframe {

    /** Where the subject is at one moment (source time, centre as 0..1 of the picture). */
    data class SubjectSample(val sourceUs: Long, val x: Float, val y: Float)

    /**
     * The crop that shows a [targetAspect] window of a [srcW]×[srcH] picture centred on (cx, cy),
     * kept inside the picture. Null when the aspects already match.
     */
    fun cropFor(srcW: Int, srcH: Int, targetAspect: Float, cx: Float, cy: Float): CropRect? {
        if (srcW <= 0 || srcH <= 0 || targetAspect <= 0f) return null
        val src = srcW.toFloat() / srcH
        if (abs(src - targetAspect) / targetAspect < 0.03f) return null
        return if (src > targetAspect) {
            val w = targetAspect / src
            val left = (cx - w / 2f).coerceIn(0f, 1f - w)
            CropRect(left = left, right = (1f - left - w).coerceAtLeast(0f))
        } else {
            val h = src / targetAspect
            val top = (cy - h / 2f).coerceIn(0f, 1f - h)
            CropRect(top = top, bottom = (1f - top - h).coerceAtLeast(0f))
        }
    }

    /**
     * Groups samples into steady stretches: a new stretch starts when the subject moves more than
     * [moveLimit] (fraction of the picture) from the stretch's median and stays there; stretches
     * shorter than [minUs] are merged.
     */
    fun segments(samples: List<SubjectSample>, fromUs: Long, toUs: Long, moveLimit: Float = 0.15f, minUs: Long = 2_000_000): List<Triple<Long, Long, SubjectSample>> {
        val inside = samples.filter { it.sourceUs in fromUs until toUs }.sortedBy { it.sourceUs }
        if (inside.isEmpty()) return emptyList()
        val groups = mutableListOf(mutableListOf(inside.first()))
        for (s in inside.drop(1)) {
            val cur = groups.last()
            val mx = median(cur.map { it.x })
            val my = median(cur.map { it.y })
            if (abs(s.x - mx) > moveLimit || abs(s.y - my) > moveLimit) groups += mutableListOf(s) else cur += s
        }
        // Merge short stretches into their neighbour.
        val merged = mutableListOf<MutableList<SubjectSample>>()
        for (g in groups) {
            val len = g.last().sourceUs - g.first().sourceUs
            if (merged.isNotEmpty() && len < minUs) merged.last() += g else merged += g
        }
        if (merged.size > 1 && merged.first().let { it.last().sourceUs - it.first().sourceUs } < minUs) {
            merged[1].addAll(0, merged.removeAt(0))
        }
        return merged.mapIndexed { i, g ->
            val start = if (i == 0) fromUs else g.first().sourceUs
            val end = if (i == merged.lastIndex) toUs else merged[i + 1].first().sourceUs
            Triple(start, end, SubjectSample(start, median(g.map { it.x }), median(g.map { it.y })))
        }
    }

    /**
     * Reframes the clips whose subject positions are known ([samplesFor] by asset id). Clips that
     * are rotated, already cropped, or whose picture already matches the frame are left alone.
     */
    fun apply(
        clips: List<Clip>,
        sizeFor: (String) -> Pair<Int, Int>?,
        samplesFor: (String) -> List<SubjectSample>?,
        targetAspect: Float,
    ): AutoEditResult {
        var changed = 0
        val out = mutableListOf<Clip>()
        for (c in clips) {
            val framing = ClipFraming.decode(c.framing)
            val size = sizeFor(c.assetId)
            val samples = samplesFor(c.assetId)
            if (size == null || samples.isNullOrEmpty() || framing.quarterTurns % 4 != 0 || !framing.crop.isNone || c.speedCurve.isNotBlank()) { out += c; continue }
            val parts = segments(samples, c.sourceInUs, c.sourceOutUs)
            if (parts.isEmpty()) { out += c; continue }
            var any = false
            parts.forEachIndexed { i, (from, to, at) ->
                val crop = cropFor(size.first, size.second, targetAspect, at.x, at.y)
                if (crop != null) any = true
                out += c.copy(
                    id = if (i == 0) c.id else UUID.randomUUID().toString(),
                    sourceInUs = from,
                    sourceOutUs = to,
                    transitionInType = if (i == 0) c.transitionInType else ClipTransition.CUT.name,
                    transitionInMs = if (i == 0) c.transitionInMs else 0,
                    transitionOutMs = if (i == parts.lastIndex) c.transitionOutMs else 0,
                    framing = (if (crop != null) framing.copy(crop = crop, fill = true) else framing).encode(),
                )
            }
            if (any) changed++
        }
        val laid = TimelineEditing.relayout(out)
        return AutoEditResult(laid, changed, note = if (changed == 0) "Nothing to reframe" else "Reframed $changed clip${if (changed == 1) "" else "s"} around the subject")
    }

    private fun median(v: List<Float>): Float {
        if (v.isEmpty()) return 0.5f
        val s = v.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2f
    }

    /** Subject centre of one matte frame (weighted by confidence), or null when nothing is found. */
    fun centre(mask: ByteArray, width: Int, height: Int): Pair<Float, Float>? {
        var sx = 0.0
        var sy = 0.0
        var sw = 0.0
        for (y in 0 until height) for (x in 0 until width) {
            val v = (mask[y * width + x].toInt() and 0xFF)
            if (v < 128) continue
            sx += x * v.toDouble(); sy += y * v.toDouble(); sw += v.toDouble()
        }
        if (sw < 255.0 * width * height * 0.01) return null
        return (sx / sw / max(1, width - 1)).toFloat().coerceIn(0f, 1f) to (sy / sw / max(1, height - 1)).toFloat().coerceIn(0f, 1f)
    }
}

