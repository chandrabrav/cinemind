package com.cinemind.core.domain.audio

import com.cinemind.core.domain.model.Clip

/**
 * Voiceover track settings. Stored in the VOICEOVER track's own fields (no extra table):
 * `Track.volume` = narration volume, `Track.opacity` = how much the music is lowered while the
 * voice speaks (0 = not at all, 1 = silent), `Track.isEnabled` = not muted. Each recording is a
 * clip; its `transitionInMs`/`transitionOutMs` are its fade-in/out.
 */
data class VoiceoverSettings(val volume: Float = 1f, val duckMusic: Float = 0.6f, val muted: Boolean = false)

/** Gain maths for narration and music ducking, shared by preview and export. Pure; unit-tested. */
object VoiceMix {

    /** Ducking eases in/out over this long so the music doesn't jump. */
    const val DUCK_RAMP_US = 250_000L

    /** Narration gain at [localUs] inside [clip] (fades from the clip's transition fields). */
    fun voiceGain(clip: Clip, localUs: Long, settings: VoiceoverSettings): Float {
        if (settings.muted) return 0f
        val len = clip.timelineOutUs - clip.timelineInUs
        var g = settings.volume.coerceIn(0f, 2f)
        val fadeIn = clip.transitionInMs * 1_000
        val fadeOut = clip.transitionOutMs * 1_000
        if (fadeIn > 0 && localUs < fadeIn) g *= (localUs.toFloat() / fadeIn).coerceIn(0f, 1f)
        if (fadeOut > 0 && localUs > len - fadeOut) g *= ((len - localUs).toFloat() / fadeOut).coerceIn(0f, 1f)
        return g
    }

    /**
     * Multiplier for the music at timeline time [tUs]: lowered by [VoiceoverSettings.duckMusic]
     * while any (unmuted) voiceover clip plays, with a short ramp at its edges.
     */
    fun musicDuck(tUs: Long, voice: List<Clip>, settings: VoiceoverSettings): Float {
        if (settings.muted || settings.duckMusic <= 0f || voice.isEmpty()) return 1f
        var presence = 0f
        for (c in voice) {
            val p = when {
                tUs < c.timelineInUs - DUCK_RAMP_US || tUs > c.timelineOutUs + DUCK_RAMP_US -> 0f
                tUs < c.timelineInUs -> 1f - (c.timelineInUs - tUs).toFloat() / DUCK_RAMP_US
                tUs > c.timelineOutUs -> 1f - (tUs - c.timelineOutUs).toFloat() / DUCK_RAMP_US
                else -> 1f
            }
            if (p > presence) presence = p
        }
        return 1f - settings.duckMusic.coerceIn(0f, 1f) * presence.coerceIn(0f, 1f)
    }
}
