package com.cinemind.core.domain.audio

import com.cinemind.core.domain.model.Clip
import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceMixTest {

    private fun clip(inUs: Long, outUs: Long, fadeInMs: Long = 0, fadeOutMs: Long = 0) =
        Clip(id = "v", trackId = "vo", assetId = "a", timelineInUs = inUs, timelineOutUs = outUs, sourceInUs = 0, sourceOutUs = outUs - inUs,
            transitionInMs = fadeInMs, transitionOutMs = fadeOutMs)

    @Test fun `voice volume, fades and mute`() {
        val c = clip(0, 4_000_000, fadeInMs = 1_000, fadeOutMs = 1_000)
        val s = VoiceoverSettings(volume = 0.8f)
        assertEquals(0f, VoiceMix.voiceGain(c, 0, s), 1e-6f)
        assertEquals(0.4f, VoiceMix.voiceGain(c, 500_000, s), 1e-6f)
        assertEquals(0.8f, VoiceMix.voiceGain(c, 2_000_000, s), 1e-6f)
        assertEquals(0.4f, VoiceMix.voiceGain(c, 3_500_000, s), 1e-6f)
        assertEquals(0f, VoiceMix.voiceGain(c, 2_000_000, s.copy(muted = true)), 0f)
    }

    @Test fun `music ducks under the voice with a ramp`() {
        val voice = listOf(clip(2_000_000, 4_000_000))
        val s = VoiceoverSettings(duckMusic = 0.6f)
        assertEquals(1f, VoiceMix.musicDuck(1_000_000, voice, s), 1e-6f)
        assertEquals(0.4f, VoiceMix.musicDuck(3_000_000, voice, s), 1e-6f)
        // Half way through the ramp before the voice: half the reduction.
        assertEquals(0.7f, VoiceMix.musicDuck(2_000_000 - VoiceMix.DUCK_RAMP_US / 2, voice, s), 1e-4f)
        assertEquals(1f, VoiceMix.musicDuck(4_000_000 + VoiceMix.DUCK_RAMP_US + 1, voice, s), 1e-6f)
    }

    @Test fun `no ducking when off, muted or without narration`() {
        val voice = listOf(clip(0, 1_000_000))
        assertEquals(1f, VoiceMix.musicDuck(500_000, voice, VoiceoverSettings(duckMusic = 0f)), 0f)
        assertEquals(1f, VoiceMix.musicDuck(500_000, voice, VoiceoverSettings(muted = true)), 0f)
        assertEquals(1f, VoiceMix.musicDuck(500_000, emptyList(), VoiceoverSettings()), 0f)
    }
}
