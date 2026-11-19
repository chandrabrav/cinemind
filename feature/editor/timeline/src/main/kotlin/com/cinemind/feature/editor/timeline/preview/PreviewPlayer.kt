package com.cinemind.feature.editor.timeline.preview

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.CompositionPlayer
import com.cinemind.core.domain.model.Clip
import com.cinemind.core.domain.model.MediaAsset
import com.cinemind.feature.export.engine.RenderMusic
import com.cinemind.feature.export.engine.RenderOverlay
import com.cinemind.feature.export.engine.RenderVoiceClip
import com.cinemind.feature.export.engine.RenderVoiceover
import com.cinemind.feature.export.engine.RenderSource
import com.cinemind.feature.export.engine.TimelineRenderer
import com.cinemind.feature.export.plan.ExportPlanner
import com.cinemind.feature.export.plan.ExportResolution
import com.cinemind.feature.export.plan.OutputSize
import java.io.File

/** One timeline clip with its source media (null when the media record is missing). */
data class PreviewClip(val clip: Clip, val asset: MediaAsset?)

/** Snapshot of playback for the UI. Positions are whole-timeline microseconds. */
data class PreviewState(
    val isPlaying: Boolean = false,
    val positionUs: Long = 0,
    val durationUs: Long = 0,
    val currentIndex: Int = 0,
    val error: String? = null,
)

/** Everything needed to play the music under a timeline of [totalUs]. */
data class MusicPlayback(
    val uri: String,
    val durationUs: Long,
    val startOffsetUs: Long,
    val volume: Float,
    val fadeInUs: Long,
    val fadeOutUs: Long,
    val totalUs: Long,
)

/**
 * The editor preview plays **the same Media3 composition the export renders** (built by
 * [TimelineRenderer.buildComposition]) with a [CompositionPlayer], at 720p:
 *  - transitions, photos, frame-rate conversion, music (looped/cut, fades) and the original-sound
 *    volume come from the export code, so preview and export can't drift apart;
 *  - one continuous composition timeline instead of a playlist of clipped items, so there is no
 *    per-clip player hand-over at cuts (the old preview's stutter);
 *  - captions are not baked in here: the editor draws them on top with the same painter, so caption
 *    edits show instantly without rebuilding the composition.
 * Rebuilds are coalesced (one per main-thread turn). Must be used from the main thread.
 */
@OptIn(UnstableApi::class)
class PreviewPlayer(context: Context) {

    private val appContext = context.applicationContext
    /**
     * Multi-input video graph so overlay (picture-in-picture) tracks play: the default graph takes a
     * single video input and only logs a warning for more (overlays then didn't play on the owner's
     * phone). Same setup as Media3's composition demo.
     */
    private fun newPlayer(): CompositionPlayer = CompositionPlayer.Builder(appContext)
        .setVideoGraphFactory(androidx.media3.effect.MultipleInputVideoGraph.Factory())
        .build()
        .also { p ->
            p.addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    lastError = "Can't play the preview: ${error.errorCodeName}"
                }
                override fun onPlaybackStateChanged(state: Int) {
                    if (p === current && state != Player.STATE_BUFFERING) _building.value = false
                }
            })
        }

    /**
     * A fresh player for every rebuild: the multi-input video graph doesn't pick up a composition
     * whose layers changed (a voiceover, overlay, sound or dissolve added), which froze the preview
     * until the project was reopened (owner's #135 test). The view switches to the new player via
     * [playerFlow]; the old one is released a moment later, after the view has let go of it.
     */
    private var current: CompositionPlayer = newPlayer()
    private val _playerFlow = kotlinx.coroutines.flow.MutableStateFlow<Player>(current)
    val playerFlow: kotlinx.coroutines.flow.StateFlow<Player> = _playerFlow
    val player: Player get() = current

    private val _building = kotlinx.coroutines.flow.MutableStateFlow(false)
    /** True while a new preview is being prepared (for a small loader). */
    val building: kotlinx.coroutines.flow.StateFlow<Boolean> = _building
    private val renderer = TimelineRenderer(appContext)
    private val main = Handler(Looper.getMainLooper())

    /** Background music; part of the composition (no second player to keep in step). */
    val music = MusicControl()

    inner class MusicControl {
        fun set(next: MusicPlayback?) {
            if (next == musicPlayback) return
            musicPlayback = next
            scheduleRebuild()
        }

        /** Kept for callers; the composition plays music in sync by construction. */
        @Suppress("UNUSED_PARAMETER")
        fun sync(timelineUs: Long, playing: Boolean) = Unit
    }

    private var clips: List<PreviewClip> = emptyList()
    private var musicPlayback: MusicPlayback? = null
    private var originalVolume = 1f
    private var size = OutputSize(720, 1280)
    private var fps = 30
    private var spans: List<ClipSpan> = emptyList()
    private var lastError: String? = null
    private var rebuildPending = false
    private var builtKey: Any? = null


    /** Output shape of the project (preview renders it at 720p on the short side). */
    fun setFormat(projectWidth: Int, projectHeight: Int, frameRate: Int) {
        if (projectWidth <= 0 || projectHeight <= 0) return
        val next = ExportPlanner.outputSize(projectWidth, projectHeight, ExportResolution.P720)
        if (next == size && frameRate == fps) return
        size = next
        fps = frameRate.coerceIn(1, 120)
        scheduleRebuild()
    }

    private var voiceEnhance = com.cinemind.core.domain.audio.VoiceEnhance()

    /** On-device noise removal, heard in the preview exactly as it will be exported. */
    fun setVoiceEnhance(value: com.cinemind.core.domain.audio.VoiceEnhance) {
        if (value == voiceEnhance) return
        voiceEnhance = value
        scheduleRebuild()
    }

    fun setOriginalVolume(volume: Float) {
        val v = volume.coerceIn(0f, 1f)
        if (v == originalVolume) return
        originalVolume = v
        scheduleRebuild()
    }

    /** Loads [clips]; keeps the current timeline position. No-op if unchanged. */
    fun setClips(next: List<PreviewClip>) {
        if (next == clips) return
        clips = next
        spans = next.map { ClipSpan(it.clip.timelineInUs, it.clip.timelineOutUs) }
        scheduleRebuild()
    }

    private var grades: Map<String, com.cinemind.core.domain.color.CubeLut> = emptyMap()
    private var gradesEnabled = true

    /** Baked colour grade per clip id (same LUTs the export uses). */
    fun setGrades(next: Map<String, com.cinemind.core.domain.color.CubeLut>) {
        if (next == grades) return
        grades = next
        scheduleRebuild()
    }

    /** Off while the user holds "compare" to see the original colour. */
    fun setGradesEnabled(enabled: Boolean) {
        if (enabled == gradesEnabled) return
        gradesEnabled = enabled
        scheduleRebuild()
    }

    private var voiceClips: List<PreviewClip> = emptyList()
    private var voiceSettings = com.cinemind.core.domain.audio.VoiceoverSettings()

    /** Recorded narration; mixed (and ducking the music) exactly like the export. */
    fun setVoiceover(clips: List<PreviewClip>, settings: com.cinemind.core.domain.audio.VoiceoverSettings) {
        if (clips == voiceClips && settings == voiceSettings) return
        voiceClips = clips
        voiceSettings = settings
        scheduleRebuild()
    }

    /** Titles and captions placed under the picture-in-picture: drawn into the composition (the
     *  editor draws everything above it itself, so those edits show without a rebuild). */
    private var underTexts: List<com.cinemind.core.domain.text.TextLayer> = emptyList()
    private var underCaptions: com.cinemind.feature.export.engine.RenderCaptions? = null
    private var layerOrder = com.cinemind.core.domain.text.LayerOrder()

    fun setUnderLayers(texts: List<com.cinemind.core.domain.text.TextLayer>, captions: com.cinemind.feature.export.engine.RenderCaptions?, order: com.cinemind.core.domain.text.LayerOrder) {
        if (texts == underTexts && captions == underCaptions && order == layerOrder) return
        underTexts = texts
        underCaptions = captions
        layerOrder = order
        scheduleRebuild()
    }

    private var sfxTracks: List<List<PreviewClip>> = emptyList()

    /** Sound-effect tracks (each clip's volume is its opacity field), mixed like the export. */
    fun setSfx(tracks: List<List<PreviewClip>>) {
        if (tracks == sfxTracks) return
        sfxTracks = tracks
        scheduleRebuild()
    }

    private var listenVolume = 1f
    private var silenced = false

    /** Silences the preview (while recording a voiceover, so the mic doesn't hear the speaker). */
    fun setMuted(muted: Boolean) {
        silenced = muted
        player.volume = if (silenced) 0f else listenVolume
    }

    /** How loud the preview plays (listening only; never changes the project). */
    fun setListeningVolume(volume: Float) {
        listenVolume = volume.coerceIn(0f, 1f)
        player.volume = if (silenced) 0f else listenVolume
    }

    fun play() {
        if (spans.isEmpty()) return
        player.play()
    }

    private var overlays: List<PreviewClip> = emptyList()

    /** Clips on the overlay (picture-in-picture) track; composited exactly like the export. */
    fun setOverlays(next: List<PreviewClip>) {
        if (next == overlays) return
        overlays = next
        scheduleRebuild()
    }

    private fun scheduleRebuild() {
        if (rebuildPending) return
        rebuildPending = true
        main.post {
            rebuildPending = false
            rebuild()
        }
    }

    /** Rebuilds the preview, e.g. after a subject cutout was made for one of the clips. */
    fun refresh() {
        matteVersion++
        rebuild()
    }
    private var matteVersion = 0

    private fun rebuild() {
        val key = listOf(clips.map { it.clip to it.asset?.uri }, overlays.map { it.clip to it.asset?.uri }, voiceClips.map { it.clip to it.asset?.uri }, voiceSettings, sfxTracks.map { t -> t.map { it.clip to it.asset?.uri } }, underTexts, underCaptions, layerOrder, grades.keys.associateWith { grades[it]?.hashCode() }, gradesEnabled, musicPlayback, originalVolume, size, fps, matteVersion, voiceEnhance)
        if (key == builtKey) return
        builtKey = key
        lastError = null
        if (clips.isEmpty()) {
            player.stop()
            return
        }
        val keepMs = (currentPositionUs() / 1_000).coerceAtLeast(0)
        val pairs = clips.map { it.clip to sourceFor(it) }
        val music = musicPlayback?.let { RenderMusic(it.uri, it.durationUs, it.startOffsetUs, it.volume, it.fadeInUs, it.fadeOutUs) }
        val overlayTrack = overlays.mapNotNull { o ->
            val a = o.asset ?: return@mapNotNull null
            RenderOverlay(o.clip, RenderSource(a.uri, a.isVideo, false, a.frameRate, a.durationUs, a.width, a.height, com.cinemind.feature.export.engine.MatteStore.existing(appContext, a.id)?.path), a.width, a.height)
        }
        val composition = runCatching {
            val voice = voiceClips.mapNotNull { v -> v.asset?.let { a -> RenderVoiceClip(v.clip, a.uri, a.durationUs) } }
            val sfx = sfxTracks.map { t -> RenderVoiceover(t.mapNotNull { v -> v.asset?.let { a -> RenderVoiceClip(v.clip, a.uri, a.durationUs) } }, com.cinemind.core.domain.audio.VoiceoverSettings()) }
            renderer.buildComposition(pairs, size, fps, music, originalVolume, overlays = listOf(overlayTrack), voiceover = RenderVoiceover(voice, voiceSettings), grades = if (gradesEnabled) grades else emptyMap(), sfx = sfx, captions = underCaptions, texts = underTexts, layerOrder = layerOrder, voiceEnhance = voiceEnhance, textsOnTop = false)
        }
            .getOrElse {
                lastError = "Can't build the preview: ${it.message}"
                return
            }
        val old = current
        val wasPlaying = old.playWhenReady
        val volume = old.volume
        val fresh = newPlayer()
        current = fresh
        _building.value = true
        fresh.volume = volume
        fresh.setComposition(composition, keepMs.coerceAtMost(TimelineMath.totalDurationUs(spans) / 1_000))
        fresh.prepare()
        fresh.playWhenReady = wasPlaying
        _playerFlow.value = fresh
        // Let the video view switch to the new player before the old one goes away.
        old.pause()
        main.postDelayed({ runCatching { old.release() } }, 400)
    }

    private fun sourceFor(item: PreviewClip): RenderSource {
        val a = item.asset
        return if (a != null) {
            RenderSource(a.uri, a.isVideo, a.hasAudio, a.frameRate, a.durationUs, a.width, a.height, com.cinemind.feature.export.engine.MatteStore.existing(appContext, a.id)?.path)
        } else {
            // Missing media plays as black for its length, so the rest of the timeline stays in place.
            RenderSource(Uri.fromFile(blackFrame()).toString(), isVideo = false, hasAudio = false, frameRate = 0f, durationUs = 0)
        }
    }

    private fun blackFrame(): File {
        val f = File(appContext.cacheDir, "preview_missing_media.png")
        if (!f.exists()) {
            val bmp = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLACK) }
            f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        return f
    }

    fun togglePlayPause() {
        if (spans.isEmpty()) return
        if (player.playbackState == Player.STATE_ENDED) {
            player.seekTo(0)
            player.play()
            return
        }
        if (player.playbackState == Player.STATE_IDLE) {
            lastError = null
            builtKey = null
            rebuild()
        }
        if (player.playWhenReady) player.pause() else player.play()
    }

    fun pause() {
        player.pause()
    }

    /** Seeks to a whole-timeline position (the composition's own time). */
    @Suppress("UNUSED_PARAMETER")
    fun seekToUs(positionUs: Long, fast: Boolean = false) {
        if (spans.isEmpty()) return
        player.seekTo((positionUs.coerceIn(0, TimelineMath.totalDurationUs(spans)) / 1_000))
    }

    fun seekToClip(index: Int) {
        if (index in spans.indices) seekToUs(spans[index].startUs)
    }

    fun currentPositionUs(): Long = if (spans.isEmpty()) 0 else player.currentPosition.coerceAtLeast(0) * 1_000

    fun snapshot(): PreviewState {
        val pos = currentPositionUs()
        return PreviewState(
            isPlaying = player.playWhenReady && player.playbackState != Player.STATE_ENDED &&
                player.playbackState != Player.STATE_IDLE,
            positionUs = pos,
            durationUs = TimelineMath.totalDurationUs(spans),
            currentIndex = TimelineMath.locate(spans, pos)?.index ?: 0,
            error = lastError,
        )
    }

    /**
     * Frees the preview's decoders and GPU pipeline while the editor isn't on screen (e.g. during
     * export, which needs its own decoders/encoder; phones have a limited number of hardware codecs).
     */
    fun releaseDecoders() {
        main.removeCallbacksAndMessages(null)
        rebuildPending = false
        if (player.playbackState != Player.STATE_IDLE) player.stop()
        builtKey = null
    }

    /** Rebuilds the preview after [releaseDecoders] (same timeline, same position). */
    fun restore() {
        scheduleRebuild()
    }

    fun release() {
        main.removeCallbacksAndMessages(null)
        runCatching { current.release() }
    }
}
