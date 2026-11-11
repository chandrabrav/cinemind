package com.cinemind.core.domain.model

import androidx.room.Embedded
import java.util.UUID

/** A project as shown in the project list: the project row plus derived display data. */
data class ProjectSummary(
    @Embedded val project: Project,
    val clipCount: Int,
    /** Source URI of the first clip, used as the cover image; null for an empty project. */
    val coverUri: String?,
)

/** Rules for user-entered project names. */
object ProjectNames {
    const val MAX_LENGTH = 80

    /** Trims and collapses whitespace; returns null if nothing usable remains. Over-long names are cut. */
    fun sanitize(input: String): String? {
        val cleaned = input.trim().replace(Regex("\\s+"), " ")
        if (cleaned.isEmpty()) return null
        return cleaned.take(MAX_LENGTH).trimEnd()
    }

    /** Name for a duplicate, kept within [MAX_LENGTH]. */
    fun copyOf(name: String): String {
        val suffix = " (copy)"
        return name.take(MAX_LENGTH - suffix.length).trimEnd() + suffix
    }
}

/** Copies a whole project timeline under fresh ids, so the copy can be edited independently. */
object ProjectCopier {

    /**
     * Every project, track and clip id is new; clips still reference the same media assets
     * (source files are shared, never duplicated). Timing and clip properties are preserved.
     */
    fun duplicate(
        source: ProjectTimeline,
        newName: String,
        now: Long = System.currentTimeMillis(),
        newId: () -> String = { UUID.randomUUID().toString() },
    ): ProjectTimeline {
        val project = source.project.copy(
            id = newId(),
            name = newName,
            createdAt = now,
            updatedAt = now,
        )
        val trackIds = source.tracks.associate { it.id to newId() }
        val tracks = source.tracks.map { it.copy(id = trackIds.getValue(it.id), projectId = project.id) }
        val clips = source.clips.mapNotNull { clip ->
            trackIds[clip.trackId]?.let { clip.copy(id = newId(), trackId = it) }
        }
        return ProjectTimeline(project, tracks, clips)
    }
}
