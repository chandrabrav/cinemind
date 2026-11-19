package com.cinemind.feature.editor.timeline.audio

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.cinemind.core.common.SoundCredits
import com.cinemind.core.domain.assistant.MiniJson
import com.cinemind.core.domain.audio.Freesound
import com.cinemind.core.domain.audio.FreesoundPage
import com.cinemind.core.domain.audio.FreesoundSound
import com.cinemind.core.domain.model.MediaAsset
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/** A sound in the bundled CC0 pack (assets/sfx/pack.json). */
data class PackSound(val file: String, val label: String, val category: String, val author: String, val source: String)

class FreesoundException(message: String) : Exception(message)

/**
 * Sound effects beyond the ones CineMind generates:
 * - a bundled CC0 pack (works offline, no credit needed);
 * - Freesound.org search with the user's own free API key (CC0 and CC BY only). The key is
 *   encrypted with an Android Keystore key, sent only to freesound.org, never logged.
 * Added sounds are copied into the app (files/sfx) so projects keep working offline, and each
 * Freesound sound's credit is remembered for About → Sound credits.
 */
@Singleton
class SoundLibrary @Inject constructor(@ApplicationContext private val context: Context) {

    private val prefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    private val _hasKey = MutableStateFlow(false)
    val hasKey: StateFlow<Boolean> = _hasKey.asStateFlow()

    init {
        _hasKey.value = runCatching { key() != null }.getOrDefault(false)
    }

    // ---- bundled CC0 pack

    /** The bundled pack, or empty when this build has none. */
    val pack: List<PackSound> by lazy {
        runCatching {
            val text = context.assets.open("sfx/pack.json").bufferedReader().use { it.readText() }
            @Suppress("UNCHECKED_CAST")
            (MiniJson.parse(text) as List<Any?>).mapNotNull { e ->
                val m = e as? Map<String, Any?> ?: return@mapNotNull null
                PackSound(
                    file = m["file"] as? String ?: return@mapNotNull null,
                    label = m["label"] as? String ?: return@mapNotNull null,
                    category = m["category"] as? String ?: "Sounds",
                    author = m["author"] as? String ?: "Kenney",
                    source = m["source"] as? String ?: "kenney.nl",
                )
            }
        }.getOrDefault(emptyList())
    }

    /** Copies a pack sound into the app and describes it as a media asset. */
    suspend fun packAsset(s: PackSound): MediaAsset = withContext(Dispatchers.IO) {
        val out = File(dir(), "pack_" + s.file.substringAfterLast('/'))
        if (!out.exists() || out.length() == 0L) context.assets.open("sfx/" + s.file).use { input -> out.outputStream().use { input.copyTo(it) } }
        asset(out, s.label, mime(out))
    }

    /** Opens a pack sound for listening (the caller closes it). */
    fun openPack(s: PackSound) = context.assets.openFd("sfx/" + s.file)

    // ---- Freesound

    suspend fun search(query: String, page: Int = 1): FreesoundPage = withContext(Dispatchers.IO) {
        val key = key() ?: throw FreesoundException("Add your Freesound API key first")
        val conn = (URL(Freesound.searchUrl(query, page)).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("Authorization", "Token $key")
            setRequestProperty("Accept", "application/json")
        }
        try {
            val code = conn.responseCode
            if (code == 401 || code == 403) throw FreesoundException("Freesound didn't accept the API key. Check it and save it again.")
            if (code == 429) throw FreesoundException("Freesound's limit for this key is reached for now. Try again later.")
            if (code !in 200..299) throw FreesoundException("Freesound search failed ($code)")
            Freesound.parse(conn.inputStream.bufferedReader().use { it.readText() })
        } finally {
            conn.disconnect()
        }
    }

    /** Downloads [s]'s preview into the app, remembers its credit and describes it as a media asset. */
    suspend fun download(s: FreesoundSound): MediaAsset = withContext(Dispatchers.IO) {
        require(s.previewUrl.startsWith("https://")) { "Not a secure link" }
        val out = File(dir(), "freesound_${s.id}.mp3")
        if (!out.exists() || out.length() == 0L) {
            val tmp = File(out.path + ".part")
            val conn = (URL(s.previewUrl).openConnection() as HttpURLConnection).apply { connectTimeout = 15_000; readTimeout = 30_000 }
            try {
                if (conn.responseCode !in 200..299) throw FreesoundException("Couldn't download the sound (${conn.responseCode})")
                conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            } finally {
                conn.disconnect()
            }
            if (tmp.length() > MAX_BYTES) { tmp.delete(); throw FreesoundException("That sound is too large") }
            tmp.renameTo(out)
        }
        SoundCredits.add(context, s.credit)
        asset(out, s.name, "audio/mpeg")
    }

    // ---- key (encrypted)

    fun saveKey(raw: String) {
        val clean = raw.trim()
        require(clean.length in 16..80 && clean.all { it.isLetterOrDigit() }) { "That doesn't look like a Freesound API key" }
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, secret())
        val blob = cipher.iv + cipher.doFinal(clean.toByteArray(Charsets.UTF_8))
        prefs.edit().putString(K_KEY, Base64.encodeToString(blob, Base64.NO_WRAP)).apply()
        _hasKey.value = true
    }

    fun removeKey() {
        prefs.edit().remove(K_KEY).apply()
        _hasKey.value = false
    }

    private fun key(): String? {
        val blob = prefs.getString(K_KEY, null) ?: return null
        val raw = Base64.decode(blob, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, secret(), GCMParameterSpec(128, raw.copyOfRange(0, 12)))
        return String(cipher.doFinal(raw, 12, raw.size - 12), Charsets.UTF_8)
    }

    private fun secret(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    // ---- helpers

    private fun dir() = File(context.filesDir, "sfx").apply { mkdirs() }

    private fun mime(f: File) = when (f.extension.lowercase()) {
        "ogg" -> "audio/ogg"
        "mp3" -> "audio/mpeg"
        "wav" -> "audio/wav"
        else -> "audio/*"
    }

    private fun asset(file: File, name: String, mime: String): MediaAsset {
        val durationUs = runCatching {
            MediaMetadataRetriever().run {
                try {
                    setDataSource(file.absolutePath)
                    (extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) * 1_000
                } finally {
                    release()
                }
            }
        }.getOrDefault(0L)
        if (durationUs <= 0) throw FreesoundException("That sound couldn't be read")
        return MediaAsset(
            uri = Uri.fromFile(file).toString(),
            mimeType = mime,
            width = 0,
            height = 0,
            durationUs = durationUs,
            hasAudio = true,
            displayName = name,
            sizeBytes = file.length(),
        )
    }

    private companion object {
        const val PREFS = "freesound_secure"
        const val ALIAS = "cinemind_freesound_key"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val K_KEY = "k"
        const val MAX_BYTES = 8L * 1024 * 1024
    }
}
