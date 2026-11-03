package com.cinemind.feature.ai.generation.model

import com.cinemind.core.domain.model.Project
import com.cinemind.core.domain.model.Track
import com.cinemind.core.domain.model.Clip

data class AutoEditRequest(
    val projectId: String,
    val userPrompt: String,
    val stylePreference: String? = null,
    val targetDurationMs: Long? = null
)

data class AutoEditResult(
    val projectId: String,
    val generatedTracks: List<Track>,
    val generatedClips: List<Clip>,
    val editingPlan: com.cinemind.feature.ai.llm.model.EditingPlan
)

sealed class GenerationState {
    object Idle : GenerationState()
    object Analyzing : GenerationState()
    class Planning(val progress: Float) : GenerationState()
    class Applying(val progress: Float) : GenerationState()
    class Success(val project: Project) : GenerationState()
    class Error(val exception: Throwable) : GenerationState()
}