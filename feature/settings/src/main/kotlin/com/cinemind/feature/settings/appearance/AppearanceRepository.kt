package com.cinemind.feature.settings.appearance

import android.app.UiModeManager
import android.content.Context
import android.os.Build
import com.cinemind.core.ui.theme.ThemeMode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The user's Light / Dark / System choice. Stored in SharedPreferences so it can be read
 * synchronously at startup (no wrong-theme flash) and observed as a Flow for live updates.
 */
@Singleton
class AppearanceRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val prefs = context.getSharedPreferences("appearance", Context.MODE_PRIVATE)

    /** Current value, read synchronously (cheap: SharedPreferences are cached in memory). */
    fun current(): ThemeMode = ThemeMode.fromName(prefs.getString(KEY, null))

    val themeMode: Flow<ThemeMode> = callbackFlow {
        trySend(current())
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY) trySend(current())
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()

    fun set(mode: ThemeMode) {
        prefs.edit().putString(KEY, mode.name).apply()
        applyToSystem(mode)
    }

    /**
     * Also tells Android (12+) which night mode this app uses, so the window background, splash
     * and system dialogs match the chosen theme, and the choice survives restarts at system level.
     */
    fun applyToSystem(mode: ThemeMode = current()) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(UiModeManager::class.java)?.setApplicationNightMode(
                when (mode) {
                    ThemeMode.SYSTEM -> UiModeManager.MODE_NIGHT_AUTO
                    ThemeMode.LIGHT -> UiModeManager.MODE_NIGHT_NO
                    ThemeMode.DARK -> UiModeManager.MODE_NIGHT_YES
                },
            )
        }
    }

    private companion object {
        const val KEY = "theme_mode"
    }
}
