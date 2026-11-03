package com.cinemind.feature.ai.analysis.model

import com.cinemind.core.domain.model.MediaAnalysis
import com.cinemind.core.domain.model.MediaAsset

data class AnalysisRequest(
    val asset: MediaAsset,
    val analysisTypes: Set<AnalysisType> = AnalysisType.all()
)

enum class AnalysisType {
    SCENE_DETECTION, OBJECT_TRACKING, QUALITY_SCORING, BEAT_DETECTION, COLOR_ANALYSIS;

    companion object {
        fun all(): Set<AnalysisType> = setOf(
            SCENE_DETECTION, OBJECT_TRACKING, QUALITY_SCORING, BEAT_DETECTION, COLOR_ANALYSIS
        )
    }
}

data class AnalysisResult(
    val assetId: String,
    val analysis: MediaAnalysis,
    val durationMs: Long,
    val timestamp: Long = System.currentTimeMillis()
)

sealed class AnalysisState {
    object Idle : AnalysisState()
    class Loading(val assetId: String) : AnalysisState()
    class Success(val result: AnalysisResult) : AnalysisState()
    class Error(val exception: Throwable, val assetId: String) : AnalysisState()
}