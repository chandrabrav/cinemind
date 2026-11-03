package com.cinemind.feature.ai.cloud

import com.cinemind.feature.ai.llm.model.ChatRequest
import com.cinemind.feature.ai.llm.model.ChatResponse
import kotlinx.coroutines.flow.Flow

interface CloudAiProvider {
    val providerName: String
    val isAvailable: Boolean
    val requiresApiKey: Boolean

    suspend fun configure(apiKey: String, baseUrl: String?)
    suspend fun chat(request: ChatRequest): ChatResponse
    fun chatStream(request: ChatRequest): Flow<ChatResponse>
    suspend fun testConnection(): Boolean
}

data class CloudAiConfig(
    val provider: ProviderType,
    val apiKey: String?,
    val baseUrl: String?,
    val isEnabled: Boolean = false
)

enum class ProviderType {
    OPENAI, ANTHROPIC, CUSTOM, NONE
}

sealed class CloudAiState {
    object NotConfigured : CloudAiState()
    object Configuring : CloudAiState()
    class Configured(val provider: ProviderType) : CloudAiState()
    class Error(val message: String) : CloudAiState()
}