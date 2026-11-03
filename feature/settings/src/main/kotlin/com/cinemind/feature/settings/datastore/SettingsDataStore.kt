package com.cinemind.feature.settings.datastore

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.editCommit
import androidx.datastore.preferences.core.preferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesKeys
import androidx.datastore.preferences.core.SharedPreferencesMigration
import androidx.datastore.preferences.core.createDataStore
import androidx.datastore.preferences.core.PreferenceDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted

object SettingsKeys {
    val AI_MODEL_ENABLED = booleanPreferencesKey("ai_model_enabled")
    val CLOUD_AI_ENABLED = booleanPreferencesKey("cloud_ai_enabled")
    val CLOUD_AI_PROVIDER = stringPreferencesKey("cloud_ai_provider")
    val CLOUD_AI_API_KEY = stringPreferencesKey("cloud_ai_api_key")
    val CLOUD_AI_BASE_URL = stringPreferencesKey("cloud_ai_base_url")
    val DEFAULT_EXPORT_PRESET = stringPreferencesKey("default_export_preset")
    val DEFAULT_ASPECT_RATIO = stringPreferencesKey("default_aspect_ratio")
    val PERFORMANCE_MODE = stringPreferencesKey("performance_mode")
    val MAX_CACHE_GB = intPreferencesKey("max_cache_gb")
    val THEME_DARK = booleanPreferencesKey("theme_dark")
    val HAPTIC_FEEDBACK = booleanPreferencesKey("haptic_feedback")
}

class SettingsDataStore(private val context: Context) {
    private val dataStore: PreferenceDataStore by lazy {
        context.createDataStore(
            name = "cinemind_settings",
            productionMigrations = listOf(
                SharedPreferencesMigration(context, "cinemind_settings_preferences")
            )
        )
    }

    val aiModelEnabled: Flow<Boolean> by lazy {
        dataStore.data
            .map { preferences ->
                preferences[SettingsKeys.AI_MODEL_ENABLED] ?: false
            }
            .distinctUntilChanged()
    }

    val cloudAIEnabled: Flow<Boolean> by lazy {
        dataStore.data
            .map { preferences ->
                preferences[SettingsKeys.CLOUD_AI_ENABLED] ?: false
            }
            .distinctUntilChanged()
    }

    val cloudAIProvider: Flow<String?> by lazy {
        dataStore.data
            .map { preferences ->
                preferences[SettingsKeys.CLOUD_AI_PROVIDER]
            }
            .distinctUntilChanged()
    }

    suspend fun setBoolean(key: PreferencesKeys<Boolean>, value: Boolean) {
        dataStore.edit { preferences ->
            preferences[key] = value
        }.editCommit()
    }

    suspend fun setString(key: PreferencesKeys<String>, value: String?) {
        dataStore.edit { preferences ->
            preferences[key] = value
        }.editCommit()
    }

    suspend fun setInt(key: PreferencesKeys<Int>, value: Int) {
        dataStore.edit { preferences ->
            preferences[key] = value
        }.editCommit()
    }

    suspend fun <T> getValue(key: PreferencesKeys<T>, default: T): T {
        return dataStore.data.first().let { preferences ->
            preferences[key] ?: default
        }
    }
}

class SettingsViewModel(private val settingsDataStore: SettingsDataStore) {
    val aiModelEnabled: Flow<Boolean> = settingsDataStore.aiModelEnabled
        .stateIn(
            scope = CoroutineScope(Dispatchers.Main),
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = false
        )

    val cloudAIEnabled: Flow<Boolean> = settingsDataStore.cloudAIEnabled
        .stateIn(
            scope = CoroutineScope(Dispatchers.Main),
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = false
        )

    fun toggleAIModel(enabled: Boolean) {
        CoroutineScope(Dispatchers.IO).launch {
            settingsDataStore.setBoolean(SettingsKeys.AI_MODEL_ENABLED, enabled)
        }
    }

    fun toggleCloudAI(enabled: Boolean) {
        CoroutineScope(Dispatchers.IO).launch {
            settingsDataStore.setBoolean(SettingsKeys.CLOUD_AI_ENABLED, enabled)
        }
    }
}