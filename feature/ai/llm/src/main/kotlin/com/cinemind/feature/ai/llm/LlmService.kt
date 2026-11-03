package com.cinemind.feature.ai.llm

import com.cinemind.feature.ai.llm.model.*

interface LlmService {
    suspend fun chat(request: ChatRequest): ChatResponse
    fun chatStream(request: ChatRequest): kotlinx.coroutines.flow.Flow<ChatResponse>
    suspend fun generateEditingPlan(prompt: String, availableAssets: List<String>): EditingPlan
    suspend fun isModelLoaded(): Boolean
    suspend fun loadModel(): Boolean
    suspend fun unloadModel()
}

interface EditingPlanGenerator {
    suspend fun generatePlan(
        userPrompt: String,
        assets: List<com.cinemind.core.domain.model.MediaAsset>,
        analysisResults: Map<String, com.cinemind.core.domain.model.MediaAnalysis>
    ): EditingPlan
}