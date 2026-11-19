package com.cinemind.feature.ai.cloud

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.cinemind.core.domain.aigen.AiMode
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

/** One provider's saved state (never the key itself). */
data class ProviderState(val hasKey: Boolean = false, val keyHint: String = "", val modelId: String = "")

data class CloudSettings(
    /** The provider in use has a key (and, except Claude, a chosen model). */
    val hasKey: Boolean = false,
    val keyHint: String = "",
    /** Claude model (used when [provider] is Claude). */
    val model: CloudModel = CloudModel.OPUS,
    val consented: Boolean = false,
    val mode: AiMode = AiMode.HYBRID,
    val provider: CloudProvider = CloudProvider.ANTHROPIC,
    val providers: Map<CloudProvider, ProviderState> = emptyMap(),
) {
    /** What the app shows as "answered by". */
    val engineLabel: String
        get() = if (provider == CloudProvider.ANTHROPIC) model.label
        else "${provider.label} · ${providers[provider]?.modelId.orEmpty()}"
}

/**
 * The user's own cloud AI keys (Claude, ChatGPT, Gemini, OpenRouter), each encrypted with an AES
 * key that lives in the Android Keystore (it never leaves secure storage). Keys are never logged,
 * never put in diagnostics, backups or feedback, and only sent to their own company.
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
        val provider = CloudProvider.from(prefs.getString(K_PROVIDER, null))
        val states = CloudProvider.entries.associateWith { p ->
            val key = runCatching { apiKey(p) }.getOrNull()
            ProviderState(
                hasKey = key != null,
                keyHint = key?.let { "••••" + it.takeLast(4) }.orEmpty(),
                modelId = if (p == CloudProvider.ANTHROPIC) "" else prefs.getString(modelKey(p), null).orEmpty(),
            )
        }
        val active = states.getValue(provider)
        _settings.value = CloudSettings(
            hasKey = active.hasKey && (provider == CloudProvider.ANTHROPIC || active.modelId.isNotBlank()),
            keyHint = active.keyHint,
            model = CloudModel.entries.firstOrNull { it.name == prefs.getString(K_MODEL, null) } ?: CloudModel.OPUS,
            consented = prefs.getBoolean(K_CONSENT, false),
            mode = AiMode.from(prefs.getString(K_MODE, null)),
            provider = provider,
            providers = states,
        )
    }

    /** The key for [provider] (default: the one in use), or null. */
    fun apiKey(provider: CloudProvider = settings.value.provider): String? {
        val blob = prefs.getString(keyName(provider), null) ?: return null
        val raw = Base64.decode(blob, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, secret(), GCMParameterSpec(128, raw.copyOfRange(0, 12)))
        return String(cipher.doFinal(raw, 12, raw.size - 12), Charsets.UTF_8)
    }

    fun saveKey(key: String, provider: CloudProvider = settings.value.provider) {
        val clean = key.trim()
        require(clean.isNotEmpty()) { "Paste your API key" }
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, secret())
        val blob = cipher.iv + cipher.doFinal(clean.toByteArray(Charsets.UTF_8))
        prefs.edit().putString(keyName(provider), Base64.encodeToString(blob, Base64.NO_WRAP)).apply()
        refresh()
    }

    fun removeKey(provider: CloudProvider = settings.value.provider) {
        prefs.edit().remove(keyName(provider)).apply()
        refresh()
    }

    fun setProvider(provider: CloudProvider) {
        prefs.edit().putString(K_PROVIDER, provider.name).apply()
        refresh()
    }

    fun setModel(model: CloudModel) {
        prefs.edit().putString(K_MODEL, model.name).apply()
        refresh()
    }

    /** Model id for ChatGPT / Gemini / OpenRouter. */
    fun setProviderModel(provider: CloudProvider, modelId: String) {
        prefs.edit().putString(modelKey(provider), modelId.trim()).apply()
        refresh()
    }

    fun setConsent(on: Boolean) {
        prefs.edit().putBoolean(K_CONSENT, on).apply()
        refresh()
    }

    /** Offline, Hybrid or Cloud-assisted (see [AiMode]). */
    fun setMode(mode: AiMode) {
        prefs.edit().putString(K_MODE, mode.name).apply()
        refresh()
    }

    /** A client for the provider in use, or null when it isn't set up. */
    fun client(): CloudLlm? {
        val s = settings.value
        if (!s.hasKey) return null
        val key = apiKey(s.provider) ?: return null
        return when (s.provider) {
            CloudProvider.ANTHROPIC -> ClaudeClient(key, s.model)
            else -> OpenAiCompatClient(s.provider, key, s.providers[s.provider]?.modelId.orEmpty())
        }
    }

    // Claude's key keeps its original name so keys saved by older versions still work.
    private fun keyName(p: CloudProvider) = if (p == CloudProvider.ANTHROPIC) K_KEY else "${K_KEY}_${p.name}"
    private fun modelKey(p: CloudProvider) = "model_${p.name}"

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
        const val K_PROVIDER = "provider"
    }
}
