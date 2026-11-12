package com.cinemind.core.domain.repository

import com.cinemind.core.domain.audio.MusicSettings
import com.cinemind.core.domain.audio.ProjectAudio
import com.cinemind.core.domain.model.Clip
import com.cinemind.core.domain.model.EditBrief
import com.cinemind.core.domain.model.MediaAsset
import com.cinemind.core.domain.model.Project
import com.cinemind.core.domain.model.ProjectFormat
import com.cinemind.core.domain.model.ProjectSummary
import com.cinemind.core.domain.model.ProjectTimeline
import com.cinemind.core.domain.model.Track
import kotlinx.coroutines.flow.Flow

interface ProjectRepository {
    suspend fun createProject(project: Project): String

    /** Atomically stores a project with its tracks and clips; returns the project id. */
    suspend fun createProjectWithTimeline(timeline: ProjectTimeline): String

    suspend fun getProject(id: String): Project?
    fun observeProject(id: String): Flow<Project?>

    /** Changes the output shape (aspect ratio + resolution) of a project. */
    suspend fun setProjectFormat(id: String, format: ProjectFormat)
    fun getAllProjects(): Flow<List<Project>>

    /** Projects with clip count and cover image, most recently edited first. */
    fun getProjectSummaries(): Flow<List<ProjectSummary>>

    /** Full timeline (tracks + clips) of a project, or null if it doesn't exist. */
    suspend fun getProjectTimeline(id: String): ProjectTimeline?

    suspend fun renameProject(id: String, name: String)

    /**
     * Replaces all clips on [trackId] with [clips] (already laid out) and updates the project's
     * length and last-edited time, in one transaction.
     */
    suspend fun saveTrackClips(projectId: String, trackId: String, clips: List<Clip>)

    /** Background music and the clips' own volume. */
    suspend fun getProjectAudio(projectId: String): ProjectAudio

    /** Sets (or with null, removes) the project's background music. */
    suspend fun saveMusic(projectId: String, music: MusicSettings?)

    suspend fun setOriginalAudioVolume(projectId: String, volume: Float)

    /** The project's overlay (picture-in-picture) track, created on first use. */
    suspend fun ensureOverlayTrack(projectId: String): Track

    /** The project's voiceover track, created on first use (see [com.cinemind.core.domain.audio.VoiceoverSettings]). */
    suspend fun ensureVoiceoverTrack(projectId: String): Track

    /** Adds a new sound-effects track (projects can have several). */
    suspend fun addSfxTrack(projectId: String): Track

    suspend fun updateTrack(track: Track)

    /** Copies a project with all tracks and clips; returns the new id, or null if [id] doesn't exist. */
    suspend fun duplicateProject(id: String, newName: String): String?
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
    suspend fun getAssetByUri(uri: String): MediaAsset?
    fun getAllAssets(): Flow<List<MediaAsset>>
    suspend fun updateAsset(asset: MediaAsset)
    suspend fun deleteAsset(id: String)
    fun searchAssets(query: String): Flow<List<MediaAsset>>
}
interface EditBriefRepository {
    /** The saved brief, or a default (empty) brief if the user hasn't written one yet. */
    fun observeBrief(projectId: String): Flow<EditBrief>
    suspend fun saveBrief(brief: EditBrief)
}

/** The project's caption layer and its look. */
interface CaptionRepository {
    fun observeCaptions(projectId: String): Flow<List<com.cinemind.core.domain.captions.Caption>>
    suspend fun getCaptions(projectId: String): List<com.cinemind.core.domain.captions.Caption>

    /** Replaces every caption of the project (one transaction, so autosave never half-writes). */
    suspend fun saveCaptions(projectId: String, captions: List<com.cinemind.core.domain.captions.Caption>)

    /** The saved style, or the default preset. */
    fun observeStyle(projectId: String): Flow<com.cinemind.core.domain.captions.CaptionStyle>
    suspend fun getStyle(projectId: String): com.cinemind.core.domain.captions.CaptionStyle
    suspend fun saveStyle(style: com.cinemind.core.domain.captions.CaptionStyle)
}

/** Free text / title / sticker layers of a project. */
interface TextLayerRepository {
    fun observe(projectId: String): Flow<List<com.cinemind.core.domain.text.TextLayer>>
    suspend fun get(projectId: String): List<com.cinemind.core.domain.text.TextLayer>
    /** Replaces every text layer of the project (one transaction). */
    suspend fun save(projectId: String, layers: List<com.cinemind.core.domain.text.TextLayer>)
}
