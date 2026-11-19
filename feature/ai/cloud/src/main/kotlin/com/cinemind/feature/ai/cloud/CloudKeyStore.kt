package com.cinemind.feature.ai.cloud

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/** Which Claude model answers. Opus is the default; the others are faster and cheaper. */
enum class CloudModel(val id: String, val label: String, val note: String) {
    OPUS("claude-opus-5-5", "Claude Opus 5.5", "Best ideas (default)"),
    SONNET("claude-sonnet-5-5", "Claude Sonnet 5.5", "Faster, lower cost"),
    HAIKU("claude-haiku-5-5", "Claude Haiku 5.5", "Fastest, lowest cost"),
}

data class CloudSettings(
    val hasKey: Boolean = false,
    val keyHint: String = "",
    val model: CloudModel = CloudModel.OPUS,
    val consented: Boolean = false,
    val mode: com.cinemind.core.domain.aigen.AiMode = com.cinemind.core.domain.aigen.AiMode.HYBRID,
)

/**
 * The user's own Claude API key, encrypted with an AES key that lives in the Android Keystore
 * (it never leaves secure storage). The key is never logged, never put in diagnostics or feedback.
 */
@Singleton
class CloudKeyStore @Inject constructor(@ApplicationContext private val context: Context) {

    private val prefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    private val _settings = MutableStateFlow(CloudSettings())
    val settings: StateFlow<CloudSettings> = _settings.asStateFlow()

    init {
        refresh()
    }

    private fun refresh() {
        val key = runCatching { apiKey() }.getOrNull()
        _settings.value = CloudSettings(
            hasKey = key != null,
            keyHint = key?.let { "••••" + it.takeLast(4) }.orEmpty(),
            model = CloudModel.entries.firstOrNull { it.name == prefs.getString(K_MODEL, null) } ?: CloudModel.OPUS,
            consented = prefs.getBoolean(K_CONSENT, false),
            mode = com.cinemind.core.domain.aigen.AiMode.from(prefs.getString(K_MODE, null)),
        )
    }

    fun apiKey(): String? {
        val blob = prefs.getString(K_KEY, null) ?: return null
        val raw = Base64.decode(blob, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, secret(), GCMParameterSpec(128, raw.copyOfRange(0, 12)))
        return String(cipher.doFinal(raw, 12, raw.size - 12), Charsets.UTF_8)
    }

    fun saveKey(key: String) {
        val clean = key.trim()
        require(clean.isNotEmpty()) { "Paste your API key" }
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, secret())
        val blob = cipher.iv + cipher.doFinal(clean.toByteArray(Charsets.UTF_8))
        prefs.edit().putString(K_KEY, Base64.encodeToString(blob, Base64.NO_WRAP)).apply()
        refresh()
    }

    fun removeKey() {
        prefs.edit().remove(K_KEY).apply()
        refresh()
    }

    fun setModel(model: CloudModel) {
        prefs.edit().putString(K_MODEL, model.name).apply()
        refresh()
    }

    fun setConsent(on: Boolean) {
        prefs.edit().putBoolean(K_CONSENT, on).apply()
        refresh()
    }

    /** Offline, Hybrid or Cloud-assisted (see [com.cinemind.core.domain.aigen.AiMode]). */
    fun setMode(mode: com.cinemind.core.domain.aigen.AiMode) {
        prefs.edit().putString(K_MODE, mode.name).apply()
        refresh()
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

    private companion object {
        const val PREFS = "cloud_ai_secure"
        const val ALIAS = "cinemind_cloud_ai_key"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val K_KEY = "k"
        const val K_MODEL = "model"
        const val K_CONSENT = "consent"
        const val K_MODE = "ai_mode"
    }
}
