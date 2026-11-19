package com.cinemind.feature.ai.cloud

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Cloud AI services the user can connect with their own key. Each is paid directly by the user
 * to that company; CineMind adds no charge and never sees the money. Claude uses Anthropic's
 * official SDK; the others share one OpenAI-compatible client (their documented endpoints).
 */
enum class CloudProvider(val label: String, val company: String, val keyUrl: String, val baseUrl: String?) {
    ANTHROPIC("Claude", "Anthropic", "https://console.anthropic.com/settings/keys", null),
    OPENAI("ChatGPT", "OpenAI", "https://platform.openai.com/api-keys", "https://api.openai.com/v1"),
    GEMINI("Gemini", "Google", "https://aistudio.google.com/apikey", "https://generativelanguage.googleapis.com/v1beta/openai"),
    OPENROUTER("OpenRouter", "OpenRouter (many models with one key)", "https://openrouter.ai/keys", "https://openrouter.ai/api/v1"),
    ;

    companion object {
        fun from(name: String?): CloudProvider = entries.firstOrNull { it.name == name } ?: ANTHROPIC
    }
}

/** One structured request to a cloud model: the reply is JSON matching [schema]. */
interface CloudLlm {
    /** [deep] = think longer (Cloud-assisted mode); providers that can't are asked normally. */
    suspend fun ask(system: String, user: String, schema: Map<String, Any>, maxTokens: Long = 4_000L, deep: Boolean = false): String
    fun close() {}
}

/**
 * ChatGPT, Gemini and OpenRouter through their OpenAI-compatible Chat Completions endpoints,
 * with the user's own key. Asks for a strict JSON-schema reply; if a model doesn't support that,
 * asks again for plain JSON with the schema in the instructions (the app validates either way).
 */
class OpenAiCompatClient(
    private val provider: CloudProvider,
    private val apiKey: String,
    private val model: String,
) : CloudLlm {

    init {
        require(provider.baseUrl != null) { "Not an OpenAI-compatible provider" }
        require(model.isNotBlank()) { "Choose a ${provider.label} model in AI models → Cloud" }
    }

    override suspend fun ask(system: String, user: String, schema: Map<String, Any>, maxTokens: Long, deep: Boolean): String = withContext(Dispatchers.IO) {
        val strict = JSONObject()
            .put("type", "json_schema")
            .put("json_schema", JSONObject().put("name", "reply").put("strict", true).put("schema", JSONObject(schema)))
        try {
            complete(system, user, maxTokens, strict)
        } catch (e: HttpFailure) {
            if (e.code != 400) throw e.toCloud(provider, model)
            // Some models reject strict schemas: plain JSON mode with the schema spelled out.
            val loose = JSONObject().put("type", "json_object")
            try {
                complete("$system\n\nReply with one JSON object only, matching this JSON schema:\n${JSONObject(schema)}", user, maxTokens, loose)
            } catch (e2: HttpFailure) {
                throw e2.toCloud(provider, model)
            }
        }
    }

    private fun complete(system: String, user: String, maxTokens: Long, format: JSONObject): String {
        val body = JSONObject()
            .put("model", model)
            .put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", system))
                    .put(JSONObject().put("role", "user").put("content", user)),
            )
            .put("response_format", format)
        // OpenAI's newer models take max_completion_tokens; the compatible services take max_tokens.
        body.put(if (provider == CloudProvider.OPENAI) "max_completion_tokens" else "max_tokens", maxTokens)
        val reply = JSONObject(http("POST", "/chat/completions", body.toString()))
        val choice = reply.optJSONArray("choices")?.optJSONObject(0) ?: throw CloudAiException("${provider.label} sent an empty answer. Try again.")
        val message = choice.optJSONObject("message")
        if (!message?.optString("refusal").isNullOrBlank() && message?.isNull("refusal") == false) {
            throw CloudAiException("${provider.label} declined this request. Try asking differently.")
        }
        if (choice.optString("finish_reason") == "length") throw CloudAiException("The answer was too long. Try a smaller request.")
        return message?.optString("content").orEmpty().ifBlank { throw CloudAiException("${provider.label} sent an empty answer. Try again.") }
    }

    /** Model ids this key can use (chat models first), for the model picker. */
    suspend fun listModels(): List<String> = withContext(Dispatchers.IO) {
        val data = try {
            JSONObject(http("GET", "/models", null)).optJSONArray("data") ?: JSONArray()
        } catch (e: HttpFailure) {
            throw e.toCloud(provider, model)
        }
        val ids = (0 until data.length()).mapNotNull { data.optJSONObject(it)?.optString("id")?.removePrefix("models/") }
        ids.filter { usableChatModel(provider, it) }.distinct().sorted()
    }

    private class HttpFailure(val code: Int, val body: String) : IOException("HTTP $code")

    private fun HttpFailure.toCloud(p: CloudProvider, m: String): CloudAiException = when (code) {
        401 -> CloudAiException("${p.label} didn't accept this API key. Check it in AI models → Cloud.", this)
        403 -> CloudAiException("This ${p.label} key isn't allowed to use $m.", this)
        404 -> CloudAiException("${p.label} has no model called “$m” for this key. Pick another in AI models → Cloud.", this)
        402, 429 -> CloudAiException(
            if (body.contains("quota", true) || body.contains("credit", true) || code == 402) "Your ${p.label} account is out of credit." else "${p.label} is busy for this key right now. Try again in a minute.",
            this,
        )
        -1 -> CloudAiException("Couldn't reach ${p.label}. Check your internet connection.", this)
        else -> CloudAiException("${p.label} couldn't answer (error $code). Try again.", this)
    }

    private fun http(method: String, path: String, body: String?): String {
        val conn = try {
            (URL(provider.baseUrl + path).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 20_000
                readTimeout = 300_000
                setRequestProperty("Authorization", "Bearer $apiKey")
                setRequestProperty("Accept", "application/json")
                if (provider == CloudProvider.OPENROUTER) setRequestProperty("X-Title", "CineMind")
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                }
            }
        } catch (e: IOException) {
            throw HttpFailure(-1, e.message.orEmpty())
        }
        try {
            val code = try { conn.responseCode } catch (e: IOException) { throw HttpFailure(-1, e.message.orEmpty()) }
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw HttpFailure(code, text.take(2_000))
            return text
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        /** Keeps text chat models; drops embeddings, speech, image and realtime endpoints. */
        fun usableChatModel(provider: CloudProvider, id: String): Boolean {
            val s = id.lowercase()
            val excluded = listOf("embed", "tts", "whisper", "transcribe", "audio", "realtime", "image", "dall-e", "moderation", "search", "live", "aqa", "veo", "imagen")
            if (excluded.any { it in s }) return false
            return when (provider) {
                CloudProvider.OPENAI -> s.startsWith("gpt-") || s.startsWith("chatgpt") || Regex("^o\\d").containsMatchIn(s)
                CloudProvider.GEMINI -> s.startsWith("gemini") || s.startsWith("gemma")
                else -> true
            }
        }
    }
}
