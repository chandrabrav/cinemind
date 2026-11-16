package com.cinemind.feature.ai.cloud

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.messages.JsonOutputFormat
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Duration

/** A failure explained in words the user can act on. */
class CloudAiException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Calls Claude with the user's own key (official Anthropic Java SDK): one request, a JSON reply
 * constrained by a schema. Low effort: the assistant answers short editing requests.
 */
class ClaudeClient(apiKey: String, private val model: CloudModel) {

    private val client: AnthropicClient = AnthropicOkHttpClient.builder()
        .apiKey(apiKey)
        .timeout(Duration.ofSeconds(180))
        .maxRetries(2)
        .build()

    suspend fun ask(system: String, user: String, schema: Map<String, Any>, maxTokens: Long = 4_000L, effort: OutputConfig.Effort = OutputConfig.Effort.LOW): String = withContext(Dispatchers.IO) {
        val schemaBuilder = JsonOutputFormat.Schema.builder()
        schema.forEach { (k, v) -> schemaBuilder.putAdditionalProperty(k, JsonValue.from(v)) }
        val params = MessageCreateParams.builder()
            .model(model.id)
            .maxTokens(maxTokens)
            .system(system)
            .addUserMessage(user)
            .outputConfig(
                OutputConfig.builder()
                    .effort(effort)
                    .format(JsonOutputFormat.builder().schema(schemaBuilder.build()).build())
                    .build(),
            )
            .build()
        val response = try {
            client.messages().create(params)
        } catch (e: UnauthorizedException) {
            throw CloudAiException("Claude didn't accept this API key. Check it in Settings → Cloud AI.", e)
        } catch (e: PermissionDeniedException) {
            throw CloudAiException("This API key can't use ${model.label}. Pick another model in Settings → Cloud AI.", e)
        } catch (e: RateLimitException) {
            throw CloudAiException("Claude is busy for this key right now (rate limit). Try again in a minute.", e)
        } catch (e: AnthropicIoException) {
            throw CloudAiException("Couldn't reach Claude. Check your internet connection.", e)
        } catch (e: AnthropicServiceException) {
            throw CloudAiException("Claude couldn't answer (error ${e.statusCode()}). Try again.", e)
        }
        when (response.stopReason().orElse(null)) {
            StopReason.REFUSAL -> throw CloudAiException("Claude declined this request. Try asking differently.")
            StopReason.MAX_TOKENS -> throw CloudAiException("The answer was too long. Try a smaller request.")
            else -> Unit
        }
        response.content().mapNotNull { it.text().orElse(null)?.text() }.joinToString("").ifBlank {
            throw CloudAiException("Claude sent an empty answer. Try again.")
        }
    }

    fun close() {
        runCatching { client.close() }
    }
}
