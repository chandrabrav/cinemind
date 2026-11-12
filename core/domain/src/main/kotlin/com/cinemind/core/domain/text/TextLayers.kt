package com.cinemind.core.domain.text

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.cinemind.core.domain.captions.CaptionFont
import com.cinemind.core.domain.model.Project
import java.util.Locale
import java.util.UUID

enum class TextKind { TEXT, STICKER }

/**
 * A title, label or sticker placed freely on the video (schema v9, table `text_layers`). Times are
 * timeline times. Position is the centre as a fraction of the frame (0..1); [size] is the text
 * height as a fraction of the frame's short side, so it looks the same at every resolution.
 * [lane] orders layers (higher = drawn on top). [style] is a [TextStyle] encoding.
 */
@Entity(
    tableName = "text_layers",
    foreignKeys = [ForeignKey(entity = Project::class, parentColumns = ["id"], childColumns = ["projectId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["projectId"])],
)
data class TextLayer(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val projectId: String,
    val startUs: Long,
    val endUs: Long,
    val kind: String = TextKind.TEXT.name,
    val text: String,
    val x: Float = 0.5f,
    val y: Float = 0.3f,
    val size: Float = 0.08f,
    val rotation: Float = 0f,
    val style: String = "",
    val lane: Int = 0,
) {
    val kindEnum: TextKind get() = if (kind == TextKind.STICKER.name) TextKind.STICKER else TextKind.TEXT
    val styleObj: TextStyle get() = TextStyle.decode(style)
    val lengthUs: Long get() = endUs - startUs
}

enum class TextAnim(val label: String) {
    NONE("None"), FADE("Fade"), POP("Pop"), SLIDE_UP("Slide up"), SLIDE_LEFT("Slide in"), ZOOM("Zoom"), TYPEWRITER("Typewriter"), BOUNCE("Bounce"),
}

/** Looks of a text layer. Colours are ARGB ints. */
data class TextStyle(
    val font: CaptionFont = CaptionFont.SANS,
    val weight: Int = 800,
    val color: Int = WHITE,
    val outline: Float = 0f,
    val outlineColor: Int = BLACK,
    val shadow: Boolean = true,
    val glow: Boolean = false,
    val boxColor: Int = BLACK,
    val boxOpacity: Float = 0f,
    val uppercase: Boolean = false,
    /** Extra space between letters, in ems. */
    val spacing: Float = 0f,
    val align: Int = 0, // -1 left, 0 centre, 1 right
    val animIn: TextAnim = TextAnim.POP,
    val animOut: TextAnim = TextAnim.FADE,
) {
    fun encode(): String = String.format(
        Locale.ROOT,
        "font=%s;w=%d;c=%08x;o=%.3f;oc=%08x;sh=%d;gl=%d;bc=%08x;bo=%.3f;up=%d;sp=%.3f;al=%d;in=%s;out=%s",
        font.name, weight, color, outline, outlineColor, if (shadow) 1 else 0, if (glow) 1 else 0, boxColor, boxOpacity,
        if (uppercase) 1 else 0, spacing, align, animIn.name, animOut.name,
    )

    companion object {
        const val WHITE = 0xFFFFFFFF.toInt()
        const val BLACK = 0xFF000000.toInt()

        fun decode(text: String?): TextStyle {
            if (text.isNullOrBlank()) return TextStyle()
            val m = text.split(';').associate { it.substringBefore('=') to it.substringAfter('=', "") }
            val d = TextStyle()
            fun color(k: String, def: Int) = m[k]?.toLongOrNull(16)?.toInt() ?: def
            return TextStyle(
                font = CaptionFont.entries.firstOrNull { it.name == m["font"] } ?: d.font,
                weight = (m["w"]?.toIntOrNull() ?: d.weight).coerceIn(100, 900),
                color = color("c", d.color),
                outline = (m["o"]?.toFloatOrNull() ?: d.outline).coerceIn(0f, 0.4f),
                outlineColor = color("oc", d.outlineColor),
                shadow = m["sh"]?.let { it == "1" } ?: d.shadow,
                glow = m["gl"] == "1",
                boxColor = color("bc", d.boxColor),
                boxOpacity = (m["bo"]?.toFloatOrNull() ?: d.boxOpacity).coerceIn(0f, 1f),
                uppercase = m["up"] == "1",
                spacing = (m["sp"]?.toFloatOrNull() ?: 0f).coerceIn(-0.1f, 1f),
                align = (m["al"]?.toIntOrNull() ?: 0).coerceIn(-1, 1),
                animIn = TextAnim.entries.firstOrNull { it.name == m["in"] } ?: d.animIn,
                animOut = TextAnim.entries.firstOrNull { it.name == m["out"] } ?: d.animOut,
            )
        }
    }
}

/** Ready-made title looks (all original). [size]/[y] are suggestions applied with the look. */
enum class TextPreset(val label: String, val style: TextStyle, val size: Float, val y: Float) {
    BOLD_TITLE("Bold title", TextStyle(weight = 900, outline = 0.08f, animIn = TextAnim.POP), 0.11f, 0.3f),
    CINEMA("Cinema", TextStyle(font = CaptionFont.SERIF, weight = 400, uppercase = true, spacing = 0.35f, shadow = true, animIn = TextAnim.FADE), 0.06f, 0.5f),
    LOWER_THIRD("Lower third", TextStyle(weight = 700, boxColor = 0xFF111111.toInt(), boxOpacity = 0.75f, align = -1, shadow = false, animIn = TextAnim.SLIDE_LEFT), 0.05f, 0.8f),
    NEON("Neon", TextStyle(weight = 700, color = 0xFF7DF9FF.toInt(), glow = true, shadow = false, animIn = TextAnim.ZOOM), 0.09f, 0.35f),
    IMPACT("Impact", TextStyle(font = CaptionFont.CONDENSED, weight = 900, uppercase = true, color = 0xFFFFD43B.toInt(), outline = 0.12f, animIn = TextAnim.BOUNCE), 0.13f, 0.4f),
    MINIMAL("Minimal", TextStyle(weight = 300, shadow = true, spacing = 0.08f, animIn = TextAnim.FADE), 0.055f, 0.5f),
    LABEL("Label", TextStyle(weight = 800, color = BLACKISH, boxColor = WHITEISH, boxOpacity = 1f, shadow = false, animIn = TextAnim.POP), 0.05f, 0.2f),
    TYPEWRITER("Typewriter", TextStyle(font = CaptionFont.MONO, weight = 500, shadow = true, animIn = TextAnim.TYPEWRITER), 0.055f, 0.5f),
    HANDWRITTEN("Casual", TextStyle(font = CaptionFont.CASUAL, weight = 600, color = 0xFFFFF4E0.toInt(), shadow = true, animIn = TextAnim.SLIDE_UP), 0.08f, 0.6f),
    ;

    companion object {
        private const val BLACKISH = 0xFF111111.toInt()
        private const val WHITEISH = 0xFFFAFAFA.toInt()
    }
}

/** Sticker sets: emoji and symbols drawn with the phone's own emoji font (no downloads). */
object Stickers {
    val sets: List<Pair<String, List<String>>> = listOf(
        "Reactions" to listOf("😂", "😍", "🔥", "😮", "😎", "🥹", "🤯", "👏", "🙌", "💯", "❤️", "✨", "🎉", "👀", "😭", "🤩"),
        "Travel" to listOf("✈️", "🏔️", "🏖️", "🌅", "🗺️", "📍", "🚗", "🏕️", "🌴", "📸", "🧳", "🌍"),
        "Food" to listOf("🍕", "🍔", "☕", "🍜", "🍰", "🥗", "🍹", "🌮", "🍛", "🍦", "🥂", "🍩"),
        "Celebrate" to listOf("🎂", "🎈", "🎁", "💍", "🪔", "🎆", "🥳", "💐", "🕺", "💃", "🏆", "🎊"),
        "Fitness" to listOf("💪", "🏃", "🏋️", "🚴", "🧘", "⚽", "🏏", "🥇", "⏱️", "🔝"),
        "Arrows & shapes" to listOf("➜", "⬅️", "⬆️", "⬇️", "↗️", "⭐", "★", "●", "■", "▲", "♥", "✔️", "❌", "❗", "❓", "💬"),
    )
}

/** Animation state of a layer at one instant: progress of the entrance and exit (0..1). */
data class TextFrame(val layer: TextLayer, val enter: Float, val exit: Float)

object TextLayerEditing {

    const val MIN_US = 300_000L
    const val DEFAULT_US = 3_000_000L
    const val ANIM_US = 400_000L

    /** Layers visible at [timeUs], bottom first, with animation progress. */
    fun framesAt(layers: List<TextLayer>, timeUs: Long): List<TextFrame> =
        layers.filter { timeUs >= it.startUs && timeUs < it.endUs && it.text.isNotBlank() }
            .sortedWith(compareBy({ it.lane }, { it.startUs }))
            .map { l ->
                val anim = minOf(ANIM_US, l.lengthUs / 3).coerceAtLeast(1)
                // Typewriter types for up to 1.2 s (half the layer at most).
                val inAnim = if (l.styleObj.animIn == TextAnim.TYPEWRITER) minOf(1_200_000L, l.lengthUs / 2).coerceAtLeast(1) else anim
                val enter = ((timeUs - l.startUs).toFloat() / inAnim).coerceIn(0f, 1f)
                val exit = (1f - (l.endUs - timeUs).toFloat() / anim).coerceIn(0f, 1f)
                TextFrame(l, enter, exit)
            }

    /** New layer at [atUs] lasting [DEFAULT_US] (shortened to fit [timelineUs]); on top of the others. */
    fun create(projectId: String, existing: List<TextLayer>, atUs: Long, timelineUs: Long, text: String, kind: TextKind, preset: TextPreset?): TextLayer {
        val start = atUs.coerceIn(0, (timelineUs - MIN_US).coerceAtLeast(0))
        val end = (start + DEFAULT_US).coerceAtMost(timelineUs.coerceAtLeast(start + MIN_US))
        val lane = (existing.maxOfOrNull { it.lane } ?: -1) + 1
        return if (kind == TextKind.STICKER) {
            TextLayer(projectId = projectId, startUs = start, endUs = end, kind = kind.name, text = text, x = 0.5f, y = 0.45f, size = 0.16f,
                style = TextStyle(shadow = false, animIn = TextAnim.POP, animOut = TextAnim.FADE).encode(), lane = lane)
        } else {
            val p = preset ?: TextPreset.BOLD_TITLE
            TextLayer(projectId = projectId, startUs = start, endUs = end, text = text, y = p.y, size = p.size, style = p.style.encode(), lane = lane)
        }
    }

    /** Moves a layer in time by [deltaUs], keeping its length and staying inside 0..[timelineUs]. */
    fun move(layers: List<TextLayer>, id: String, deltaUs: Long, timelineUs: Long): List<TextLayer> = layers.map { l ->
        if (l.id != id) l else {
            val len = l.lengthUs
            val start = (l.startUs + deltaUs).coerceIn(0, (timelineUs - len).coerceAtLeast(0))
            l.copy(startUs = start, endUs = start + len)
        }
    }

    /** Changes start/end by the deltas; at least [MIN_US] long, inside 0..[timelineUs]. */
    fun trim(layers: List<TextLayer>, id: String, startDeltaUs: Long, endDeltaUs: Long, timelineUs: Long): List<TextLayer> = layers.map { l ->
        if (l.id != id) l else {
            val start = (l.startUs + startDeltaUs).coerceIn(0, (l.endUs - MIN_US).coerceAtLeast(0))
            val end = (l.endUs + endDeltaUs).coerceIn(start + MIN_US, maxOf(timelineUs, start + MIN_US))
            l.copy(startUs = start, endUs = end)
        }
    }

    fun duplicate(layers: List<TextLayer>, id: String, timelineUs: Long, newId: () -> String = { UUID.randomUUID().toString() }): List<TextLayer> {
        val l = layers.firstOrNull { it.id == id } ?: return layers
        val start = l.endUs.coerceAtMost((timelineUs - l.lengthUs).coerceAtLeast(0))
        val copy = l.copy(id = newId(), startUs = start, endUs = start + l.lengthUs, lane = (layers.maxOf { it.lane }) + 1,
            y = if (start == l.startUs) (l.y + 0.08f).coerceAtMost(0.9f) else l.y)
        return layers + copy
    }

    fun bringToFront(layers: List<TextLayer>, id: String): List<TextLayer> {
        val top = layers.maxOfOrNull { it.lane } ?: 0
        return layers.map { if (it.id == id) it.copy(lane = top + 1) else it }
    }

    /** Keeps layers inside a shorter timeline (after clips are cut): shortened, or removed if nothing is left. */
    fun fitTo(layers: List<TextLayer>, timelineUs: Long): List<TextLayer> = layers.mapNotNull { l ->
        when {
            l.startUs >= timelineUs - MIN_US / 2 -> null
            l.endUs > timelineUs -> l.copy(endUs = timelineUs)
            else -> l
        }
    }
}
