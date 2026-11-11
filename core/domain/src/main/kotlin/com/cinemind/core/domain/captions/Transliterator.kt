package com.cinemind.core.domain.captions

/**
 * Devanagari → Latin letters (transliteration: the words stay Hindi, only the alphabet changes).
 * Used to show Hindi captions in Roman script when the recogniser returned Devanagari, and as a
 * deterministic fallback when the model can't be asked. Not a translator.
 *
 * Follows everyday Hinglish spelling rather than scholarly ISO-15919 (no diacritics): the inherent
 * "a" is dropped where Hindi speakers drop it (schwa deletion: "करना" → "karna", "जगह" → "jagah"),
 * and with [casual] the most common words use their usual chat spelling ("में" → "mein").
 * Text that isn't Devanagari (English words, digits, emoji) passes through unchanged.
 */
object Transliterator {

    fun containsDevanagari(text: String): Boolean = text.any { it in 'ऀ'..'ॿ' }

    fun devanagariToRoman(text: String, casual: Boolean = true): String {
        if (!containsDevanagari(text)) return text
        val out = StringBuilder()
        var i = 0
        while (i < text.length) {
            if (isDeva(text[i])) {
                var j = i
                while (j < text.length && isDeva(text[j])) j++
                val word = text.substring(i, j)
                out.append(if (casual) COMMON[word] ?: word(word, casual) else word(word, casual))
                i = j
            } else {
                out.append(PUNCT[text[i]] ?: text[i])
                i++
            }
        }
        return out.toString()
    }

    private fun isDeva(c: Char) = c in 'ऀ'..'ॿ' && c != '।' && c != '॥' && c !in '०'..'९'

    private class Syl(val cons: String?, var vowel: String, var inherent: Boolean, var suffix: String = "")

    private fun word(w: String, casual: Boolean): String {
        val units = mutableListOf<Syl>()
        var i = 0
        while (i < w.length) {
            val c = w[i]
            val cons = CONSONANTS[c]
            when {
                cons != null -> {
                    var roman: String = cons
                    if (i + 1 < w.length && w[i + 1] == NUKTA) {
                        roman = NUKTA_FORMS[c] ?: cons
                        i++
                    }
                    val u = Syl(roman, "a", inherent = true)
                    if (i + 1 < w.length) {
                        val n = w[i + 1]
                        val matra = MATRAS[n]
                        if (matra != null) { u.vowel = matra; u.inherent = false; i++ }
                        else if (n == VIRAMA) { u.vowel = ""; u.inherent = false; i++ }
                    }
                    units += u
                }
                PRECOMPOSED[c] != null -> {
                    val u = Syl(PRECOMPOSED[c], "a", inherent = true)
                    if (i + 1 < w.length) {
                        val n = w[i + 1]
                        val matra = MATRAS[n]
                        if (matra != null) { u.vowel = matra; u.inherent = false; i++ }
                        else if (n == VIRAMA) { u.vowel = ""; u.inherent = false; i++ }
                    }
                    units += u
                }
                VOWELS[c] != null -> units += Syl(null, VOWELS.getValue(c), inherent = false)
                c == ANUSVARA || c == CHANDRABINDU -> units.lastOrNull()?.let { it.suffix += "n" }
                c == VISARGA -> units.lastOrNull()?.let { it.suffix += "h" }
                else -> {} // ZWJ, ZWNJ and other marks: ignored
            }
            i++
        }
        // Schwa deletion, right to left.
        if (units.size > 1) {
            val last = units.last()
            if (last.inherent && last.suffix.isEmpty()) { last.vowel = ""; last.inherent = false }
            for (k in units.size - 2 downTo 1) {
                val u = units[k]
                if (!u.inherent || u.suffix.isNotEmpty()) continue
                val next = units[k + 1]
                val prev = units[k - 1]
                // Keep it when dropping would make a three-consonant cluster ("ज़िंदगी" → "zindagi").
                if (next.cons != null && next.vowel.isNotEmpty() && prev.vowel.isNotEmpty() && prev.suffix.isEmpty()) {
                    u.vowel = ""
                    u.inherent = false
                }
            }
        }
        // Anusvara before a labial sounds like "m".
        for (k in 0 until units.size - 1) {
            if (units[k].suffix == "n" && units[k + 1].cons?.firstOrNull() in setOf('p', 'b', 'm')) units[k].suffix = "m"
        }
        if (casual && units.size > 1) {
            val last = units.last()
            if (last.suffix.isEmpty()) {
                last.vowel = when (last.vowel) { "aa" -> "a"; "ee" -> "i"; else -> last.vowel }
            }
        }
        return units.joinToString("") { (it.cons ?: "") + it.vowel + it.suffix }
    }

    private const val NUKTA = '़'
    private const val VIRAMA = '्'
    private const val ANUSVARA = 'ं'
    private const val CHANDRABINDU = 'ँ'
    private const val VISARGA = 'ः'

    private val VOWELS = mapOf(
        'अ' to "a", 'आ' to "aa", 'इ' to "i", 'ई' to "ee", 'उ' to "u", 'ऊ' to "oo", 'ऋ' to "ri",
        'ए' to "e", 'ऐ' to "ai", 'ओ' to "o", 'औ' to "au", 'ऑ' to "o", 'ऍ' to "e",
    )
    private val MATRAS = mapOf(
        'ा' to "aa", 'ि' to "i", 'ी' to "ee", 'ु' to "u", 'ू' to "oo", 'ृ' to "ri",
        'े' to "e", 'ै' to "ai", 'ो' to "o", 'ौ' to "au", 'ॉ' to "o", 'ॅ' to "e",
    )
    private val CONSONANTS = mapOf(
        'क' to "k", 'ख' to "kh", 'ग' to "g", 'घ' to "gh", 'ङ' to "n",
        'च' to "ch", 'छ' to "chh", 'ज' to "j", 'झ' to "jh", 'ञ' to "n",
        'ट' to "t", 'ठ' to "th", 'ड' to "d", 'ढ' to "dh", 'ण' to "n",
        'त' to "t", 'थ' to "th", 'द' to "d", 'ध' to "dh", 'न' to "n",
        'प' to "p", 'फ' to "ph", 'ब' to "b", 'भ' to "bh", 'म' to "m",
        'य' to "y", 'र' to "r", 'ल' to "l", 'व' to "v", 'श' to "sh", 'ष' to "sh", 'स' to "s", 'ह' to "h",
        'ळ' to "l",
    )
    private val NUKTA_FORMS = mapOf('क' to "q", 'ख' to "kh", 'ग' to "gh", 'ज' to "z", 'ड' to "d", 'ढ' to "dh", 'फ' to "f", 'य' to "y")
    // Precomposed nukta letters U+0958..U+095F (written as escapes: editors may decompose them).
    private val PRECOMPOSED = mapOf(
        'क़' to "q", 'ख़' to "kh", 'ग़' to "gh", 'ज़' to "z",
        'ड़' to "d", 'ढ़' to "dh", 'फ़' to "f", 'य़' to "y",
    )
    private val PUNCT = mapOf(
        '।' to '.', '॥' to '.',
        '०' to '0', '१' to '1', '२' to '2', '३' to '3', '४' to '4', '५' to '5', '६' to '6', '७' to '7', '८' to '8', '९' to '9',
    )

    /** Everyday chat spellings of very common words (casual mode). */
    private val COMMON = mapOf(
        "है" to "hai", "हैं" to "hain", "में" to "mein", "मैं" to "main", "यह" to "yeh", "ये" to "ye",
        "वह" to "woh", "वो" to "wo", "नहीं" to "nahi", "क्या" to "kya", "और" to "aur", "भाई" to "bhai",
        "बहुत" to "bahut", "हूँ" to "hoon", "हूं" to "hoon", "हो" to "ho", "था" to "tha", "थी" to "thi",
        "थे" to "the", "तो" to "to", "भी" to "bhi", "कि" to "ki", "की" to "ki", "के" to "ke", "का" to "ka",
        "को" to "ko", "से" to "se", "पर" to "par", "एक" to "ek", "अब" to "ab", "आप" to "aap", "हम" to "hum",
        "तुम" to "tum", "मुझे" to "mujhe", "यहाँ" to "yahan", "यहां" to "yahan", "वहाँ" to "wahan",
        "वहां" to "wahan", "कैसे" to "kaise", "क्यों" to "kyun", "अच्छा" to "accha", "ठीक" to "theek",
        "जगह" to "jagah", "सच" to "sach", "बात" to "baat", "चलो" to "chalo", "देखो" to "dekho",
        "सब" to "sab", "कुछ" to "kuch", "लिए" to "liye", "रहा" to "raha", "रही" to "rahi", "रहे" to "rahe",
        "गया" to "gaya", "गई" to "gayi", "दिया" to "diya", "हुआ" to "hua", "यार" to "yaar", "मत" to "mat",
        "ना" to "na", "हाँ" to "haan", "हां" to "haan", "जी" to "ji", "अपना" to "apna", "अपनी" to "apni",
    )
}
