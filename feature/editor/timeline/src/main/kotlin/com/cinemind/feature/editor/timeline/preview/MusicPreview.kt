package com.cinemind.feature.editor.timeline.preview

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import com.cinemind.core.domain.audio.MusicLayout
import com.cinemind.core.domain.audio.MusicSegment
import kotlin.math.abs

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
 * Plays background music in a second player that follows the video preview: same segments
 * (offset, looping) and fade envelope as export, re-aligned whenever it drifts. Main thread only.
 */
class MusicPreview(context: Context) {

    private val player: ExoPlayer = ExoPlayer.Builder(context.applicationContext).build().apply { volume = 0f }
    private var playback: MusicPlayback? = null
    private var segments: List<MusicSegment> = emptyList()
    private var spans: List<ClipSpan> = emptyList()

    fun set(next: MusicPlayback?) {
        if (next == playback) return
        playback = next
        player.pause()
        if (next == null) {
            segments = emptyList()
            spans = emptyList()
            player.clearMediaItems()
            return
        }
        segments = MusicLayout.segments(next.durationUs, next.startOffsetUs, next.totalUs)
        spans = segments.map { ClipSpan(it.timelineStartUs, it.timelineStartUs + it.lengthUs) }
        player.setMediaItems(
            segments.map { seg ->
                MediaItem.Builder()
                    .setUri(next.uri)
                    .setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(seg.sourceInUs / 1_000)
                            .setEndPositionMs(seg.sourceOutUs / 1_000)
                            .build(),
                    )
                    .build()
            },
        )
        player.prepare()
    }

    /** Call frequently with the video's timeline position and whether it is playing. */
    fun sync(timelineUs: Long, playing: Boolean) {
        val p = playback ?: return
        if (spans.isEmpty()) return
        if (!playing || timelineUs >= p.totalUs) {
            if (player.playWhenReady) player.pause()
            return
        }
        val loc = TimelineMath.locate(spans, timelineUs) ?: return
        val drift = abs(player.currentPosition * 1_000 - loc.offsetUs)
        if (player.currentMediaItemIndex != loc.index || drift > MAX_DRIFT_US) {
            player.seekTo(loc.index, loc.offsetUs / 1_000)
        }
        player.volume = MusicLayout.gainAt(timelineUs, p.totalUs, p.volume, p.fadeInUs, p.fadeOutUs)
        if (!player.playWhenReady) player.play()
    }

    fun pause() = player.pause()

    fun release() = player.release()

    private companion object {
        const val MAX_DRIFT_US = 200_000L
    }
}
