package com.cinemind.core.domain.audio

import java.net.URLEncoder

/** A kind of music that would suit the video, with why and what to search for. */
data class MusicIdea(
    val mood: String,
    val genres: List<String>,
    val bpmRange: IntRange,
    val reason: String,
    val searchTerms: List<String>,
)

/** A place to look for music the user is allowed to publish (opened in the browser / app). */
data class MusicSource(val name: String, val note: String, val searchUrl: (String) -> String)

/**
 * Music suggestions from what the video is about (project name, brief, style words) and, when a
 * song is already chosen, its tempo. Rule-based and offline; the on-device AI can refine the
 * wording but never invents song files. Nothing is downloaded or scraped: suggestions are search
 * terms for sources the user opens themselves.
 */
object MusicAdvisor {

    private data class Profile(val key: String, val words: List<String>, val idea: MusicIdea)

    private val profiles = listOf(
        Profile("wedding", listOf("wedding", "shaadi", "sangeet", "mehendi", "haldi", "bride", "groom", "engagement", "baraat"),
            MusicIdea("Romantic, celebratory", listOf("Bollywood romantic", "Acoustic love song", "Orchestral cinematic"), 70..100,
                "Weddings feel best with warm, emotional music that builds", listOf("romantic wedding instrumental", "shehnai cinematic", "acoustic love song"))),
        Profile("festival", listOf("diwali", "holi", "festival", "puja", "navratri", "garba", "eid", "christmas", "celebration", "party"),
            MusicIdea("Festive, energetic", listOf("Bollywood dance", "Dhol / Bhangra", "Upbeat pop"), 100..130,
                "Festivals and parties call for bright percussion and a strong beat", listOf("dhol beat instrumental", "festive upbeat", "bhangra drums"))),
        Profile("travel", listOf("travel", "trip", "mountain", "beach", "road", "himalaya", "goa", "ladakh", "manali", "vacation", "journey", "explore"),
            MusicIdea("Adventurous, uplifting", listOf("Indie folk", "Cinematic uplifting", "Chill electronic"), 90..120,
                "Travel montages need open, uplifting music with momentum", listOf("uplifting travel indie", "cinematic adventure", "chill travel vlog"))),
        Profile("fitness", listOf("gym", "workout", "fitness", "run", "training", "sports", "cricket", "football", "boxing"),
            MusicIdea("Powerful, driving", listOf("Hip-hop / trap", "EDM", "Rock"), 120..150,
                "Workouts and sport cut best to hard-hitting beats", listOf("workout trap beat", "gym motivation edm", "sports rock anthem"))),
        Profile("food", listOf("food", "recipe", "cooking", "cafe", "restaurant", "street food", "kitchen", "chai"),
            MusicIdea("Light, playful", listOf("Lo-fi", "Jazz hop", "Acoustic"), 80..105,
                "Food videos suit relaxed, cheerful grooves", listOf("lofi cooking", "jazz hop cafe", "happy acoustic"))),
        Profile("devotional", listOf("temple", "mandir", "prayer", "devotional", "bhajan", "spiritual", "gurudwara", "masjid", "aarti"),
            MusicIdea("Calm, devotional", listOf("Devotional instrumental", "Ambient", "Indian classical"), 60..85,
                "Spiritual moments need calm, respectful music", listOf("flute meditation", "devotional instrumental", "sitar ambient"))),
        Profile("vlog", listOf("vlog", "day in my life", "daily", "routine", "morning", "lifestyle", "room", "home"),
            MusicIdea("Easy-going", listOf("Lo-fi", "Indie pop", "Chill hip-hop"), 85..110,
                "Everyday vlogs work with relaxed music that stays in the background", listOf("lofi vlog", "chill indie pop", "morning routine music"))),
        Profile("cinematic", listOf("cinematic", "film", "short film", "story", "drama", "moody", "night", "rain", "emotional"),
            MusicIdea("Cinematic, emotional", listOf("Cinematic score", "Ambient piano", "Trailer"), 60..100,
                "Story-driven edits need music with tension and release", listOf("emotional piano cinematic", "dark ambient trailer", "epic cinematic build"))),
        Profile("fashion", listOf("fashion", "outfit", "style", "grwm", "makeup", "beauty", "lookbook", "model"),
            MusicIdea("Stylish, confident", listOf("House", "Pop", "R&B"), 110..128,
                "Fashion and beauty edits pop with polished, groovy beats", listOf("fashion house beat", "runway pop", "chic rnb"))),
        Profile("kids", listOf("baby", "kid", "kids", "birthday", "family", "school", "pet", "dog", "cat"),
            MusicIdea("Happy, warm", listOf("Ukulele pop", "Whistle pop", "Children's"), 95..120,
                "Family and pet moments feel right with cheerful, light music", listOf("happy ukulele", "cute whistle", "birthday upbeat"))),
    )

    private val default = MusicIdea("Upbeat, modern", listOf("Pop", "Lo-fi", "Electronic"), 95..120,
        "A safe, modern sound that suits most short videos", listOf("upbeat background music", "lofi chill", "modern pop instrumental"))

    /** Up to [max] ideas, best first. [bpm] (0 = unknown) of a chosen song adds a tempo note. */
    fun suggest(text: String, bpm: Float = 0f, max: Int = 3): List<MusicIdea> {
        val t = " " + text.lowercase() + " "
        val scored = profiles.map { p -> p to p.words.count { w -> t.contains(w) } }.filter { it.second > 0 }.sortedByDescending { it.second }
        val ideas = scored.map { it.first.idea }.ifEmpty { listOf(default) }.take(max).toMutableList()
        if (bpm > 0f) {
            ideas.replaceAll { i ->
                val fits = bpm.toInt() in i.bpmRange || (bpm / 2).toInt() in i.bpmRange || (bpm * 2).toInt() in i.bpmRange
                i.copy(reason = i.reason + if (fits) " — your current song's ${bpm.toInt()} BPM fits." else " — your current song is ${bpm.toInt()} BPM; ${i.bpmRange.first}–${i.bpmRange.last} BPM would suit better.")
            }
        }
        return ideas
    }

    private fun q(s: String) = URLEncoder.encode(s, "UTF-8")

    /** Places with music cleared for use in videos (each has its own licence terms, shown there). */
    val sources = listOf(
        MusicSource("YouTube Audio Library", "Free music for videos; some tracks need credit") { "https://www.youtube.com/audiolibrary" },
        MusicSource("Pixabay Music", "Free to use, no attribution required (Pixabay licence)") { "https://pixabay.com/music/search/${q(it)}/" },
        MusicSource("Free Music Archive", "Creative Commons music; check each track's licence") { "https://freemusicarchive.org/search?quicksearch=${q(it)}" },
        MusicSource("Instagram (when posting)", "Add licensed songs inside Instagram when you post the Reel") { "https://www.instagram.com/explore/search/keyword/?q=${q(it)}" },
    )
}

/**
 * A sound the user wants to use from Instagram. CineMind never downloads Instagram audio (that
 * would bypass Instagram's licensing); it keeps the link with the project so the user can add the
 * exact sound in Instagram when posting.
 */
data class InstagramSound(val url: String, val audioId: String?, val kind: Kind) {
    enum class Kind { AUDIO_PAGE, REEL, OTHER }

    companion object {
        private val audio = Regex("""instagram\.com/reels?/audio/(\d+)""", RegexOption.IGNORE_CASE)
        private val reel = Regex("""instagram\.com/(reel|reels|p)/([A-Za-z0-9_-]+)""", RegexOption.IGNORE_CASE)

        /** Parses a pasted link (with or without https://, tracking parameters removed); null if not Instagram. */
        fun parse(text: String): InstagramSound? {
            val raw = text.trim().substringBefore(' ')
            if (!raw.contains("instagram.com", ignoreCase = true) && !raw.contains("instagr.am", ignoreCase = true)) return null
            val clean = raw.substringBefore('?').substringBefore('#').let { if (it.startsWith("http")) it else "https://$it" }.trimEnd('/')
            audio.find(clean)?.let { return InstagramSound(clean + "/", it.groupValues[1], Kind.AUDIO_PAGE) }
            reel.find(clean)?.let { return InstagramSound(clean + "/", null, Kind.REEL) }
            return InstagramSound(clean, null, Kind.OTHER)
        }
    }
}
