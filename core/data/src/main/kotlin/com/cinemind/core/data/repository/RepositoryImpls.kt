package com.cinemind.core.data.repository

import com.cinemind.core.data.local.dao.ClipDao
import com.cinemind.core.data.local.dao.MediaAssetDao
import com.cinemind.core.data.local.dao.ProjectDao
import com.cinemind.core.data.local.dao.TrackDao
import com.cinemind.core.data.local.database.AppDatabase
import androidx.room.withTransaction
import com.cinemind.core.domain.model.Clip
import com.cinemind.core.domain.model.MediaAsset
import com.cinemind.core.domain.model.Project
import com.cinemind.core.domain.model.ProjectCopier
import com.cinemind.core.domain.model.ProjectSummary
import com.cinemind.core.domain.model.ProjectTimeline
import com.cinemind.core.domain.model.Track
import com.cinemind.core.domain.repository.ClipRepository
import com.cinemind.core.domain.repository.MediaAssetRepository
import com.cinemind.core.domain.repository.ProjectRepository
import com.cinemind.core.domain.repository.TrackRepository
import kotlinx.coroutines.flow.Flow

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

    override suspend fun duplicateProject(id: String, newName: String): String? =
        database.withTransaction {
            val source = readTimeline(id) ?: return@withTransaction null
            val copy = ProjectCopier.duplicate(source, newName)
            projectDao.insert(copy.project)
            copy.tracks.forEach { database.trackDao().insert(it) }
            copy.clips.forEach { database.clipDao().insert(it) }
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
