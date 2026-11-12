package com.cinemind.core.domain.color

import java.util.Locale

/** A look the advisor can choose from (built-in, imported or from a downloaded pack). */
data class LutCandidate(val id: String, val name: String, val category: String, val tags: Set<String>)

/**
 * What is known about the footage and the user's intent. [words] come from the user's brief /
 * project, captions and (when the on-device model is used) a description of the frames; the
 * numbers are measured from frames (0..1, warmth −1 cool .. +1 warm). Any of them may be missing.
 */
data class SceneProfile(
    val words: Set<String> = emptySet(),
    val brightness: Float? = null,
    val saturation: Float? = null,
    val warmth: Float? = null,
)

data class LutSuggestion(val id: String, val name: String, val score: Float, val reason: String)

/**
 * Recommends looks for a video from its scene and theme, and says why. Deterministic and offline;
 * the on-device model only contributes scene words. Pure; unit-tested.
 */
object LutAdvisor {

    /** Everyday words → the tag vocabulary used by looks. */
    private val SYNONYMS: Map<String, List<String>> = mapOf(
        "sea" to listOf("ocean", "beach"), "seaside" to listOf("beach", "ocean"), "goa" to listOf("beach", "tropical"),
        "sand" to listOf("beach", "desert"), "island" to listOf("tropical", "beach"), "waves" to listOf("ocean"),
        "sunrise" to listOf("golden hour", "warm"), "dusk" to listOf("sunset"), "evening" to listOf("sunset", "golden hour"),
        "hills" to listOf("mountain"), "himalaya" to listOf("mountain", "snow"), "trek" to listOf("hiking", "nature"),
        "trekking" to listOf("hiking", "nature"), "jungle" to listOf("forest"), "trees" to listOf("forest", "nature"),
        "rain" to listOf("rain", "monsoon"), "cloudy" to listOf("moody"), "snowy" to listOf("snow"),
        "street" to listOf("city", "urban"), "streets" to listOf("city", "urban"), "building" to listOf("architecture"),
        "lights" to listOf("night", "neon"), "nightlife" to listOf("night", "party"), "club" to listOf("party", "neon"),
        "marriage" to listOf("wedding"), "shaadi" to listOf("wedding"), "bride" to listOf("wedding"), "couple" to listOf("romantic"),
        "love" to listOf("romantic"), "anniversary" to listOf("romantic"),
        "gym" to listOf("fitness"), "workout" to listOf("fitness"), "exercise" to listOf("fitness"), "football" to listOf("sports"),
        "cricket" to listOf("sports"), "running" to listOf("sports", "energetic"),
        "recipe" to listOf("food"), "cooking" to listOf("food"), "restaurant" to listOf("food"), "dish" to listOf("food"), "meal" to listOf("food"),
        "coffee" to listOf("cafe", "cozy"), "home" to listOf("indoor", "cozy"),
        "festival" to listOf("festival"), "diwali" to listOf("festival", "warm"), "holi" to listOf("festival", "vivid"),
        "birthday" to listOf("party"), "celebration" to listOf("party", "festival"),
        "interview" to listOf("interview"), "podcast" to listOf("podcast"), "talking" to listOf("vlog"),
        "tutorial" to listOf("tutorial", "education"), "lesson" to listOf("education"), "product" to listOf("product"),
        "unboxing" to listOf("product", "clean"), "fashion" to listOf("fashion"),
        "film" to listOf("cinematic"), "movie" to listOf("cinematic"), "epic" to listOf("cinematic", "dramatic"),
        "dreamy" to listOf("soft", "pastel"), "aesthetic" to listOf("soft", "pastel"), "old" to listOf("vintage"),
        "nostalgia" to listOf("nostalgic", "vintage"), "nostalgic" to listOf("vintage"), "retro" to listOf("vintage"),
        "bw" to listOf("black and white"), "monochrome" to listOf("black and white"), "mono" to listOf("black and white"),
        "happy" to listOf("bright", "energetic"), "sad" to listOf("moody"), "calm" to listOf("calm"), "peaceful" to listOf("calm"),
        "energetic" to listOf("energetic"), "hype" to listOf("energetic"), "reel" to listOf("reels"), "reels" to listOf("reels"),
    )

    /** Words of a free text, normalised and expanded with synonyms (and two-word tags like "golden hour"). */
    fun keywords(text: String): Set<String> {
        val lower = text.lowercase(Locale.ROOT)
        val words = lower.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length > 1 }
        val out = mutableSetOf<String>()
        for (w in words) {
            val forms = if (w.endsWith("s") && w.length > 3) listOf(w, w.dropLast(1)) else listOf(w)
            for (f in forms) {
                out += f
                SYNONYMS[f]?.let { out += it }
            }
        }
        for (phrase in listOf("golden hour", "black and white", "road trip")) if (lower.contains(phrase)) out += phrase
        return out
    }

    fun suggest(profile: SceneProfile, candidates: List<LutCandidate>, limit: Int = 6): List<LutSuggestion> {
        val scored = candidates.map { c ->
            val matches = c.tags.filter { it in profile.words }
            var score = matches.size * 2f
            val because = mutableListOf<String>()
            if (matches.isNotEmpty()) because += "matches " + matches.take(3).joinToString(", ")
            profile.brightness?.let { b ->
                if (b < 0.35f && ("night" in c.tags || "moody" in c.tags)) { score += 1f; because += "suits dark footage" }
                if (b > 0.62f && ("bright" in c.tags || "clean" in c.tags || "soft" in c.tags)) { score += 0.8f; because += "keeps bright footage airy" }
                if (b < 0.3f && c.category == "Black & white" && "bright" in c.tags) score -= 1f
            }
            profile.saturation?.let { s ->
                if (s < 0.18f && ("vivid" in c.tags || "tropical" in c.tags)) { score += 1f; because += "adds colour to flat footage" }
                if (s > 0.45f && ("moody" in c.tags || "soft" in c.tags)) { score += 0.6f; because += "tames very saturated colour" }
            }
            profile.warmth?.let { w ->
                if (w > 0.12f && "warm" in c.tags) { score += 0.8f; because += "builds on warm light" }
                if (w < -0.12f && "cool" in c.tags) { score += 0.8f; because += "builds on cool light" }
            }
            LutSuggestion(c.id, c.name, score, because.joinToString("; ").replaceFirstChar { it.uppercase() })
        }
        val good = scored.filter { it.score > 0f }.sortedWith(compareByDescending<LutSuggestion> { it.score }.thenBy { it.name })
        if (good.isNotEmpty()) return good.take(limit)
        // Nothing specific known: safe, popular starting points.
        val defaults = listOf("builtin:teal_orange", "builtin:clean_bright", "builtin:golden_hour", "builtin:faded_film")
        return defaults.mapNotNull { id -> candidates.firstOrNull { it.id == id } }
            .map { LutSuggestion(it.id, it.name, 0f, "A popular starting point") }
            .take(limit)
    }
}
