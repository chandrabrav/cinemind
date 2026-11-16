package com.cinemind.core.domain.editing

import com.cinemind.core.domain.model.Clip
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToLong

/** Edges cut off the picture, each a fraction (0..0.9) of the width/height. */
data class CropRect(val left: Float = 0f, val top: Float = 0f, val right: Float = 0f, val bottom: Float = 0f) {
    val isNone: Boolean get() = left == 0f && top == 0f && right == 0f && bottom == 0f
    /** Width/height of what remains, as a fraction of the original. */
    val keptWidth: Float get() = 1f - left - right
    val keptHeight: Float get() = 1f - top - bottom

    fun clamped(): CropRect {
        val l = left.coerceIn(0f, 0.9f)
        val t = top.coerceIn(0f, 0.9f)
        return CropRect(l, t, right.coerceIn(0f, 0.95f - l), bottom.coerceIn(0f, 0.95f - t))
    }

    companion object {
        /** Largest centred crop of a [srcW]×[srcH] picture with aspect [w]:[h]. */
        fun forAspect(srcW: Int, srcH: Int, w: Int, h: Int): CropRect {
            if (srcW <= 0 || srcH <= 0 || w <= 0 || h <= 0) return CropRect()
            val src = srcW.toFloat() / srcH
            val target = w.toFloat() / h
            return if (src > target) {
                val cut = (1f - target / src) / 2f
                CropRect(left = cut, right = cut)
            } else {
                val cut = (1f - src / target) / 2f
                CropRect(top = cut, bottom = cut)
            }
        }
    }
}

/**
 * Where the picture sits at one moment. [x]/[y]: offset of the centre in frame widths/heights
 * (0 = centred, +x right, +y down); [scale] 1 = fitted size; [rotation] degrees clockwise;
 * [opacity] 0..1 (over black on the main track). [sourceUs] is a time in the source footage,
 * like speed points, so keyframes stay on the same moment when the clip is trimmed or retimed.
 */
data class MotionKey(
    val sourceUs: Long,
    val x: Float = 0f,
    val y: Float = 0f,
    val scale: Float = 1f,
    val rotation: Float = 0f,
    val opacity: Float = 1f,
    val ease: SpeedEase = SpeedEase.EASE_IN_OUT,
) {
    val isIdentity: Boolean get() = x == 0f && y == 0f && scale == 1f && rotation == 0f && opacity == 1f
    fun sameLook(o: MotionKey) = x == o.x && y == o.y && scale == o.scale && rotation == o.rotation && opacity == o.opacity
}

/** Look of the picture at one instant (what a renderer needs). */
data class MotionLook(val x: Float = 0f, val y: Float = 0f, val scale: Float = 1f, val rotation: Float = 0f, val opacity: Float = 1f) {
    val isIdentity: Boolean get() = x == 0f && y == 0f && scale == 1f && rotation == 0f && opacity == 1f
}

/**
 * Everything about how a main-track clip is framed (Room v8 `clips.framing`): crop, 90° turns,
 * flips, fill (crop to fill the project frame instead of fitting with bars) and motion keyframes.
 * One key = a static position; two or more = animation between them.
 */
data class ClipFraming(
    val crop: CropRect = CropRect(),
    val quarterTurns: Int = 0,
    val flipH: Boolean = false,
    val flipV: Boolean = false,
    val fill: Boolean = false,
    val keys: List<MotionKey> = emptyList(),
    /** Green-screen key (removes a colour), or null. */
    val chroma: ChromaKey? = null,
    /** Shape the picture is cut to. */
    val mask: MaskShape = MaskShape.NONE,
    /** Soft edge of the mask, as a fraction of the picture (0..0.3). */
    val maskFeather: Float = 0.04f,
) {
    val isNone: Boolean get() = crop.isNone && quarterTurns % 4 == 0 && !flipH && !flipV && !fill && keys.all { it.isIdentity } && chroma == null && mask == MaskShape.NONE
    val hasKeyOrMask: Boolean get() = chroma != null || mask != MaskShape.NONE
    val hasMotion: Boolean get() = keys.size >= 2 && keys.zipWithNext().any { (a, b) -> !a.sameLook(b) }

    fun encode(): String {
        if (isNone) return ""
        val parts = mutableListOf<String>()
        if (!crop.isNone) parts += String.format(Locale.ROOT, "crop=%.4f,%.4f,%.4f,%.4f", crop.left, crop.top, crop.right, crop.bottom)
        if (quarterTurns % 4 != 0) parts += "turn=${((quarterTurns % 4) + 4) % 4}"
        if (flipH || flipV) parts += "flip=" + (if (flipH) "h" else "") + (if (flipV) "v" else "")
        if (fill) parts += "fill=1"
        chroma?.let { parts += String.format(Locale.ROOT, "chroma=%08x,%.3f,%.3f", it.color, it.strength, it.softness) }
        if (mask != MaskShape.NONE) parts += String.format(Locale.ROOT, "mask=%s,%.3f", mask.name, maskFeather)
        if (keys.isNotEmpty()) {
            parts += "keys=" + keys.sortedBy { it.sourceUs }.joinToString("|") {
                String.format(Locale.ROOT, "%d:%.4f:%.4f:%.4f:%.2f:%.3f:%s", it.sourceUs, it.x, it.y, it.scale, it.rotation, it.opacity, it.ease.name)
            }
        }
        return parts.joinToString(";")
    }

    companion object {
        const val MIN_SCALE = 0.1f
        const val MAX_SCALE = 5f

        fun decode(text: String?): ClipFraming {
            if (text.isNullOrBlank()) return ClipFraming()
            var f = ClipFraming()
            text.split(';').forEach { part ->
                val k = part.substringBefore('=')
                val v = part.substringAfter('=', "")
                when (k) {
                    "crop" -> v.split(',').mapNotNull { it.toFloatOrNull() }.takeIf { it.size == 4 }?.let {
                        f = f.copy(crop = CropRect(it[0], it[1], it[2], it[3]).clamped())
                    }
                    "turn" -> f = f.copy(quarterTurns = (v.toIntOrNull() ?: 0).mod(4))
                    "flip" -> f = f.copy(flipH = 'h' in v, flipV = 'v' in v)
                    "fill" -> f = f.copy(fill = v == "1")
                    "keys" -> f = f.copy(keys = v.split('|').mapNotNull(::decodeKey).sortedBy { it.sourceUs })
                    "chroma" -> v.split(',').let { p ->
                        val col = p.getOrNull(0)?.toLongOrNull(16)?.toInt()
                        if (col != null) f = f.copy(chroma = ChromaKey(col, p.getOrNull(1)?.toFloatOrNull()?.coerceIn(0f, 1f) ?: 0.6f, p.getOrNull(2)?.toFloatOrNull()?.coerceIn(0f, 1f) ?: 0.1f))
                    }
                    "mask" -> v.split(',').let { p ->
                        f = f.copy(
                            mask = MaskShape.entries.firstOrNull { it.name == p.getOrNull(0) } ?: MaskShape.NONE,
                            maskFeather = (p.getOrNull(1)?.toFloatOrNull() ?: 0.04f).coerceIn(0f, 0.3f),
                        )
                    }
                }
            }
            return f
        }

        private fun decodeKey(s: String): MotionKey? {
            val p = s.split(':')
            if (p.size < 6) return null
            val t = p[0].toLongOrNull() ?: return null
            val n = p.subList(1, 6).map { it.toFloatOrNull() ?: return null }
            return MotionKey(
                sourceUs = t,
                x = n[0].coerceIn(-2f, 2f),
                y = n[1].coerceIn(-2f, 2f),
                scale = n[2].coerceIn(MIN_SCALE, MAX_SCALE),
                rotation = n[3].coerceIn(-360f, 360f),
                opacity = n[4].coerceIn(0f, 1f),
                ease = SpeedEase.entries.firstOrNull { it.name == p.getOrNull(6) } ?: SpeedEase.EASE_IN_OUT,
            )
        }
    }
}

/**
 * Green screen: pixels close to [color] (in colour, ignoring brightness) become transparent.
 * [strength] 0..1 how much around the key colour is removed; [softness] 0..1 the soft edge.
 */
data class ChromaKey(val color: Int = GREEN, val strength: Float = 0.6f, val softness: Float = 0.1f) {
    companion object {
        const val GREEN = 0xFF00B140.toInt()
        const val BLUE = 0xFF0047BB.toInt()

        /** Chroma (Cb, Cr) of an ARGB colour, 0..1 each; brightness-independent. */
        fun chroma(argb: Int): Pair<Float, Float> {
            val r = ((argb shr 16) and 0xFF) / 255f
            val g = ((argb shr 8) and 0xFF) / 255f
            val b = (argb and 0xFF) / 255f
            return (-0.169f * r - 0.331f * g + 0.5f * b + 0.5f) to (0.5f * r - 0.419f * g - 0.081f * b + 0.5f)
        }

        /** How much of a pixel stays (0 = removed) for this key — the same formula as the GPU shader. */
        fun keep(pixel: Int, key: ChromaKey): Float {
            val (pu, pv) = chroma(pixel)
            val (ku, kv) = chroma(key.color)
            val d = kotlin.math.sqrt((pu - ku) * (pu - ku) + (pv - kv) * (pv - kv))
            val lo = key.strength * 0.3f
            val hi = lo + key.softness * 0.2f + 1e-4f
            val t = ((d - lo) / (hi - lo)).coerceIn(0f, 1f)
            return t * t * (3 - 2 * t)
        }
    }
}

/** Shapes a picture can be cut to. */
enum class MaskShape(val label: String) { NONE("None"), CIRCLE("Circle"), ROUNDED("Rounded"), HEART("Heart"), DIAMOND("Diamond") }

object TransformMath {

    fun ease(t: Float, e: SpeedEase): Float = when (e) {
        SpeedEase.LINEAR -> t
        SpeedEase.EASE_IN -> t * t
        SpeedEase.EASE_OUT -> 1f - (1f - t) * (1f - t)
        SpeedEase.EASE_IN_OUT -> (0.5f - 0.5f * cos(PI * t).toFloat())
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    private fun look(k: MotionKey) = MotionLook(k.x, k.y, k.scale, k.rotation, k.opacity)

    /** Look at absolute source time [sourceUs] (held before the first / after the last key). */
    fun lookAt(keys: List<MotionKey>, sourceUs: Long): MotionLook {
        if (keys.isEmpty()) return MotionLook()
        if (keys.size == 1 || sourceUs <= keys.first().sourceUs) return look(keys.first())
        if (sourceUs >= keys.last().sourceUs) return look(keys.last())
        val i = keys.indexOfLast { it.sourceUs <= sourceUs }
        val a = keys[i]
        val b = keys[i + 1]
        val t = ease(((sourceUs - a.sourceUs).toFloat() / (b.sourceUs - a.sourceUs).coerceAtLeast(1)).coerceIn(0f, 1f), a.ease)
        return MotionLook(lerp(a.x, b.x, t), lerp(a.y, b.y, t), lerp(a.scale, b.scale, t), lerp(a.rotation, b.rotation, t), lerp(a.opacity, b.opacity, t))
    }

    /** Look at [timelineOffsetUs] into [clip] (goes through the clip's speed to footage time). */
    fun lookAtTimeline(clip: Clip, framing: ClipFraming, timelineOffsetUs: Long): MotionLook =
        lookAt(framing.keys, clip.sourceInUs + SpeedMath.timelineToSource(clip, timelineOffsetUs.coerceAtLeast(0)))

    /**
     * Sets the look at [sourceUs]. [animate] on: replaces the key within [snapUs] of it, else adds
     * a key there (so the look animates between keys). Off: the clip gets one static look.
     */
    fun setLook(framing: ClipFraming, sourceUs: Long, look: MotionLook, animate: Boolean, snapUs: Long = 100_000): ClipFraming {
        val key = { t: Long, ease: SpeedEase -> MotionKey(t, look.x, look.y, look.scale.coerceIn(ClipFraming.MIN_SCALE, ClipFraming.MAX_SCALE), look.rotation, look.opacity.coerceIn(0f, 1f), ease) }
        if (!animate) return framing.copy(keys = listOf(key(sourceUs, SpeedEase.EASE_IN_OUT)))
        val i = framing.keys.indexOfFirst { kotlin.math.abs(it.sourceUs - sourceUs) <= snapUs }
        val next = framing.keys.toMutableList()
        if (i >= 0) next[i] = key(next[i].sourceUs, next[i].ease) else next += key(sourceUs, SpeedEase.EASE_IN_OUT)
        return framing.copy(keys = next.sortedBy { it.sourceUs })
    }

    fun removeKeyNear(framing: ClipFraming, sourceUs: Long, snapUs: Long = 100_000): ClipFraming {
        val i = framing.keys.indexOfFirst { kotlin.math.abs(it.sourceUs - sourceUs) <= snapUs }
        return if (i < 0) framing else framing.copy(keys = framing.keys.filterIndexed { j, _ -> j != i })
    }

    /** Displayed aspect (w/h) of a [srcW]×[srcH] source after crop and quarter turns. */
    fun shownAspect(srcW: Int, srcH: Int, framing: ClipFraming): Float {
        if (srcW <= 0 || srcH <= 0) return 1f
        val w = srcW * framing.crop.keptWidth
        val h = srcH * framing.crop.keptHeight
        return if (framing.quarterTurns % 2 == 1) h / w else w / h
    }
}

/** Ready-made moves over the whole clip (from its in-point to out-point); all stay editable. */
enum class MotionPreset(val label: String, private val from: MotionLook, private val to: MotionLook) {
    ZOOM_IN("Slow zoom in", MotionLook(scale = 1f), MotionLook(scale = 1.25f)),
    ZOOM_OUT("Slow zoom out", MotionLook(scale = 1.25f), MotionLook(scale = 1f)),
    PAN_RIGHT("Pan to the right", MotionLook(x = 0.08f, scale = 1.2f), MotionLook(x = -0.08f, scale = 1.2f)),
    PAN_LEFT("Pan to the left", MotionLook(x = -0.08f, scale = 1.2f), MotionLook(x = 0.08f, scale = 1.2f)),
    RISE("Rise", MotionLook(y = 0.06f, scale = 1.15f), MotionLook(y = -0.06f, scale = 1.15f)),
    PUNCH_IN("Punch in", MotionLook(scale = 1f), MotionLook(scale = 1.6f)),
    TILT("Dutch tilt", MotionLook(scale = 1.15f, rotation = -4f), MotionLook(scale = 1.15f, rotation = 4f)),
    FADE_IN("Fade up from black", MotionLook(opacity = 0f), MotionLook(opacity = 1f)),
    ;

    fun keysFor(clip: Clip): List<MotionKey> {
        val ease = if (this == PUNCH_IN) SpeedEase.EASE_OUT else SpeedEase.EASE_IN_OUT
        val end = if (this == FADE_IN) clip.sourceInUs + ((clip.sourceOutUs - clip.sourceInUs) / 3) else clip.sourceOutUs
        val start = clip.sourceInUs
        val punchEnd = if (this == PUNCH_IN) start + ((clip.sourceOutUs - start) * 0.15).roundToLong().coerceAtLeast(1) else end
        fun k(t: Long, l: MotionLook) = MotionKey(t, l.x, l.y, l.scale, l.rotation, l.opacity, ease)
        return listOf(k(start, from), k(punchEnd, to))
    }
}
