package com.cinemind.core.domain.color

import java.util.Locale
import kotlin.math.floor

/** The file isn't a usable 3D LUT (the message says why, for the user). */
class LutFormatException(message: String) : Exception(message)

/**
 * A 3D colour lookup table as in Adobe/Resolve `.cube` files: [size]³ RGB entries, red changing
 * fastest (index = (b·size + g)·size + r), values 0..1. Pure; unit-tested.
 */
class CubeLut(val size: Int, val data: FloatArray, val title: String = "") {

    init {
        require(size in MIN_SIZE..MAX_SIZE) { "LUT size $size" }
        require(data.size == size * size * size * 3) { "LUT data length" }
    }

    private fun idx(r: Int, g: Int, b: Int) = ((b * size + g) * size + r) * 3

    /** Trilinear lookup of a colour (components 0..1, clamped). */
    fun sample(r: Float, g: Float, b: Float, out: FloatArray = FloatArray(3)): FloatArray {
        val n = size - 1
        val fr = r.coerceIn(0f, 1f) * n
        val fg = g.coerceIn(0f, 1f) * n
        val fb = b.coerceIn(0f, 1f) * n
        val r0 = floor(fr).toInt().coerceAtMost(n - 1).coerceAtLeast(0)
        val g0 = floor(fg).toInt().coerceAtMost(n - 1).coerceAtLeast(0)
        val b0 = floor(fb).toInt().coerceAtMost(n - 1).coerceAtLeast(0)
        val dr = fr - r0
        val dg = fg - g0
        val db = fb - b0
        for (c in 0..2) {
            val c000 = data[idx(r0, g0, b0) + c]
            val c100 = data[idx(r0 + 1, g0, b0) + c]
            val c010 = data[idx(r0, g0 + 1, b0) + c]
            val c110 = data[idx(r0 + 1, g0 + 1, b0) + c]
            val c001 = data[idx(r0, g0, b0 + 1) + c]
            val c101 = data[idx(r0 + 1, g0, b0 + 1) + c]
            val c011 = data[idx(r0, g0 + 1, b0 + 1) + c]
            val c111 = data[idx(r0 + 1, g0 + 1, b0 + 1) + c]
            val x00 = c000 + (c100 - c000) * dr
            val x10 = c010 + (c110 - c010) * dr
            val x01 = c001 + (c101 - c001) * dr
            val x11 = c011 + (c111 - c011) * dr
            val y0 = x00 + (x10 - x00) * dg
            val y1 = x01 + (x11 - x01) * dg
            out[c] = y0 + (y1 - y0) * db
        }
        return out
    }

    /** Entry at grid position (for building GPU textures). */
    fun at(r: Int, g: Int, b: Int, c: Int): Float = data[idx(r, g, b) + c]

    /** As `.cube` text. */
    fun toCube(): String = buildString {
        if (title.isNotEmpty()) appendLine("TITLE \"${title.replace("\"", "'")}\"")
        appendLine("LUT_3D_SIZE $size")
        var i = 0
        while (i < data.size) {
            appendLine(String.format(Locale.ROOT, "%.6f %.6f %.6f", data[i], data[i + 1], data[i + 2]))
            i += 3
        }
    }

    companion object {
        const val MIN_SIZE = 2
        const val MAX_SIZE = 65

        fun identity(size: Int): CubeLut {
            val d = FloatArray(size * size * size * 3)
            var i = 0
            for (b in 0 until size) for (g in 0 until size) for (r in 0 until size) {
                d[i++] = r / (size - 1f); d[i++] = g / (size - 1f); d[i++] = b / (size - 1f)
            }
            return CubeLut(size, d)
        }

        /** Builds a LUT by evaluating [f] (rgb in → rgb out, 0..1) on the grid. */
        fun bake(size: Int, title: String = "", f: (FloatArray, FloatArray) -> Unit): CubeLut {
            val d = FloatArray(size * size * size * 3)
            val inp = FloatArray(3)
            val out = FloatArray(3)
            var i = 0
            for (b in 0 until size) for (g in 0 until size) for (r in 0 until size) {
                inp[0] = r / (size - 1f); inp[1] = g / (size - 1f); inp[2] = b / (size - 1f)
                f(inp, out)
                d[i++] = out[0].coerceIn(0f, 1f); d[i++] = out[1].coerceIn(0f, 1f); d[i++] = out[2].coerceIn(0f, 1f)
            }
            return CubeLut(size, d, title)
        }

        /**
         * Parses `.cube` text (Adobe/IRIDAS 3D format). Accepts comments, TITLE, DOMAIN_MIN/MAX
         * (rescaled to 0..1) and sizes 2..65. Rejects 1D LUTs, wrong counts and junk, with a reason.
         */
        fun parse(text: String): CubeLut {
            var size = 0
            var title = ""
            var min = floatArrayOf(0f, 0f, 0f)
            var max = floatArrayOf(1f, 1f, 1f)
            val values = ArrayList<Float>(35_937 * 3)
            for (raw in text.lineSequence()) {
                val line = raw.substringBefore('#').trim()
                if (line.isEmpty()) continue
                val upper = line.uppercase(Locale.ROOT)
                when {
                    upper.startsWith("TITLE") -> title = line.substringAfter("TITLE").trim().trim('"')
                    upper.startsWith("LUT_1D_SIZE") -> throw LutFormatException("This is a 1D LUT; only 3D .cube LUTs are supported.")
                    upper.startsWith("LUT_3D_SIZE") -> size = line.split(Regex("\\s+")).getOrNull(1)?.toIntOrNull()
                        ?: throw LutFormatException("LUT_3D_SIZE is not a number.")
                    upper.startsWith("DOMAIN_MIN") -> min = floats(line, "DOMAIN_MIN")
                    upper.startsWith("DOMAIN_MAX") -> max = floats(line, "DOMAIN_MAX")
                    upper.first().isLetter() -> Unit // other keywords (LUT_IN_VIDEO_RANGE…): ignored
                    else -> {
                        val parts = line.split(Regex("\\s+"))
                        if (parts.size != 3) throw LutFormatException("Expected 3 numbers per line, found: \"${line.take(40)}\"")
                        for (p in parts) values += p.toFloatOrNull() ?: throw LutFormatException("Not a number: \"$p\"")
                    }
                }
            }
            if (size == 0) throw LutFormatException("No LUT_3D_SIZE line: not a 3D .cube LUT.")
            if (size !in MIN_SIZE..MAX_SIZE) throw LutFormatException("LUT size $size is not supported (2–65).")
            val expected = size * size * size * 3
            if (values.size != expected) throw LutFormatException("Expected ${expected / 3} colours for size $size, found ${values.size / 3}.")
            val data = FloatArray(expected)
            for (i in 0 until expected) {
                val c = i % 3
                val span = (max[c] - min[c]).takeIf { it > 0f } ?: 1f
                data[i] = ((values[i] - min[c]) / span).coerceIn(0f, 1f)
            }
            return CubeLut(size, data, title)
        }

        private fun floats(line: String, key: String): FloatArray {
            val p = line.substringAfter(key).trim().split(Regex("\\s+")).mapNotNull { it.toFloatOrNull() }
            if (p.size != 3) throw LutFormatException("$key needs 3 numbers.")
            return p.toFloatArray()
        }
    }
}
