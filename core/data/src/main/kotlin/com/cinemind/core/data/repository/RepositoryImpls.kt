package com.cinemind.core.data.repository

import com.cinemind.core.domain.model.*
import com.cinemind.core.domain.repository.*
import com.cinemind.core.data.local.dao.*
import com.cinemind.core.data.local.database.AppDatabase
import kotlinx.coroutines.flow.Flow

class ProjectRepositoryImpl(
    private val projectDao: ProjectDao,
    private val trackDao: TrackDao,
    private val clipDao: ClipDao,
    private val mediaAssetDao: MediaAssetDao
) : ProjectRepository {
    override suspend fun createProject(project: Project): String {
        val id = projectDao.insert(project).toString()
        return id
    }

    override suspend fun getProject(id: String): Project? = projectDao.getProject(id)

    override fun getAllProjects(): Flow<List<Project>> = projectDao.getAllProjects()

    override suspend fun updateProject(project: Project) = projectDao.update(project)

    override suspend fun deleteProject(id: String) {
        val project = projectDao.getProject(id) ?: return
        projectDao.delete(project)
    }

    override suspend fun getProjectCount(): Int = projectDao.count()
}

class TrackRepositoryImpl(
    private val trackDao: TrackDao
) : TrackRepository {
    override suspend fun createTrack(track: Track): String = trackDao.insert(track).toString()

    override fun getTracksForProject(projectId: String): Flow<List<Track>> =
        trackDao.getTracksForProject(projectId)

    override suspend fun updateTrack(track: Track) = trackDao.update(track)

    override suspend fun deleteTrack(id: String) = trackDao.delete(Track(id = id))

    override suspend fun deleteTracksForProject(projectId: String) {
        // Note: In production, fetch tracks first then delete individually
        // Room doesn't support delete by query directly without DAO method
    }
}

class ClipRepositoryImpl(
    private val clipDao: ClipDao
) : ClipRepository {
    override suspend fun createClip(clip: Clip): String = clipDao.insert(clip).toString()

    override fun getClipsForTrack(trackId: String): Flow<List<Clip>> =
        clipDao.getClipsForTrack(trackId)

    override suspend fun getClip(id: String): Clip? = clipDao.getClip(id)

    override suspend fun updateClip(clip: Clip) = clipDao.update(clip)

    override suspend fun deleteClip(id: String) = clipDao.delete(Clip(id = id))

    override suspend fun deleteClipsForTrack(trackId: String) = clipDao.deleteByTrackId(trackId)
}

class MediaAssetRepositoryImpl(
    private val mediaAssetDao: MediaAssetDao
) : MediaAssetRepository {
    override suspend fun createAsset(asset: MediaAsset): String = mediaAssetDao.insert(asset).toString()

    override suspend fun getAsset(id: String): MediaAsset? = mediaAssetDao.getAsset(id)

    override fun getAllAssets(): Flow<List<MediaAsset>> = mediaAssetDao.getAllAssets()

    override suspend fun updateAsset(asset: MediaAsset) = mediaAssetDao.update(asset)

    override suspend fun deleteAsset(id: String) = mediaAssetDao.delete(MediaAsset(id = id))

    override fun searchAssets(query: String): Flow<List<MediaAsset>> =
        mediaAssetDao.searchAssets("%$query%")
}