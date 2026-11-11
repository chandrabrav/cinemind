package com.cinemind.core.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.cinemind.core.domain.model.Clip
import com.cinemind.core.domain.model.MediaAsset
import com.cinemind.core.domain.model.Project
import com.cinemind.core.domain.model.Track
import kotlinx.coroutines.flow.Flow

@Dao
interface ProjectDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(project: Project)

    @Query("SELECT * FROM projects WHERE id = :id")
    suspend fun getProject(id: String): Project?

    @Query("SELECT * FROM projects ORDER BY updatedAt DESC")
    fun getAllProjects(): Flow<List<Project>>

    @Update
    suspend fun update(project: Project)

    @Delete
    suspend fun delete(project: Project)

    @Query("DELETE FROM projects WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT COUNT(*) FROM projects")
    suspend fun count(): Int
}

@Dao
interface TrackDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(track: Track)

    // `index` is an SQL keyword, so the column name must be quoted.
    @Query("SELECT * FROM tracks WHERE projectId = :projectId ORDER BY `index` ASC")
    fun getTracksForProject(projectId: String): Flow<List<Track>>

    @Update
    suspend fun update(track: Track)

    @Query("DELETE FROM tracks WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM tracks WHERE projectId = :projectId")
    suspend fun deleteByProjectId(projectId: String)
}

@Dao
interface ClipDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(clip: Clip)

    @Query("SELECT * FROM clips WHERE trackId = :trackId ORDER BY timelineInUs ASC")
    fun getClipsForTrack(trackId: String): Flow<List<Clip>>

    @Query("SELECT * FROM clips WHERE id = :id")
    suspend fun getClip(id: String): Clip?

    @Update
    suspend fun update(clip: Clip)

    @Query("DELETE FROM clips WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM clips WHERE trackId = :trackId")
    suspend fun deleteByTrackId(trackId: String)
}

@Dao
interface MediaAssetDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(asset: MediaAsset)

    @Query("SELECT * FROM media_assets WHERE id = :id")
    suspend fun getAsset(id: String): MediaAsset?

    @Query("SELECT * FROM media_assets ORDER BY importDate DESC")
    fun getAllAssets(): Flow<List<MediaAsset>>

    @Update
    suspend fun update(asset: MediaAsset)

    @Query("DELETE FROM media_assets WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT * FROM media_assets WHERE uri LIKE :query ORDER BY importDate DESC")
    fun searchAssets(query: String): Flow<List<MediaAsset>>
}
