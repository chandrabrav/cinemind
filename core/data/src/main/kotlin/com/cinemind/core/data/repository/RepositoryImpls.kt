package com.cinemind.core.data.repository

import com.cinemind.core.data.local.dao.ClipDao
import com.cinemind.core.data.local.dao.EditBriefDao
import com.cinemind.core.data.local.dao.MediaAssetDao
import com.cinemind.core.data.local.dao.ProjectDao
import com.cinemind.core.data.local.dao.TrackDao
import com.cinemind.core.data.local.database.AppDatabase
import androidx.room.withTransaction
import com.cinemind.core.domain.audio.MusicSettings
import com.cinemind.core.domain.audio.ProjectAudio
import com.cinemind.core.domain.model.Clip
import com.cinemind.core.domain.model.EditBrief
import com.cinemind.core.domain.model.MediaAsset
import com.cinemind.core.domain.model.Project
import com.cinemind.core.domain.model.ProjectCopier
import com.cinemind.core.domain.model.ProjectFormat
import com.cinemind.core.domain.model.ProjectSummary
import com.cinemind.core.domain.model.ProjectTimeline
import com.cinemind.core.domain.model.Track
import com.cinemind.core.domain.model.TrackType
import com.cinemind.core.domain.repository.ClipRepository
import com.cinemind.core.domain.repository.EditBriefRepository
import com.cinemind.core.domain.repository.MediaAssetRepository
import com.cinemind.core.domain.repository.ProjectRepository
import com.cinemind.core.domain.repository.TrackRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class ProjectRepositoryImpl(
    private val database: AppDatabase,
) : ProjectRepository {
    private val projectDao: ProjectDao = database.projectDao()

    override suspend fun createProject(project: Project): String {
        projectDao.insert(project)
        return project.id
    }

    override suspend fun createProjectWithTimeline(timeline: ProjectTimeline): String {
        database.withTransaction {
            projectDao.insert(timeline.project)
            timeline.tracks.forEach { database.trackDao().insert(it) }
            timeline.clips.forEach { database.clipDao().insert(it) }
        }
        return timeline.project.id
    }

    override suspend fun getProject(id: String): Project? = projectDao.getProject(id)

    override fun getAllProjects(): Flow<List<Project>> = projectDao.getAllProjects()

    override fun observeProject(id: String): Flow<Project?> = projectDao.observeProject(id)

    override suspend fun setProjectFormat(id: String, format: ProjectFormat) {
        database.withTransaction {
            val project = projectDao.getProject(id) ?: return@withTransaction
            projectDao.update(
                project.copy(
                    aspectRatio = format.ratio,
                    resolutionWidth = format.width,
                    resolutionHeight = format.height,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        }
    }

    override fun getProjectSummaries(): Flow<List<ProjectSummary>> = projectDao.getProjectSummaries()

    override suspend fun getProjectTimeline(id: String): ProjectTimeline? =
        database.withTransaction { readTimeline(id) }

    private suspend fun readTimeline(id: String): ProjectTimeline? {
        val project = projectDao.getProject(id) ?: return null
        val tracks = database.trackDao().getTracksForProjectOnce(id)
        val clips = if (tracks.isEmpty()) {
            emptyList()
        } else {
            database.clipDao().getClipsForTracksOnce(tracks.map { it.id })
        }
        return ProjectTimeline(project, tracks, clips)
    }

    override suspend fun renameProject(id: String, name: String) =
        projectDao.rename(id, name, System.currentTimeMillis())

    override suspend fun getProjectAudio(projectId: String): ProjectAudio = database.withTransaction {
        val tracks = database.trackDao().getTracksForProjectOnce(projectId)
        val video = tracks.firstOrNull { it.type == TrackType.VIDEO }
        val musicTrack = tracks.firstOrNull { it.type == TrackType.AUDIO }
        val musicClip = musicTrack?.let { database.clipDao().getClipsForTracksOnce(listOf(it.id)).firstOrNull() }
        ProjectAudio(
            music = musicClip?.let { c ->
                MusicSettings(
                    assetId = c.assetId,
                    startOffsetUs = c.sourceInUs,
                    volume = musicTrack?.volume ?: 0.8f,
                    fadeInMs = c.transitionInMs,
                    fadeOutMs = c.transitionOutMs,
                )
            },
            originalVolume = video?.volume ?: 1f,
        )
    }

    override suspend fun saveMusic(projectId: String, music: MusicSettings?) {
        database.withTransaction {
            val project = projectDao.getProject(projectId) ?: return@withTransaction
            val tracks = database.trackDao().getTracksForProjectOnce(projectId)
            val existing = tracks.firstOrNull { it.type == TrackType.AUDIO }
            if (music == null) {
                existing?.let { database.trackDao().deleteById(it.id) } // cascades to its clip
            } else {
                val asset = database.mediaAssetDao().getAsset(music.assetId) ?: return@withTransaction
                val track = existing?.copy(volume = music.volume)?.also { database.trackDao().update(it) }
                    ?: Track(projectId = projectId, type = TrackType.AUDIO, index = 1, name = "Music", volume = music.volume)
                        .also { database.trackDao().insert(it) }
                database.clipDao().deleteByTrackId(track.id)
                database.clipDao().insert(
                    Clip(
                        trackId = track.id,
                        assetId = asset.id,
                        // The music clip spans the video; renderers recompute its length from the video track.
                        timelineInUs = 0,
                        timelineOutUs = project.durationUs,
                        sourceInUs = music.startOffsetUs.coerceIn(0, asset.durationUs.coerceAtLeast(0)),
                        sourceOutUs = asset.durationUs,
                        transitionInMs = music.fadeInMs,
                        transitionOutMs = music.fadeOutMs,
                    ),
                )
            }
            projectDao.update(project.copy(updatedAt = System.currentTimeMillis()))
        }
    }

    override suspend fun setOriginalAudioVolume(projectId: String, volume: Float) {
        database.withTransaction {
            val video = database.trackDao().getTracksForProjectOnce(projectId).firstOrNull { it.type == TrackType.VIDEO }
                ?: return@withTransaction
            database.trackDao().update(video.copy(volume = volume.coerceIn(0f, 1f)))
        }
    }

    override suspend fun saveTrackClips(projectId: String, trackId: String, clips: List<Clip>) {
        require(clips.all { it.trackId == trackId }) { "All clips must belong to track $trackId" }
        database.withTransaction {
            val project = projectDao.getProject(projectId) ?: return@withTransaction
            database.clipDao().deleteByTrackId(trackId)
            clips.forEach { database.clipDao().insert(it) }
            projectDao.update(
                project.copy(
                    durationUs = clips.maxOfOrNull { it.timelineOutUs } ?: 0L,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        }
    }

    override suspend fun duplicateProject(id: String, newName: String): String? =
        database.withTransaction {
            val source = readTimeline(id) ?: return@withTransaction null
            val copy = ProjectCopier.duplicate(source, newName)
            projectDao.insert(copy.project)
            copy.tracks.forEach { database.trackDao().insert(it) }
            copy.clips.forEach { database.clipDao().insert(it) }
            database.editBriefDao().get(id)?.let { brief ->
                database.editBriefDao().upsert(brief.copy(projectId = copy.project.id, updatedAt = copy.project.updatedAt))
            }
            copy.project.id
        }

    override suspend fun updateProject(project: Project) = projectDao.update(project)

    // Tracks and clips are removed by the CASCADE foreign keys. Source media files are never touched.
    override suspend fun deleteProject(id: String) = projectDao.deleteById(id)

    override suspend fun getProjectCount(): Int = projectDao.count()
}

class TrackRepositoryImpl(
    private val trackDao: TrackDao,
) : TrackRepository {
    override suspend fun createTrack(track: Track): String {
        trackDao.insert(track)
        return track.id
    }

    override fun getTracksForProject(projectId: String): Flow<List<Track>> =
        trackDao.getTracksForProject(projectId)

    override suspend fun updateTrack(track: Track) = trackDao.update(track)

    override suspend fun deleteTrack(id: String) = trackDao.deleteById(id)

    override suspend fun deleteTracksForProject(projectId: String) =
        trackDao.deleteByProjectId(projectId)
}

class ClipRepositoryImpl(
    private val clipDao: ClipDao,
) : ClipRepository {
    override suspend fun createClip(clip: Clip): String {
        clipDao.insert(clip)
        return clip.id
    }

    override fun getClipsForTrack(trackId: String): Flow<List<Clip>> =
        clipDao.getClipsForTrack(trackId)

    override suspend fun getClip(id: String): Clip? = clipDao.getClip(id)

    override suspend fun updateClip(clip: Clip) = clipDao.update(clip)

    override suspend fun deleteClip(id: String) = clipDao.deleteById(id)

    override suspend fun deleteClipsForTrack(trackId: String) = clipDao.deleteByTrackId(trackId)
}

class MediaAssetRepositoryImpl(
    private val mediaAssetDao: MediaAssetDao,
) : MediaAssetRepository {
    override suspend fun createAsset(asset: MediaAsset): String {
        mediaAssetDao.insert(asset)
        return asset.id
    }

    override suspend fun getAsset(id: String): MediaAsset? = mediaAssetDao.getAsset(id)

    override suspend fun getAssetByUri(uri: String): MediaAsset? = mediaAssetDao.getAssetByUri(uri)

    override fun getAllAssets(): Flow<List<MediaAsset>> = mediaAssetDao.getAllAssets()

    override suspend fun updateAsset(asset: MediaAsset) = mediaAssetDao.update(asset)

    override suspend fun deleteAsset(id: String) = mediaAssetDao.deleteById(id)

    override fun searchAssets(query: String): Flow<List<MediaAsset>> =
        mediaAssetDao.searchAssets("%$query%")
}

class EditBriefRepositoryImpl(
    private val editBriefDao: EditBriefDao,
) : EditBriefRepository {
    override fun observeBrief(projectId: String): Flow<EditBrief> =
        editBriefDao.observe(projectId).map { it ?: EditBrief(projectId = projectId, updatedAt = 0) }

    override suspend fun saveBrief(brief: EditBrief) =
        editBriefDao.upsert(brief.copy(updatedAt = System.currentTimeMillis()))
}
