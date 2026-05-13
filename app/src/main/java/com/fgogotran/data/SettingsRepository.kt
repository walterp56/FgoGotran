package com.fgogotran.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.fgogotran.util.FgoLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * Persistent application settings backed by Jetpack DataStore Preferences.
 *
 * All settings are exposed as [Flow] for reactive reading and have
 * corresponding suspend setter functions for writing.
 *
 * Settings persist across app restarts and survive APK updates.
 */
@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        val KEY_TRANSLATION_BACKEND = stringPreferencesKey("translation_backend")
        val KEY_API_KEY = stringPreferencesKey("api_key")
        val KEY_PLAYER_NAME = stringPreferencesKey("player_name")
        val KEY_FONT_SIZE = stringPreferencesKey("font_size")
        val KEY_OVERLAY_ENABLED = booleanPreferencesKey("overlay_enabled")
        val KEY_CACHE_ENABLED = booleanPreferencesKey("cache_enabled")

        /** DeepSeek Chat API (default). */
        const val BACKEND_DEEPSEEK = "deepseek"
        /** Anthropic Claude Messages API. */
        const val BACKEND_CLAUDE = "claude"
        /** OpenAI GPT Chat Completions API. */
        const val BACKEND_GPT = "gpt"
    }

    private val tag = "Settings"

    /** Which LLM backend to use for translation. Default: [BACKEND_DEEPSEEK]. */
    val translationBackend: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_TRANSLATION_BACKEND] ?: BACKEND_DEEPSEEK
    }

    /** User's API key for the selected translation backend. */
    val apiKey: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_API_KEY] ?: ""
    }

    /** The player's FGO Master name for dialogue personalization. */
    val playerName: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_PLAYER_NAME] ?: ""
    }

    /** Font size preference: "auto", "small", "medium", or "large". */
    val fontSize: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_FONT_SIZE] ?: "auto"
    }

    /** Whether the translation overlay is enabled. */
    val overlayEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_OVERLAY_ENABLED] ?: true
    }

    /** Whether translation caching is enabled. */
    val cacheEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_CACHE_ENABLED] ?: true
    }

    suspend fun setTranslationBackend(backend: String) {
        context.dataStore.edit { it[KEY_TRANSLATION_BACKEND] = backend }
        FgoLogger.debug(tag, "Setting updated: translation_backend=$backend")
    }

    suspend fun setApiKey(key: String) {
        context.dataStore.edit { it[KEY_API_KEY] = key }
        // Redact the actual key value to prevent accidental log leakage
        FgoLogger.debug(tag, "Setting updated: api_key=(redacted, ${key.length} chars)")
    }

    suspend fun setPlayerName(name: String) {
        context.dataStore.edit { it[KEY_PLAYER_NAME] = name }
        FgoLogger.debug(tag, "Setting updated: player_name=$name")
    }

    suspend fun setFontSize(size: String) {
        context.dataStore.edit { it[KEY_FONT_SIZE] = size }
        FgoLogger.debug(tag, "Setting updated: font_size=$size")
    }

    suspend fun setOverlayEnabled(enabled: Boolean) {
        context.dataStore.edit { it[KEY_OVERLAY_ENABLED] = enabled }
        FgoLogger.debug(tag, "Setting updated: overlay_enabled=$enabled")
    }

    suspend fun setCacheEnabled(enabled: Boolean) {
        context.dataStore.edit { it[KEY_CACHE_ENABLED] = enabled }
        FgoLogger.debug(tag, "Setting updated: cache_enabled=$enabled")
    }
}
