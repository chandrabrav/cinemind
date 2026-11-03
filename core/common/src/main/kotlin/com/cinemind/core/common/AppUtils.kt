package com.cinemind.core.common

import timber.log.Timber

object CineMindLogger {
    fun d(message: String, tag: String = "CineMind") = Timber.tag(tag).d(message)
    fun i(message: String, tag: String = "CineMind") = Timber.tag(tag).i(message)
    fun w(message: String, tag: String = "CineMind") = Timber.tag(tag).w(message)
    fun e(message: String, tag: String = "CineMind") = Timber.tag(tag).e(message)
    fun e(throwable: Throwable, message: String, tag: String = "CineMind") = Timber.tag(tag).e(throwable, message)
}

object Constants {
    const val APPLICATION_ID = "com.cinemind.app"
    
    // Timeline
    const val DEFAULT_FRAME_RATE = 30
    const val DEFAULT_ASPECT_RATIO = "9:16"
    const val DEFAULT_RESOLUTION_WIDTH = 1080
    const val DEFAULT_RESOLUTION_HEIGHT = 1920
    
    // AI
    const val MAX_LOCAL_LLM_TOKENS = 2048
    const val AI_MODEL_CACHE_SIZE_MB = 2048
    
    // Export
    const val EXPORT_PROGRESS_UPDATE_INTERVAL_MS = 500
    
    // Cache
    const val MAX_PROXY_CACHE_GB = 2L
    const val PROXY_CACHE_DIR_NAME = "proxies"
    const val MODEL_CACHE_DIR_NAME = "models"
    
    // Database
    const val DATABASE_VERSION = 1
    
    // Performance
    const val MIN_FPS_FOR_SMOOTH_PREVIEW = 30
    const val MAX_TRACKS_PER_PROJECT = 16
}

sealed class Result<out T> {
    data class Success<out T>(val data: T) : Result<T>()
    data class Error(val exception: Throwable) : Result<Nothing>()
    object Loading : Result<Nothing>()
}

data class PagingParams(
    val page: Int = 0,
    val pageSize: Int = 20
)