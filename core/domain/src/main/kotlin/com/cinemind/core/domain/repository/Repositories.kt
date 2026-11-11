package com.cinemind.core.domain.repository

import com.cinemind.core.domain.model.Clip
import com.cinemind.core.domain.model.MediaAsset
import com.cinemind.core.domain.model.Project
import com.cinemind.core.domain.model.Track
import kotlinx.coroutines.flow.Flow

interface ProjectRepository {
    suspend fun createProject(project: Project): String
    suspend fun getProject(id: String): Project?
    fun getAllProjects(): Flow<List<Project>>
    suspend fun updateProject(project: Project)
    suspend fun deleteProject(id: String)
    suspend fun getProjectCount(): Int
}

interface TrackRepository {
    suspend fun createTrack(track: Track): String
    fun getTracksForProject(projectId: String): Flow<List<Track>>
    suspend fun updateTrack(track: Track)
    suspend fun deleteTrack(id: String)
    suspend fun deleteTracksForProject(projectId: String)
}

interface ClipRepository {
    suspend fun createClip(clip: Clip): String
    fun getClipsForTrack(trackId: String): Flow<List<Clip>>
    suspend fun getClip(id: String): Clip?
    suspend fun updateClip(clip: Clip)
    suspend fun deleteClip(id: String)
    suspend fun deleteClipsForTrack(trackId: String)
}

interface MediaAssetRepository {
    suspend fun createAsset(asset: MediaAsset): String
    suspend fun getAsset(id: String): MediaAsset?
    fun getAllAssets(): Flow<List<MediaAsset>>
    suspend fun updateAsset(asset: MediaAsset)
    suspend fun deleteAsset(id: String)
    fun searchAssets(query: String): Flow<List<MediaAsset>>
}