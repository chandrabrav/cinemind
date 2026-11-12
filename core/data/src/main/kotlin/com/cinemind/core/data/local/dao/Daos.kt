package com.cinemind.core.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import com.cinemind.core.domain.model.Clip
import com.cinemind.core.domain.model.EditBrief
import com.cinemind.core.domain.model.MediaAsset
import com.cinemind.core.domain.model.Project
import com.cinemind.core.domain.model.ProjectSummary
import com.cinemind.core.domain.model.Track
import kotlinx.coroutines.flow.Flow

@Dao
interface ProjectDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(project: Project)

    @Query("SELECT * FROM projects WHERE id = :id")
    fun observeProject(id: String): Flow<Project?>

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

    /**
     * Projects with their clip count and the source of their first clip (used as a cover image),
     * newest first. Computed in SQL so the home screen needs one query, not one per project.
     */
    @Query(
        """
        SELECT p.*,
            (SELECT COUNT(*) FROM clips c JOIN tracks t ON c.trackId = t.id AND t.type = 'VIDEO'
                WHERE t.projectId = p.id) AS clipCount,
            (SELECT a.uri FROM clips c
                JOIN tracks t ON c.trackId = t.id AND t.type = 'VIDEO'
                JOIN media_assets a ON a.id = c.assetId
                WHERE t.projectId = p.id
                ORDER BY t.`index` ASC, c.timelineInUs ASC LIMIT 1) AS coverUri
        FROM projects p
        ORDER BY p.updatedAt DESC
        """,
    )
    fun getProjectSummaries(): Flow<List<ProjectSummary>>

    @Query("UPDATE projects SET name = :name, updatedAt = :updatedAt WHERE id = :id")
    suspend fun rename(id: String, name: String, updatedAt: Long)

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

    @Query("SELECT * FROM tracks WHERE projectId = :projectId ORDER BY `index` ASC")
    suspend fun getTracksForProjectOnce(projectId: String): List<Track>

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

    @Query("SELECT * FROM clips WHERE trackId IN (:trackIds) ORDER BY timelineInUs ASC")
    suspend fun getClipsForTracksOnce(trackIds: List<String>): List<Clip>

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

    @Query("SELECT * FROM media_assets WHERE uri = :uri LIMIT 1")
    suspend fun getAssetByUri(uri: String): MediaAsset?

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

@Dao
interface EditBriefDao {
    @Upsert
    suspend fun upsert(brief: EditBrief)

    @Query("SELECT * FROM edit_briefs WHERE projectId = :projectId")
    suspend fun get(projectId: String): EditBrief?

    @Query("SELECT * FROM edit_briefs WHERE projectId = :projectId")
    fun observe(projectId: String): Flow<EditBrief?>
}

@Dao
interface CaptionDao {
    @Query("SELECT * FROM captions WHERE projectId = :projectId ORDER BY startUs")
    fun observe(projectId: String): Flow<List<com.cinemind.core.domain.captions.Caption>>

    @Query("SELECT * FROM captions WHERE projectId = :projectId ORDER BY startUs")
    suspend fun get(projectId: String): List<com.cinemind.core.domain.captions.Caption>

    @Query("DELETE FROM captions WHERE projectId = :projectId")
    suspend fun deleteForProject(projectId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(captions: List<com.cinemind.core.domain.captions.Caption>)

    /** Replaces the whole caption layer in one transaction. */
    @androidx.room.Transaction
    suspend fun replaceAll(projectId: String, captions: List<com.cinemind.core.domain.captions.Caption>) {
        deleteForProject(projectId)
        insertAll(captions.map { it.copy(projectId = projectId) })
    }

    @Upsert
    suspend fun upsertStyle(style: com.cinemind.core.domain.captions.CaptionStyle)

    @Query("SELECT * FROM caption_styles WHERE projectId = :projectId")
    fun observeStyle(projectId: String): Flow<com.cinemind.core.domain.captions.CaptionStyle?>

    @Query("SELECT * FROM caption_styles WHERE projectId = :projectId")
    suspend fun getStyle(projectId: String): com.cinemind.core.domain.captions.CaptionStyle?
}

@Dao
interface TextLayerDao {
    @Query("SELECT * FROM text_layers WHERE projectId = :projectId ORDER BY lane, startUs")
    fun observe(projectId: String): Flow<List<com.cinemind.core.domain.text.TextLayer>>

    @Query("SELECT * FROM text_layers WHERE projectId = :projectId ORDER BY lane, startUs")
    suspend fun get(projectId: String): List<com.cinemind.core.domain.text.TextLayer>

    @Query("DELETE FROM text_layers WHERE projectId = :projectId")
    suspend fun deleteForProject(projectId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(layers: List<com.cinemind.core.domain.text.TextLayer>)

    /** Replaces all text layers of a project in one transaction. */
    @androidx.room.Transaction
    suspend fun replaceAll(projectId: String, layers: List<com.cinemind.core.domain.text.TextLayer>) {
        deleteForProject(projectId)
        insertAll(layers.map { it.copy(projectId = projectId) })
    }
}
