package com.cinemind.core.domain.aiedit

import com.cinemind.core.domain.captions.Caption
import com.cinemind.core.domain.captions.CaptionFont
import com.cinemind.core.domain.text.TextAnim
import com.cinemind.core.domain.text.TextLayer
import com.cinemind.core.domain.text.TextLoop
import com.cinemind.core.domain.text.TextStyle
import java.util.Locale

/**
 * Text designed from what is said, for talking videos: a big hook title from the strongest
 * phrase near the start (behind the speaker when there's a cutout), and keyword callouts that
 * pop up beside the speaker at the moment each key word is spoken, in changing places. Pure and
 * deterministic; the captions carry the words and their times (timeline times).
 */
object SpeechGraphics {

    /** At most one callout every this long, and none while the hook is on screen. */
    const val MIN_GAP_US = 2_500_000L
    const val CALLOUT_US = 1_300_000L

    private val STOP = (
        // English
        "a an the and or but if then so as of to in on at by for with from into onto up down out over under about " +
            "is are was were be been being am do does did done have has had having i me my mine we us our you your " +
            "he him his she her it its they them their this that these those there here what which who whom whose " +
            "when where why how all any both each few more most other some such no not only own same than too very " +
            "can could will would shall should may might must just also now well yeah okay ok like really get got " +
            "go going gonna want wanna let lets say said see know think thing things make made way one two " +
            // Hinglish (Roman Hindi)
            "hai hain ho hota hoti hote tha thi the ka ki ke ko se mein main me mai aur ya ye yeh wo woh vo voh to toh " +
            "na nahi nahin bhi kya kyu kyun hum ham tum aap mera meri mere apna apni apne kar karo karna karne kiya " +
            "raha rahe rahi jo ek liye lie wala wali wale par pe bas abhi ab jab tab kuch sab bahut bhai haan han " +
            "chalo dekho matlab"
        ).split(' ').filter { it.isNotBlank() }.toSet()

    private fun words(text: String): List<String> =
        text.split(Regex("""\s+""")).map { it.trim { c -> !c.isLetterOrDigit() && c != '\'' && c != '%' } }.filter { it.isNotEmpty() }

    private fun isContent(w: String): Boolean {
        val l = w.lowercase(Locale.ROOT)
        return l !in STOP && (l.any { it.isDigit() } || l.length >= 3)
    }

    /** How strongly a word deserves a callout (0 = never). */
    fun score(word: String, promptWords: Set<String>): Int {
        val l = word.lowercase(Locale.ROOT)
        if (!isContent(word)) return 0
        var s = 1
        if (l.any { it.isDigit() }) s += 4
        if (l in promptWords) s += 3
        if (l.length >= 6) s += 2 else if (l.length >= 4) s += 1
        if (word.length > 1 && word[0].isUpperCase() && word.drop(1).all { it.isLowerCase() }) s += 1
        if (word.length > 1 && word.any { it.isLetter() } && word.all { !it.isLetter() || it.isUpperCase() }) s += 2
        return s
    }

    /**
     * The hook: the best 2–4 word stretch said in the first [windowUs] (content words at both ends),
     * upper-cased. Null if nothing worth showing was said.
     */
    fun hook(captions: List<Caption>, promptWords: Set<String>, windowUs: Long = 8_000_000L): String? {
        val early = captions.sortedBy { it.startUs }.filter { it.startUs < windowUs }.ifEmpty { captions.sortedBy { it.startUs }.take(2) }
        var best: List<String>? = null
        var bestScore = 0
        for (c in early) {
            val ws = words(c.text)
            for (i in ws.indices) for (len in 2..4) {
                if (i + len > ws.size) break
                val span = ws.subList(i, i + len)
                if (!isContent(span.first()) || !isContent(span.last())) continue
                val s = span.sumOf { score(it, promptWords) } - (len - 2)
                if (s > bestScore) { bestScore = s; best = span }
            }
            // A single strong word is a hook too.
            ws.filter { score(it, promptWords) >= 4 }.maxByOrNull { score(it, promptWords) }?.let { w ->
                if (score(w, promptWords) > bestScore) { bestScore = score(w, promptWords); best = listOf(w) }
            }
        }
        if (bestScore < 4) return null
        return best?.joinToString(" ")?.uppercase(Locale.ROOT)?.take(28)
    }

    /** Places for callouts: beside the speaker's head, left and right, a little higher or lower. */
    private val SPOTS = listOf(0.27f to 0.36f, 0.73f to 0.42f, 0.30f to 0.48f, 0.72f to 0.34f)
    private val COLORS = listOf(0xFFFFE600.toInt(), 0xFFFFFFFF.toInt(), 0xFF7DF9FF.toInt(), 0xFFFF5C93.toInt())
    private val ANIMS = listOf(TextAnim.STAMP, TextAnim.POP, TextAnim.ZOOM, TextAnim.BOUNCE)

    /**
     * The hook title and keyword callouts for a talking video of [totalUs]. [behindSpeaker] puts the
     * hook behind the person (needs the clip's cutout).
     */
    fun plan(projectId: String, captions: List<Caption>, totalUs: Long, prompt: String, behindSpeaker: Boolean, idFor: (String) -> String = { it }): List<TextLayer> {
        if (captions.isEmpty() || totalUs <= 0) return emptyList()
        val promptWords = words(prompt).map { it.lowercase(Locale.ROOT) }.filter { isContent(it) }.toSet()
        val out = mutableListOf<TextLayer>()

        val hook = hook(captions, promptWords)
        val hookEnd = minOf(3_500_000L, totalUs / 3).coerceAtLeast(1_200_000L).coerceAtMost(totalUs)
        if (hook != null) {
            out += TextLayer(
                id = idFor("hook"), projectId = projectId, startUs = 0, endUs = hookEnd, text = hook,
                x = 0.5f, y = 0.24f, size = if (hook.length <= 10) 0.17f else 0.12f,
                style = TextStyle(
                    font = CaptionFont.ANTON, weight = 900, uppercase = true, color = 0xFFFFFFFF.toInt(), color2 = 0xFFFFD43B.toInt(),
                    shadow = !behindSpeaker, outline = if (behindSpeaker) 0f else 0.05f, animIn = TextAnim.CASCADE, animOut = TextAnim.FADE,
                    behindSubject = behindSpeaker,
                ).encode(),
                lane = 0,
            )
        }

        // Keyword callouts: the strongest word of each caption, spaced out, never during the hook.
        val maxCallouts = (totalUs / 4_000_000L).toInt().coerceIn(1, 8)
        // The first callout may follow the hook after half a second.
        var lastUs = if (hook != null) hookEnd - MIN_GAP_US + 500_000L else -MIN_GAP_US
        val hookWords = hook?.lowercase(Locale.ROOT)?.split(' ')?.toSet().orEmpty()
        val picks = mutableListOf<Pair<Long, String>>()
        for (c in captions.sortedBy { it.startUs }) {
            val ws = words(c.text)
            if (ws.isEmpty()) continue
            val bestIdx = ws.indices.maxByOrNull { score(ws[it], promptWords) } ?: continue
            val w = ws[bestIdx]
            if (score(w, promptWords) < 3 || w.lowercase(Locale.ROOT) in hookWords) continue
            // When the word is said: spread evenly through the caption.
            val at = c.startUs + (c.endUs - c.startUs) * bestIdx / ws.size
            if (at - lastUs < MIN_GAP_US || at + 600_000 > totalUs) continue
            picks += at to w
            lastUs = at
            if (picks.size >= maxCallouts) break
        }
        picks.forEachIndexed { i, (at, w) ->
            val (x, y) = SPOTS[i % SPOTS.size]
            out += TextLayer(
                id = idFor("callout-$i"), projectId = projectId, startUs = at, endUs = minOf(totalUs, at + CALLOUT_US),
                text = w.uppercase(Locale.ROOT), x = x, y = y, size = if (w.length <= 6) 0.09f else 0.07f, rotation = if (i % 2 == 0) -4f else 3f,
                style = TextStyle(
                    font = CaptionFont.ANTON, weight = 900, uppercase = true, color = COLORS[i % COLORS.size], outline = 0.1f,
                    animIn = ANIMS[i % ANIMS.size], animOut = TextAnim.FADE, loop = if (i % 3 == 2) TextLoop.PULSE else TextLoop.NONE,
                ).encode(),
                lane = 1 + i,
            )
        }
        return out
    }

    /** True for an automatic project name ("Project 19 Nov, 18:03", "VID_2026…"), not a real title. */
    fun isGenericName(name: String): Boolean {
        val n = name.trim()
        // "Project", "Project 3", "Project 19 Nov, 18:03" (a word, then only numbers, a month and separators).
        return n.isBlank() || Regex("""(?i)^(project|untitled|vid|img|video|new project)[\s_\-\d,:./]*([a-z]{3}[\s_\-\d,:./]*)?$""").matches(n) ||
            Regex("""^[\w\-]*\d{6,}[\w\-]*$""").matches(n)
    }
}
