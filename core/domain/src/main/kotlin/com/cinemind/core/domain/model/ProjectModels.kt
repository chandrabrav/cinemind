package com.cinemind.core.domain.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * Represents a video editing project containing tracks, clips, and settings.
 */
@Entity(
    tableName = "projects",
    foreignKeys = []
)
data class Project(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val aspectRatio: String = "9:16",
    val resolutionWidth: Int = 1080,
    val resolutionHeight: Int = 1920,
    val frameRate: Int = 30,
    val colorSpace: String = "REC709",
    val thumbnailUri: String? = null,
    val durationUs: Long = 0,
    val version: Int = 1
)

/**
 * Represents an editing track within a project.
 * Tracks can be video, audio, effects, or overlay type.
 */
@Entity(
    tableName = "tracks",
    foreignKeys = [
        ForeignKey(
            entity = Project::class,
            parentColumns = ["id"],
            childColumns = ["projectId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class Track(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val projectId: String,
    val type: TrackType,
    val index: Int,
    val name: String,
    val isEnabled: Boolean = true,
    val isLocked: Boolean = false,
    val volume: Float = 1.0f,
    val opacity: Float = 1.0f
)

enum class TrackType {
    VIDEO, AUDIO, EFFECT, OVERLAY
}

/**
 * Represents a clip placed on a timeline track.
 * Clips reference a source media asset and have trim points.
 */
@Entity(
    tableName = "clips",
    foreignKeys = [
        ForeignKey(
            entity = Track::class,
            parentColumns = ["id"],
            childColumns = ["trackId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = MediaAsset::class,
            parentColumns = ["id"],
            childColumns = ["assetId"],
            onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [Index(value = ["trackId"])]
)
data class Clip(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val trackId: String,
    val assetId: String,
    val timelineInUs: Long,
    val timelineOutUs: Long,
    val sourceInUs: Long,
    val sourceOutUs: Long,
    val speed: Float = 1.0f,
    val transformX: Float = 0.5f,
    val transformY: Float = 0.5f,
    val scale: Float = 1.0f,
    val rotation: Float = 0.0f,
    val opacity: Float = 1.0f,
    val transitionInMs: Long = 0,
    val transitionOutMs: Long = 0
)

/**
 * Represents an imported media asset (video, image, or audio file).
 */
@Entity(tableName = "media_assets")
data class MediaAsset(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val uri: String,
    val mimeType: String,
    val width: Int,
    val height: Int,
    val durationUs: Long,
    val frameRate: Float = 30.0f,
    val hasAudio: Boolean = false,
    val dateTaken: Long? = null,
    val proxyPath: String? = null,
    val importDate: Long = System.currentTimeMillis()
)

/**
 * AI analysis results for a media asset, stored as embedded JSON.
 */
data class MediaAnalysis(
    val scenes: List<SceneBoundary> = emptyList(),
    val objects: List<ObjectTrack> = emptyList(),
    val qualityScores: List<QualityScore> = emptyList(),
    val dominantColors: List<String> = emptyList(),
    val suggestedBeatsUs: List<Long> = emptyList()
)

data class SceneBoundary(
    val startUs: Long,
    val endUs: Long,
    val confidence: Float,
    val type: SceneType
)

enum class SceneType {
    ESTABLISHING, ACTION, DIALOGUE, TRANSITION, BROLL, CREDITS
}

data class ObjectTrack(
    val objectClass: String,
    val confidence: Float,
    val frames: List<ObjectFrame>
)

data class ObjectFrame(
    val timestampUs: Long,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
)

data class QualityScore(
    val timestampUs: Long,
    val sharpness: Float,
    val exposure: Float,
    val colorScore: Float,
    val overall: Float
)

/**
 * Immutable transform data for clip positioning and scaling.
 */
data class Transform(
    val x: Float = 0.5f,
    val y: Float = 0.5f,
    val scale: Float = 1.0f,
    val rotation: Float = 0.0f
) {
    companion object {
        val identity = Transform()
    }
}

/**
 * Reference to an effect applied to a clip.
 */
data class EffectRef(
    val effectId: String,
    val parameters: Map<String, Float> = emptyMap(),
    val enabled: Boolean = true
)

/**
 * Reference to a transition between clips.
 */
data class TransitionRef(
    val transitionId: String,
    val durationMs: Long = 300,
    val type: TransitionType = TransitionType.CROSS_DISSOLVE
)

enum class TransitionType {
    CROSS_DISSOLVE, PUSH, WIPE, FADE, SLIDE
}