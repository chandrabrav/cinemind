package com.cinemind.feature.export.engine

import android.net.Uri

sealed interface ExportState {
    val projectId: String?
    val isActive: Boolean get() = false

    data object Idle : ExportState {
        override val projectId: String? = null
    }

    data class Preparing(override val projectId: String) : ExportState {
        override val isActive get() = true
    }

    /** [progress] is 0..1, or null while the encoder can't report it yet. */
    data class Running(override val projectId: String, val progress: Float?) : ExportState {
        override val isActive get() = true
    }

    data class Saving(override val projectId: String) : ExportState {
        override val isActive get() = true
    }

    data class Success(
        override val projectId: String,
        val uri: Uri,
        val displayName: String,
        val sizeBytes: Long,
        val durationMs: Long,
    ) : ExportState

    data class Failed(override val projectId: String, val message: String) : ExportState

    data class Cancelled(override val projectId: String) : ExportState
}
