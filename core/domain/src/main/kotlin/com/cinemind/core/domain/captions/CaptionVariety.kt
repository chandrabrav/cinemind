package com.cinemind.core.domain.captions

import com.cinemind.core.domain.aiedit.SpeechGraphics

/**
 * "Mix it up" captions: instead of one look for the whole video, phrases take turns between the
 * project's own style and a few contrasting designs (a punchy box, a big pop, an elegant serif, a
 * neon line, a marker scrawl), and each phrase's key word stands out larger in an accent colour.
 * Deterministic per phrase, so the preview and the export always agree.
 */
object CaptionVariety {

    /** How much bigger the key word is drawn. */
    const val EMPHASIS_SCALE = 1.35f

    data class Look(val style: CaptionStyle, /** Index of the key word in the phrase, or -1. */ val emphasis: Int, val emphasisColor: Int)

    private const val YELLOW = 0xFFFFE600.toInt()
    private const val CYAN = 0xFF7DF9FF.toInt()
    private const val PINK = 0xFFFF5C93.toInt()
    private const val ORANGE = 0xFFFF8A3D.toInt()
    private const val BLACK = 0xFF000000.toInt()
    private const val WHITE = 0xFFFFFFFF.toInt()

    /** Number of designs a phrase can take (the first three keep the project's own style). */
    const val DESIGNS = 8

    /** The look for the phrase [words] of caption [captionId]. Returns [base] untouched when variety is off. */
    fun look(base: CaptionStyle, captionId: String, words: List<String>): Look {
        if (!base.variety || words.isEmpty()) return Look(base, -1, base.highlightColor)
        val key = (captionId + "|" + words.joinToString(" ")).hashCode()
        val design = Math.floorMod(key, DESIGNS)
        // The bundled display fonts have no Devanagari: keep the project's font for Hindi script.
        val devanagari = words.any { w -> w.any { it in 'ऀ'..'ॿ' } }
        fun font(f: CaptionFont) = if (devanagari) base.font else f.name
        val style = when (design) {
            3 -> base.copy(font = font(CaptionFont.ANTON), uppercase = true, textColor = BLACK, outline = 0f, shadow = false,
                backgroundColor = YELLOW, backgroundOpacity = 1f, highlightColor = WHITE)
            4 -> base.copy(font = font(CaptionFont.BANGERS), uppercase = true, size = base.size * 1.22f, textColor = YELLOW,
                outline = 0.16f, outlineColor = BLACK, highlightColor = WHITE)
            5 -> base.copy(font = font(CaptionFont.ABRIL), uppercase = false, weight = 400, size = base.size * 0.95f, textColor = WHITE,
                outline = 0f, shadow = true, backgroundOpacity = 0f, highlightColor = ORANGE)
            6 -> base.copy(font = font(CaptionFont.POPPINS), uppercase = true, weight = 800, textColor = CYAN, outline = 0.06f,
                outlineColor = 0xFF003344.toInt(), shadow = true, highlightColor = WHITE)
            7 -> base.copy(font = font(CaptionFont.MARKER), uppercase = false, size = base.size * 1.1f, textColor = WHITE,
                outline = 0.1f, outlineColor = BLACK, highlightColor = PINK)
            else -> base
        }
        val accent = when (design) {
            3 -> 0xFFD7263D.toInt()
            4 -> WHITE
            5 -> ORANGE
            6 -> PINK
            7 -> YELLOW
            else -> listOf(YELLOW, CYAN, PINK)[design.coerceIn(0, 2)]
        }
        return Look(style, emphasisOf(words), accent)
    }

    /** The phrase's key word (numbers, long and unusual words win), or -1 when nothing stands out. */
    fun emphasisOf(words: List<String>): Int {
        if (words.size < 2) return -1
        val best = words.indices.maxByOrNull { SpeechGraphics.score(words[it], emptySet()) } ?: return -1
        return if (SpeechGraphics.score(words[best], emptySet()) >= 3) best else -1
    }
}
