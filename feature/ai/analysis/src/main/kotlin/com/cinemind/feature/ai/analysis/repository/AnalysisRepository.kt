package com.cinemind.feature.ai.analysis.repository

import com.cinemind.feature.ai.analysis.model.*
import kotlinx.coroutines.flow.Flow

interface AnalysisRepository {
    suspend fun analyzeAsset(request: AnalysisRequest): AnalysisResult
    fun analyzeAssetStream(request: AnalysisRequest): Flow<AnalysisState>
    suspend fun getCachedAnalysis(assetId: String): MediaAnalysis?
    suspend fun cacheAnalysis(assetId: String, analysis: MediaAnalysis)
}