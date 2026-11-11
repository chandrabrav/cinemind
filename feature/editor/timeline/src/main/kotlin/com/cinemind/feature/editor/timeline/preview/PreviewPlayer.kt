package com.cinemind.feature.editor.timeline.preview

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.SilenceMediaSource
import com.cinemind.core.domain.model.Clip
import com.cinemind.core.domain.model.MediaAsset

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

/**
 * Plays a single video track as an ExoPlayer playlist — one playlist item per clip:
 *  - video clips → the source file clipped to the clip's source in/out points;
 *  - stills and missing media → a silent span of the clip's duration (the UI draws the image on top).
 *
 * Preview only: nothing is rendered or written to disk here. Must be used from the main thread.
 */
@OptIn(UnstableApi::class)
class PreviewPlayer(context: Context) {

    val player: ExoPlayer = ExoPlayer.Builder(context.applicationContext).build()

    private val sourceFactory = DefaultMediaSourceFactory(context.applicationContext)
    private var spans: List<ClipSpan> = emptyList()
    private var loadedKey: List<Any?> = emptyList()
    private var lastError: String? = null

    init {
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                lastError = "Can't play clip ${player.currentMediaItemIndex + 1}: ${error.errorCodeName}"
            }
        })
    }

    /** Loads [clips] as the playlist, keeping the current timeline position. No-op if unchanged. */
    fun setClips(clips: List<PreviewClip>) {
        val key = clips.map { listOf(it.clip, it.asset?.uri) }
        if (key == loadedKey) return
        val keepPositionUs = currentPositionUs()

        loadedKey = key
        spans = clips.map { ClipSpan(it.clip.timelineInUs, it.clip.timelineOutUs) }
        lastError = null

        if (clips.isEmpty()) {
            player.clearMediaItems()
            return
        }
        player.setMediaSources(clips.map(::sourceFor))
        player.prepare()
        seekToUs(keepPositionUs)
    }

    private fun sourceFor(item: PreviewClip): MediaSource {
        val clip = item.clip
        val asset = item.asset
        if (asset == null || !asset.isVideo) {
            return SilenceMediaSource.Factory()
                .setDurationUs((clip.timelineOutUs - clip.timelineInUs).coerceAtLeast(1))
                .setTag(clip.id)
                .createMediaSource()
        }
        // Metadata durations are rounded; if the clip runs to the end of the file, let the
        // container decide where that is instead of asking for a position past it.
        val endMs = if (clip.sourceOutUs >= asset.durationUs) C.TIME_END_OF_SOURCE else clip.sourceOutUs / 1_000
        val mediaItem = MediaItem.Builder()
            .setUri(asset.uri)
            .setMediaId(clip.id)
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(clip.sourceInUs / 1_000)
                    .setEndPositionMs(endMs)
                    .build(),
            )
            .build()
        return sourceFactory.createMediaSource(mediaItem)
    }

    fun togglePlayPause() {
        if (spans.isEmpty()) return
        if (player.playbackState == Player.STATE_ENDED) {
            player.seekTo(0, 0)
            player.play()
            return
        }
        if (player.playbackState == Player.STATE_IDLE) {
            lastError = null
            player.prepare()
        }
        if (player.playWhenReady) player.pause() else player.play()
    }

    fun pause() = player.pause()

    /** Seeks to a whole-timeline position. [fast] trades accuracy for speed while scrubbing. */
    fun seekToUs(positionUs: Long, fast: Boolean = false) {
        val loc = TimelineMath.locate(spans, positionUs) ?: return
        player.setSeekParameters(if (fast) SeekParameters.CLOSEST_SYNC else SeekParameters.EXACT)
        player.seekTo(loc.index, loc.offsetUs / 1_000)
    }

    fun seekToClip(index: Int) {
        if (index in spans.indices) seekToUs(spans[index].startUs)
    }

    fun currentPositionUs(): Long {
        if (spans.isEmpty()) return 0
        val index = player.currentMediaItemIndex.coerceIn(0, spans.lastIndex)
        return TimelineMath.absolutePositionUs(spans, index, player.currentPosition * 1_000)
    }

    fun snapshot(): PreviewState = PreviewState(
        isPlaying = player.playWhenReady && player.playbackState != Player.STATE_ENDED &&
            player.playbackState != Player.STATE_IDLE,
        positionUs = currentPositionUs(),
        durationUs = TimelineMath.totalDurationUs(spans),
        currentIndex = if (spans.isEmpty()) 0 else player.currentMediaItemIndex.coerceIn(0, spans.lastIndex),
        error = lastError,
    )

    fun release() = player.release()
}
