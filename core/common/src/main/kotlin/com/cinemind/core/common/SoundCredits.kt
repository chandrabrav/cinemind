package com.cinemind.core.common

import android.content.Context

/**
 * Credits for sounds added from Freesound and the bundled CC0 pack, newest first. CC BY sounds
 * must be credited where the video is published; About → Sound credits shows them to copy.
 */
object SoundCredits {
    private const val PREFS = "sound_credits"
    private const val KEY = "list"
    private const val MAX = 200

    fun add(context: Context, credit: String) {
        val list = listOf(credit) + all(context).filter { it != credit }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, list.take(MAX).joinToString("\n")).apply()
    }

    fun all(context: Context): List<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "").orEmpty().split('\n').filter { it.isNotBlank() }
}
