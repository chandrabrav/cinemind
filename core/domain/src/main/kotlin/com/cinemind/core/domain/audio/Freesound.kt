package com.cinemind.core.domain.audio

import com.cinemind.core.domain.assistant.MiniJson
import java.net.URLEncoder
import java.util.Locale

/** Licences CineMind accepts for sounds it adds to a video. */
enum class SoundLicense(val label: String, val needsCredit: Boolean) {
    CC0("CC0 · no credit needed", false),
    CC_BY("CC BY · credit the author", true),
    ;

    companion object {
        /** From Freesound's licence URL; null for licences CineMind doesn't use (e.g. NonCommercial). */
        fun fromUrl(url: String): SoundLicense? {
            val u = url.lowercase(Locale.ROOT)
            return when {
                "publicdomain/zero" in u -> CC0
                "licenses/by/" in u -> CC_BY
                else -> null
            }
        }
    }
}

/** One sound found on Freesound. [previewUrl] is the high-quality MP3 preview (public, no login). */
data class FreesoundSound(
    val id: Long,
    val name: String,
    val username: String,
    val license: SoundLicense,
    val seconds: Double,
    val previewUrl: String,
    val tags: List<String> = emptyList(),
) {
    val pageUrl: String get() = "https://freesound.org/s/$id/"

    /** What to write in a video description for a CC BY sound (kept for CC0 too, as a courtesy). */
    val credit: String get() = "“${name.take(60)}” by $username ($pageUrl), " + if (license == SoundLicense.CC0) "CC0" else "CC BY"
}

data class FreesoundPage(val sounds: List<FreesoundSound>, val total: Int, val hasMore: Boolean)

/**
 * Freesound.org's official API (v2), used with the user's own free API key: search only sounds
 * that may be used in published videos (CC0 and CC BY, short), most downloaded first. Pure
 * (URL building and parsing), so it is unit-tested; the app does the network calls.
 */
object Freesound {

    const val SEARCH = "https://freesound.org/apiv2/search/"
    const val KEY_PAGE = "https://freesound.org/apiv2/apply/"
    const val PAGE_SIZE = 20

    /** Popular searches for short-video sound design. */
    val QUICK = listOf(
        "whoosh", "swoosh transition", "impact boom", "bass drop", "riser", "pop", "ding", "notification",
        "camera shutter", "typing", "record scratch", "crowd laugh", "applause", "cash register", "glitch", "cartoon boing",
    )

    fun searchUrl(query: String, page: Int = 1, maxSeconds: Int = 12): String {
        val filter = "license:(\"Creative Commons 0\" OR \"Attribution\") duration:[0 TO $maxSeconds]"
        fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        return SEARCH + "?query=" + enc(query.trim()) +
            "&filter=" + enc(filter) +
            "&sort=downloads_desc" +
            "&fields=" + enc("id,name,username,license,duration,previews,tags") +
            "&page_size=$PAGE_SIZE&page=${page.coerceAtLeast(1)}"
    }

    @Suppress("UNCHECKED_CAST")
    fun parse(json: String): FreesoundPage {
        val root = MiniJson.parse(json) as? Map<String, Any?> ?: return FreesoundPage(emptyList(), 0, false)
        val results = root["results"] as? List<Any?> ?: emptyList()
        val sounds = results.mapNotNull { r ->
            val m = r as? Map<String, Any?> ?: return@mapNotNull null
            val license = SoundLicense.fromUrl(m["license"] as? String ?: "") ?: return@mapNotNull null
            val previews = m["previews"] as? Map<String, Any?> ?: return@mapNotNull null
            val preview = (previews["preview-hq-mp3"] ?: previews["preview-lq-mp3"]) as? String ?: return@mapNotNull null
            if (!preview.startsWith("https://")) return@mapNotNull null
            FreesoundSound(
                id = (m["id"] as? Number)?.toLong() ?: return@mapNotNull null,
                name = (m["name"] as? String).orEmpty().substringBeforeLast('.').ifBlank { "Sound" },
                username = (m["username"] as? String).orEmpty(),
                license = license,
                seconds = (m["duration"] as? Number)?.toDouble() ?: 0.0,
                previewUrl = preview,
                tags = (m["tags"] as? List<Any?>).orEmpty().filterIsInstance<String>().take(8),
            )
        }
        return FreesoundPage(sounds, (root["count"] as? Number)?.toInt() ?: sounds.size, root["next"] is String)
    }
}
