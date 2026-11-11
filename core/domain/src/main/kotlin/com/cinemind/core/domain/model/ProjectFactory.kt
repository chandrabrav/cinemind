package com.cinemind.core.domain.model

/** A project together with the tracks and clips that make up its initial timeline. */
data class ProjectTimeline(
    val project: Project,
    val tracks: List<Track>,
    val clips: List<Clip>,
)

/**
 * Builds a new editable project from imported media, placing clips back to back on one
 * video track in the order given. Pure logic — no Android or storage dependencies.
 */
object ProjectFactory {

    /** How long a still image occupies the timeline when first added. */
    const val DEFAULT_IMAGE_DURATION_US = 3_000_000L

    fun fromAssets(
        name: String,
        assets: List<MediaAsset>,
        now: Long = System.currentTimeMillis(),
    ): ProjectTimeline {
        require(assets.isNotEmpty()) { "A project needs at least one media item" }

        val first = assets.first()
        val portrait = first.height >= first.width
        val project = Project(
            name = name,
            createdAt = now,
            updatedAt = now,
            aspectRatio = if (portrait) "9:16" else "16:9",
            resolutionWidth = if (portrait) 1080 else 1920,
            resolutionHeight = if (portrait) 1920 else 1080,
            frameRate = normalizeFrameRate(assets.firstOrNull { it.isVideo }?.frameRate),
        )
        val track = Track(
            projectId = project.id,
            type = TrackType.VIDEO,
            index = 0,
            name = "Video 1",
        )

        var cursorUs = 0L
        val clips = assets.map { asset ->
            val durationUs = timelineDurationUs(asset)
            Clip(
                trackId = track.id,
                assetId = asset.id,
                timelineInUs = cursorUs,
                timelineOutUs = cursorUs + durationUs,
                sourceInUs = 0,
                sourceOutUs = durationUs,
            ).also { cursorUs += durationUs }
        }

        return ProjectTimeline(
            project = project.copy(durationUs = cursorUs),
            tracks = listOf(track),
            clips = clips,
        )
    }

    fun timelineDurationUs(asset: MediaAsset): Long =
        if (asset.isVideo && asset.durationUs > 0) asset.durationUs else DEFAULT_IMAGE_DURATION_US

    /** Snaps a measured source frame rate to a standard project rate (24, 25, 30 or 60). */
    fun normalizeFrameRate(fps: Float?): Int = when {
        fps == null || fps.isNaN() || fps <= 0f -> 30
        fps < 24.5f -> 24
        fps < 27.5f -> 25
        fps < 45f -> 30
        else -> 60
    }
}
