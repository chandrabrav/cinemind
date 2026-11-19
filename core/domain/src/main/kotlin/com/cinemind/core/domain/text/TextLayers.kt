package com.cinemind.core.domain.text

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.cinemind.core.domain.captions.CaptionFont
import com.cinemind.core.domain.model.Project
import java.util.Locale
import java.util.UUID

enum class TextKind { TEXT, STICKER, /** A bar that fills as the video plays (style colour; size = thickness). */ PROGRESS }

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
    val kindEnum: TextKind get() = TextKind.entries.firstOrNull { it.name == kind } ?: TextKind.TEXT
    val styleObj: TextStyle get() = TextStyle.decode(style)
    val lengthUs: Long get() = endUs - startUs
}

enum class TextAnim(val label: String) {
    NONE("None"), FADE("Fade"), POP("Pop"), SLIDE_UP("Slide up"), SLIDE_LEFT("Slide in"), ZOOM("Zoom"), TYPEWRITER("Typewriter"), BOUNCE("Bounce"),
    // Motion-graphics entrances (letter / word staggered ones take up to 1.2 s, like the typewriter).
    CASCADE("Letter drop"), WORDS("Word by word"), WIPE("Wipe"), TRACK("Tracking"), SPIN("Spin"), STAMP("Stamp"), GLITCH("Glitch"),
    ;

    /** Builds letter by letter or word by word, so it needs a longer entrance. */
    val staggered: Boolean get() = this == TYPEWRITER || this == CASCADE || this == WORDS
}

/** Emphasis that keeps moving while the text is on screen (after the entrance). */
enum class TextLoop(val label: String) {
    NONE("None"), PULSE("Pulse"), SHAKE("Shake"), WAVE("Wave"), FLOAT("Float"), FLICKER("Neon flicker"), GLITCH("Glitch"),
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
    /** Drawn under picture-in-picture overlays (default: above everything). */
    val belowOverlays: Boolean = false,
    /** Second colour of a top-to-bottom gradient fill; 0 = solid [color]. */
    val color2: Int = 0,
    /** 3D extrusion depth (fraction of text size, 0 = flat) and its colour. */
    val extrude: Float = 0f,
    val extrudeColor: Int = 0xFF7A1F1F.toInt(),
    /** Emphasis after the entrance. */
    val loop: TextLoop = TextLoop.NONE,
    /**
     * Drawn behind the person / subject (needs the clip's subject cutout). Without a cutout it is
     * drawn normally, under the picture-in-picture.
     */
    val behindSubject: Boolean = false,
    /**
     * Motion path: where the text starts (centre x, y as 0..1 of the frame, and size); it glides
     * to its own position and size over its duration. Null = it stays put.
     */
    val from: TextFrom? = null,
    /**
     * Per-overlay stacking: the picture-in-picture clips (ids) this title sits behind while they
     * are on screen. In front of every other overlay. Ignored when [belowOverlays] is set.
     */
    val belowClips: Set<String> = emptySet(),
) {
    val hasGradient: Boolean get() = color2 != 0

    /** Behind some overlay clips (not all): drawn under or over the picture-in-picture depending on the time. */
    val partlyBelow: Boolean get() = !belowOverlays && belowClips.isNotEmpty()

    /** Whether this title is drawn under the picture-in-picture while overlay clip [overlayId] shows. */
    fun isBelow(overlayId: String?): Boolean = belowOverlays || (overlayId != null && overlayId in belowClips)

    /** Drawn into the video picture (under overlays or behind the subject) rather than on top of everything. */
    val drawnInPicture: Boolean get() = belowOverlays || behindSubject

    fun encode(): String = String.format(
        Locale.ROOT,
        "font=%s;w=%d;c=%08x;o=%.3f;oc=%08x;sh=%d;gl=%d;bc=%08x;bo=%.3f;up=%d;sp=%.3f;al=%d;in=%s;out=%s;bl=%d;c2=%08x;ex=%.3f;ec=%08x;lp=%s;bs=%d%s%s",
        font.name, weight, color, outline, outlineColor, if (shadow) 1 else 0, if (glow) 1 else 0, boxColor, boxOpacity,
        if (uppercase) 1 else 0, spacing, align, animIn.name, animOut.name, if (belowOverlays) 1 else 0,
        color2, extrude, extrudeColor, loop.name, if (behindSubject) 1 else 0,
        from?.let { String.format(Locale.ROOT, ";fr=%.4f,%.4f,%.4f", it.x, it.y, it.size) } ?: "",
        if (belowClips.isEmpty()) "" else ";ub=" + belowClips.sorted().joinToString(","),
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
                belowOverlays = m["bl"] == "1",
                color2 = color("c2", 0),
                extrude = (m["ex"]?.toFloatOrNull() ?: 0f).coerceIn(0f, 0.3f),
                extrudeColor = color("ec", d.extrudeColor),
                loop = TextLoop.entries.firstOrNull { it.name == m["lp"] } ?: TextLoop.NONE,
                behindSubject = m["bs"] == "1",
                from = m["fr"]?.split(',')?.mapNotNull { it.toFloatOrNull() }?.takeIf { it.size == 3 }?.let { TextFrom(it[0].coerceIn(-0.5f, 1.5f), it[1].coerceIn(-0.5f, 1.5f), it[2].coerceIn(0.01f, 0.6f)) },
                belowClips = m["ub"]?.split(',')?.filter { it.isNotBlank() }?.toSet().orEmpty(),
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

    // Motion-graphics looks (original designs in the spirit of modern social video editors).
    BEHIND_ME("Behind me", TextStyle(font = CaptionFont.ANTON, uppercase = true, shadow = false, spacing = 0.02f, animIn = TextAnim.TRACK, behindSubject = true), 0.3f, 0.32f),
    HYPE("Hype", TextStyle(font = CaptionFont.ANTON, uppercase = true, color = 0xFFFFE600.toInt(), outline = 0.1f, animIn = TextAnim.STAMP, loop = TextLoop.SHAKE), 0.14f, 0.38f),
    KINETIC("Kinetic", TextStyle(font = CaptionFont.BEBAS, uppercase = true, spacing = 0.04f, animIn = TextAnim.WORDS, animOut = TextAnim.ZOOM), 0.12f, 0.45f),
    GRADIENT_POP("Gradient pop", TextStyle(font = CaptionFont.POPPINS, color = 0xFFFF5FA2.toInt(), color2 = 0xFFFFB347.toInt(), shadow = true, animIn = TextAnim.POP, loop = TextLoop.PULSE), 0.1f, 0.35f),
    RETRO_3D("Retro 3D", TextStyle(font = CaptionFont.BUNGEE, uppercase = true, color = 0xFFFFD43B.toInt(), extrude = 0.12f, extrudeColor = 0xFFD7263D.toInt(), shadow = false, animIn = TextAnim.BOUNCE), 0.11f, 0.35f),
    GLITCH("Glitch", TextStyle(font = CaptionFont.ARCHIVO, uppercase = true, color = 0xFFE8FFFF.toInt(), shadow = false, animIn = TextAnim.GLITCH, loop = TextLoop.GLITCH), 0.1f, 0.4f),
    NEON_SIGN("Neon sign", TextStyle(font = CaptionFont.NEON, color = 0xFFFF6EC7.toInt(), glow = true, shadow = false, animIn = TextAnim.FADE, loop = TextLoop.FLICKER), 0.1f, 0.35f),
    WAVE("Wave", TextStyle(font = CaptionFont.LUCKIEST, color = 0xFFFFFFFF.toInt(), outline = 0.08f, outlineColor = 0xFF2B59C3.toInt(), animIn = TextAnim.CASCADE, loop = TextLoop.WAVE), 0.1f, 0.4f),
    COMIC("Comic", TextStyle(font = CaptionFont.BANGERS, uppercase = true, color = 0xFFFFE600.toInt(), outline = 0.06f, extrude = 0.08f, extrudeColor = BLACKISH, spacing = 0.03f, animIn = TextAnim.SPIN), 0.12f, 0.35f),
    SIGNATURE("Signature", TextStyle(font = CaptionFont.SCRIPT, weight = 400, shadow = true, animIn = TextAnim.WIPE), 0.12f, 0.55f),
    EDITORIAL("Editorial", TextStyle(font = CaptionFont.ABRIL, color = 0xFFF5EFE6.toInt(), shadow = true, animIn = TextAnim.TRACK, loop = TextLoop.FLOAT), 0.09f, 0.4f),
    HINDI_BOLD("Hindi bold", TextStyle(font = CaptionFont.MUKTA, color = 0xFFFFE600.toInt(), outline = 0.08f, animIn = TextAnim.WORDS), 0.1f, 0.4f),
    ;
}

private const val BLACKISH = 0xFF111111.toInt()
private const val WHITEISH = 0xFFFAFAFA.toInt()

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
data class TextFrame(val layer: TextLayer, val enter: Float, val exit: Float, /** Time since the layer appeared, for looping emphasis. */ val localUs: Long = 0L)

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
                val inAnim = if (l.styleObj.animIn.staggered) minOf(1_200_000L, l.lengthUs / 2).coerceAtLeast(1) else anim
                val enter = ((timeUs - l.startUs).toFloat() / inAnim).coerceIn(0f, 1f)
                val exit = (1f - (l.endUs - timeUs).toFloat() / anim).coerceIn(0f, 1f)
                // Running time only while something moves, so still text isn't redrawn every frame.
                val moving = enter < 1f || l.styleObj.loop != TextLoop.NONE || l.styleObj.from != null || l.kind == TextKind.PROGRESS.name
                TextFrame(l, enter, exit, if (moving) timeUs - l.startUs else 0L)
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

/**
 * Stacking of what is drawn over the video, top to bottom: titles/stickers above the
 * picture-in-picture (by lane), captions, PiP, titles/stickers below it, the main video. Captions
 * can sit above or below the PiP and above or below the titles in their group. Kept per project
 * (preferences; no schema change); read by both preview and export.
 */
data class LayerOrder(val captionsAboveOverlays: Boolean = true, val captionsAboveTitles: Boolean = true) {
    fun encode(): String = "cap_pip=${if (captionsAboveOverlays) 1 else 0};cap_titles=${if (captionsAboveTitles) 1 else 0}"

    companion object {
        fun decode(text: String?): LayerOrder {
            if (text.isNullOrBlank()) return LayerOrder()
            val m = text.split(';').associate { it.substringBefore('=') to it.substringAfter('=', "") }
            return LayerOrder(m["cap_pip"] != "0", m["cap_titles"] != "0")
        }
    }
}

/** One row of the Layers list, top first. */
sealed interface LayerEntry {
    data class Title(val layer: TextLayer) : LayerEntry
    data object Captions : LayerEntry
    data object PictureInPicture : LayerEntry
    data object MainVideo : LayerEntry
}

object LayerStack {

    /** The visual stack, top first. */
    fun entries(texts: List<TextLayer>, order: LayerOrder, hasCaptions: Boolean, hasOverlays: Boolean): List<LayerEntry> {
        val above = texts.filter { !it.styleObj.belowOverlays }.sortedByDescending { it.lane }.map { LayerEntry.Title(it) }
        val below = texts.filter { it.styleObj.belowOverlays }.sortedByDescending { it.lane }.map { LayerEntry.Title(it) }
        val out = mutableListOf<LayerEntry>()
        fun group(titles: List<LayerEntry>, withCaptions: Boolean) {
            if (withCaptions && hasCaptions && order.captionsAboveTitles) out += LayerEntry.Captions
            out += titles
            if (withCaptions && hasCaptions && !order.captionsAboveTitles) out += LayerEntry.Captions
        }
        group(above, order.captionsAboveOverlays)
        if (hasOverlays) out += LayerEntry.PictureInPicture
        group(below, !order.captionsAboveOverlays)
        out += LayerEntry.MainVideo
        return out
    }

    /**
     * Moves [entry] one step up (or down) and returns the new texts and order: a title passes the
     * captions or the PiP (changing its above/below flag) or swaps lanes with its neighbour;
     * captions pass a title group edge or the PiP. The main video always stays at the bottom.
     */
    fun move(texts: List<TextLayer>, order: LayerOrder, hasCaptions: Boolean, hasOverlays: Boolean, entry: LayerEntry, up: Boolean): Pair<List<TextLayer>, LayerOrder> {
        val list = entries(texts, order, hasCaptions, hasOverlays)
        val i = list.indexOf(entry)
        val j = if (up) i - 1 else i + 1
        if (i < 0 || j < 0 || j >= list.size) return texts to order
        val other = list[j]
        if (other == LayerEntry.MainVideo || entry == LayerEntry.MainVideo || entry == LayerEntry.PictureInPicture) return texts to order
        return when (entry) {
            is LayerEntry.Title -> when (other) {
                is LayerEntry.Title -> {
                    // Swap lanes (and take the neighbour's above/below side).
                    val a = entry.layer
                    val b = other.layer
                    texts.map {
                        when (it.id) {
                            a.id -> it.copy(lane = b.lane, style = it.styleObj.copy(belowOverlays = b.styleObj.belowOverlays).encode())
                            b.id -> it.copy(lane = a.lane)
                            else -> it
                        }
                    } to order
                }
                LayerEntry.PictureInPicture -> texts.map { if (it.id == entry.layer.id) it.copy(style = it.styleObj.copy(belowOverlays = !up, belowClips = emptySet()).encode()) else it } to order
                LayerEntry.Captions -> {
                    // Crossing the captions inside one group flips captions-vs-titles for that group;
                    // simplest consistent rule: move the title to the other side of the captions.
                    texts to order.copy(captionsAboveTitles = !up)
                }
                else -> texts to order
            }
            LayerEntry.Captions -> when (other) {
                LayerEntry.PictureInPicture -> texts to order.copy(captionsAboveOverlays = up)
                is LayerEntry.Title -> texts to order.copy(captionsAboveTitles = up)
                else -> texts to order
            }
            else -> texts to order
        }
    }

    /** Stable identity of an entry (titles change their data when they move). */
    fun keyOf(entry: LayerEntry): String = when (entry) {
        is LayerEntry.Title -> "title:" + entry.layer.id
        LayerEntry.Captions -> "captions"
        LayerEntry.PictureInPicture -> "pip"
        LayerEntry.MainVideo -> "video"
    }

    /**
     * Moves the entry with [key] by [steps] places (negative = towards the front / up), as far as
     * the rules allow; one result, so the editor can make it a single undo step. The picture-in-
     * picture group moves by passing its neighbours over it. The main video never moves.
     */
    fun moveBy(texts: List<TextLayer>, order: LayerOrder, hasCaptions: Boolean, hasOverlays: Boolean, key: String, steps: Int): Pair<List<TextLayer>, LayerOrder> {
        var t = texts
        var o = order
        val up = steps < 0
        repeat(kotlin.math.abs(steps)) {
            val list = entries(t, o, hasCaptions, hasOverlays)
            val i = list.indexOfFirst { keyOf(it) == key }
            if (i < 0) return t to o
            val entry = list[i]
            val next = if (entry == LayerEntry.PictureInPicture) {
                val n = list.getOrNull(if (up) i - 1 else i + 1) ?: return t to o
                if (n == LayerEntry.MainVideo) return t to o
                move(t, o, hasCaptions, hasOverlays, n, up = !up)
            } else {
                move(t, o, hasCaptions, hasOverlays, entry, up)
            }
            if (next.first == t && next.second == o) return t to o
            t = next.first
            o = next.second
        }
        return t to o
    }
}

/** Start of a text's motion path (see [TextStyle.from]). */
data class TextFrom(val x: Float, val y: Float, val size: Float)

/** Where a layer is drawn at [localUs] into its life (its motion path applied, eased in and out). */
fun TextLayer.placedAt(localUs: Long): TextLayer {
    val f = styleObj.from ?: return this
    val t = (localUs.toFloat() / lengthUs.coerceAtLeast(1)).coerceIn(0f, 1f)
    val k = t * t * (3f - 2f * t)
    return copy(x = f.x + (x - f.x) * k, y = f.y + (y - f.y) * k, size = f.size + (size - f.size) * k)
}
