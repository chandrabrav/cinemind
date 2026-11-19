package com.cinemind.core.domain.aiedit

import com.cinemind.core.domain.editing.ClipTransition
import java.util.Locale

/**
 * House rules that keep an AI edit looking current instead of like a 2017 slideshow:
 * - between shots: hard cuts, with at most a quick zoom punch or a short cross-dissolve where the
 *   scene changes; no slides, spins or whips (they slide the picture off and show black), and no
 *   dip to black in the middle of a video;
 * - no emoji stickers;
 * - only as many transitions as the edit can carry (about one every three shots).
 * Whatever the user explicitly asks for ("slide transitions", "add emojis") is kept.
 */
object ModernEdit {

    const val PUNCH_SECONDS = 0.3
    const val DISSOLVE_SECONDS = 0.5

    fun tidy(plan: EditPlan, prompt: String): EditPlan {
        val p = prompt.lowercase(Locale.ROOT)
        fun asked(vararg words: String) = words.any { it in p }
        val keepSlides = asked("slide", "swipe")
        val keepWhips = asked("whip")
        val keepSpin = asked("spin")
        val keepBlackDips = asked("fade to black", "dip to black", "fade through black")
        val keepStickers = asked("emoji", "sticker")

        var sinceLast = 0
        val shots = plan.shots.mapIndexed { i, s ->
            val t = ClipTransition.fromName(s.transitionIn.trim().uppercase(Locale.ROOT))
            var type = when (t) {
                ClipTransition.SLIDE_LEFT, ClipTransition.SLIDE_RIGHT, ClipTransition.SLIDE_UP, ClipTransition.SLIDE_DOWN ->
                    if (keepSlides) t else ClipTransition.ZOOM_IN
                ClipTransition.WHIP_LEFT, ClipTransition.WHIP_RIGHT -> if (keepWhips) t else ClipTransition.ZOOM_IN
                ClipTransition.SPIN -> if (keepSpin) t else ClipTransition.ZOOM_IN
                ClipTransition.FADE, ClipTransition.ZOOM_OUT -> if (keepBlackDips) t else ClipTransition.DISSOLVE
                else -> t
            }
            // Transitions are accents: no two in a row (unless asked for by name).
            val named = keepSlides || keepWhips || keepSpin || keepBlackDips
            if (i == 0) type = ClipTransition.CUT
            if (type != ClipTransition.CUT && !named && sinceLast < 2) type = ClipTransition.CUT
            sinceLast = if (type == ClipTransition.CUT) sinceLast + 1 else 0
            val seconds = when (type) {
                ClipTransition.CUT -> 0.0
                ClipTransition.ZOOM_IN -> PUNCH_SECONDS
                ClipTransition.DISSOLVE -> s.transitionSeconds.coerceIn(0.3, DISSOLVE_SECONDS)
                else -> s.transitionSeconds
            }
            s.copy(transitionIn = type.name, transitionSeconds = seconds)
        }
        val texts = if (keepStickers) plan.texts else plan.texts.filterNot { it.role.trim().equals(TextRole.STICKER.name, ignoreCase = true) }
        return plan.copy(
            shots = shots,
            texts = texts,
            // A short fade in from black at the very start is fine; a long one feels dated.
            openingSeconds = plan.openingSeconds.coerceAtMost(0.4),
        )
    }
}
