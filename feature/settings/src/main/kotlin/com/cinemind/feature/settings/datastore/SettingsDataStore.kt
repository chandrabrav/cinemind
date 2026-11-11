package com.cinemind.feature.settings.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "cinemind_settings",
)

/**
 * Non-secret user preferences.
 *
 * SECURITY: cloud-AI API keys must NOT be stored here (DataStore is plain text on disk).
 * They belong in Android Keystore-backed storage, which arrives with the Stage 6 provider work.
 */
object SettingsKeys {
    val AI_MODEL_ENABLED = booleanPreferencesKey("ai_model_enabled")
    val CLOUD_AI_ENABLED = booleanPreferencesKey("cloud_ai_enabled")
    val CLOUD_AI_PROVIDER = stringPreferencesKey("cloud_ai_provider")
    val CLOUD_AI_BASE_URL = stringPreferencesKey("cloud_ai_base_url")
    val DEFAULT_EXPORT_PRESET = stringPreferencesKey("default_export_preset")
    val DEFAULT_ASPECT_RATIO = stringPreferencesKey("default_aspect_ratio")
    val PERFORMANCE_MODE = stringPreferencesKey("performance_mode")
    val MAX_CACHE_GB = intPreferencesKey("max_cache_gb")
    val THEME_DARK = booleanPreferencesKey("theme_dark")
    val HAPTIC_FEEDBACK = booleanPreferencesKey("haptic_feedback")
}

class SettingsDataStore(context: Context) {
    private val dataStore = context.applicationContext.settingsDataStore

    val aiModelEnabled: Flow<Boolean> = observe(SettingsKeys.AI_MODEL_ENABLED, false)
    val cloudAiEnabled: Flow<Boolean> = observe(SettingsKeys.CLOUD_AI_ENABLED, false)
    val cloudAiProvider: Flow<String?> = dataStore.data
        .map { it[SettingsKeys.CLOUD_AI_PROVIDER] }
        .distinctUntilChanged()

    fun <T> observe(key: Preferences.Key<T>, default: T): Flow<T> =
        dataStore.data.map { it[key] ?: default }.distinctUntilChanged()

    suspend fun <T> set(key: Preferences.Key<T>, value: T) {
        dataStore.edit { it[key] = value }
    }

    suspend fun <T> remove(key: Preferences.Key<T>) {
        dataStore.edit { it.remove(key) }
    }

    suspend fun <T> get(key: Preferences.Key<T>, default: T): T =
        dataStore.data.first()[key] ?: default
}
