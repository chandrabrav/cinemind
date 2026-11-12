package com.cinemind.core.domain.color

import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * A clip's colour grade, stored as text in `clips.grade` (schema v6). All values are 0 when neutral.
 * Ranges: exposure −2..2 stops, the others −1..1 (fade 0..1), LUT intensity 0..1.
 */
data class ColorGrade(
    /** Look id from the LUT library ("builtin:…", "import:…", "gmic:…"), or null for none. */
    val lutId: String? = null,
    val lutIntensity: Float = 1f,
    val exposure: Float = 0f,
    val contrast: Float = 0f,
    val saturation: Float = 0f,
    val vibrance: Float = 0f,
    val temperature: Float = 0f,
    val tint: Float = 0f,
    val highlights: Float = 0f,
    val shadows: Float = 0f,
    val fade: Float = 0f,
) {
    val hasLook: Boolean get() = lutId != null && lutIntensity > 0.001f
    val hasAdjustments: Boolean
        get() = listOf(exposure, contrast, saturation, vibrance, temperature, tint, highlights, shadows, fade).any { kotlin.math.abs(it) > 0.001f }
    val isNeutral: Boolean get() = !hasLook && !hasAdjustments

    fun encode(): String = if (isNeutral) "" else buildList {
        lutId?.let { add("lut=$it") }
        add("int=${f(lutIntensity)}")
        if (exposure != 0f) add("exp=${f(exposure)}")
        if (contrast != 0f) add("con=${f(contrast)}")
        if (saturation != 0f) add("sat=${f(saturation)}")
        if (vibrance != 0f) add("vib=${f(vibrance)}")
        if (temperature != 0f) add("tmp=${f(temperature)}")
        if (tint != 0f) add("tnt=${f(tint)}")
        if (highlights != 0f) add("hi=${f(highlights)}")
        if (shadows != 0f) add("sh=${f(shadows)}")
        if (fade != 0f) add("fade=${f(fade)}")
    }.joinToString(";")

    companion object {
        val NONE = ColorGrade()

        private fun f(v: Float) = String.format(Locale.ROOT, "%.3f", v)

        /** Lenient: unknown keys are ignored, bad numbers fall back to neutral. */
        fun decode(text: String?): ColorGrade {
            if (text.isNullOrBlank()) return NONE
            val m = text.split(';').mapNotNull { p -> p.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0].trim() to it[1].trim() } }.toMap()
            fun n(k: String, lo: Float, hi: Float, def: Float = 0f) = m[k]?.toFloatOrNull()?.coerceIn(lo, hi) ?: def
            return ColorGrade(
                lutId = m["lut"]?.takeIf { it.isNotEmpty() },
                lutIntensity = n("int", 0f, 1f, 1f),
                exposure = n("exp", -2f, 2f),
                contrast = n("con", -1f, 1f),
                saturation = n("sat", -1f, 1f),
                vibrance = n("vib", -1f, 1f),
                temperature = n("tmp", -1f, 1f),
                tint = n("tnt", -1f, 1f),
                highlights = n("hi", -1f, 1f),
                shadows = n("sh", -1f, 1f),
                fade = n("fade", 0f, 1f),
            )
        }
    }
}

/**
 * The colour pipeline, identical in preview and export because it is baked into one 3D LUT per
 * grade: white balance and exposure, contrast, highlights/shadows, saturation/vibrance, fade,
 * then the look LUT mixed in by its intensity. Works in display (gamma) space except exposure,
 * which is applied in linear light. Pure; unit-tested.
 */
object ColorMath {

    const val BAKE_SIZE = 33

    fun luma(r: Float, g: Float, b: Float) = 0.2126f * r + 0.7152f * g + 0.0722f * b

    /** Applies the grade's sliders to one colour (in place). */
    fun adjust(c: FloatArray, g: ColorGrade) {
        var r = c[0]; var gr = c[1]; var b = c[2]
        if (g.exposure != 0f) {
            val k = 2f.pow(g.exposure)
            r = toGamma(toLinear(r) * k); gr = toGamma(toLinear(gr) * k); b = toGamma(toLinear(b) * k)
        }
        if (g.temperature != 0f) { r *= 1f + 0.12f * g.temperature; b *= 1f - 0.12f * g.temperature }
        if (g.tint != 0f) { gr *= 1f - 0.10f * g.tint; r *= 1f + 0.04f * g.tint; b *= 1f + 0.04f * g.tint }
        if (g.contrast != 0f) {
            val k = 1f + g.contrast * 0.8f
            r = 0.5f + (r - 0.5f) * k; gr = 0.5f + (gr - 0.5f) * k; b = 0.5f + (b - 0.5f) * k
        }
        if (g.highlights != 0f || g.shadows != 0f) {
            val l = luma(r, gr, b).coerceIn(0f, 1f)
            val delta = g.highlights * 0.3f * l * l + g.shadows * 0.3f * (1f - l) * (1f - l)
            r += delta; gr += delta; b += delta
        }
        if (g.saturation != 0f || g.vibrance != 0f) {
            val l = luma(r, gr, b)
            val sat = (max(r, max(gr, b)) - min(r, min(gr, b))).coerceIn(0f, 1f)
            val k = (1f + g.saturation) * (1f + g.vibrance * (1f - sat))
            r = l + (r - l) * k; gr = l + (gr - l) * k; b = l + (b - l) * k
        }
        if (g.fade > 0f) {
            val lift = 0.22f * g.fade
            r = lift + r * (1f - lift); gr = lift + gr * (1f - lift); b = lift + b * (1f - lift)
        }
        c[0] = r.coerceIn(0f, 1f); c[1] = gr.coerceIn(0f, 1f); c[2] = b.coerceIn(0f, 1f)
    }

    /**
     * One LUT for the whole grade: sliders, then [look] mixed by the intensity. Null when the grade
     * changes nothing (no effect needed).
     */
    fun bake(grade: ColorGrade, look: CubeLut?, size: Int = BAKE_SIZE): CubeLut? {
        val useLook = look != null && grade.hasLook
        if (!useLook && !grade.hasAdjustments) return null
        val tmp = FloatArray(3)
        return CubeLut.bake(size, grade.lutId ?: "grade") { inp, out ->
            out[0] = inp[0]; out[1] = inp[1]; out[2] = inp[2]
            adjust(out, grade)
            if (useLook) {
                look!!.sample(out[0], out[1], out[2], tmp)
                val k = grade.lutIntensity
                out[0] += (tmp[0] - out[0]) * k; out[1] += (tmp[1] - out[1]) * k; out[2] += (tmp[2] - out[2]) * k
            }
        }
    }

    fun toLinear(v: Float): Float = v.coerceAtLeast(0f).pow(2.2f)
    fun toGamma(v: Float): Float = v.coerceAtLeast(0f).pow(1f / 2.2f)
}
