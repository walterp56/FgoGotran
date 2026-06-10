package com.fgogotran.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
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
        val KEY_CACHE_ENABLED = booleanPreferencesKey("cache_enabled")
        val KEY_DB_CONTENT_VERSION = stringPreferencesKey("db_content_version")
        val KEY_DB_SHA256 = stringPreferencesKey("db_sha256")
        val KEY_DB_LOCALE = stringPreferencesKey("db_locale")
        val KEY_DB_LAST_CHECK_AT = longPreferencesKey("db_last_check_at")
        val KEY_DB_LAST_UPDATE_AT = longPreferencesKey("db_last_update_at")

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

    /** Whether translation caching is enabled. */
    val cacheEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_CACHE_ENABLED] ?: true
    }

    /** Latest installed CDN terminology DB content version, blank when only bundled DB is known. */
    val dbContentVersion: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_DB_CONTENT_VERSION] ?: ""
    }

    /** SHA-256 of the installed terminology DB package, blank when unknown. */
    val dbSha256: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_DB_SHA256] ?: ""
    }

    /** Locale of the installed terminology DB package. */
    val dbLocale: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_DB_LOCALE] ?: "zh-Hans"
    }

    /** Last time an online terminology DB check was attempted, as epoch millis. */
    val dbLastCheckAt: Flow<Long> = context.dataStore.data.map { prefs ->
        prefs[KEY_DB_LAST_CHECK_AT] ?: 0L
    }

    /** Last time an online terminology DB package was installed, as epoch millis. */
    val dbLastUpdateAt: Flow<Long> = context.dataStore.data.map { prefs ->
        prefs[KEY_DB_LAST_UPDATE_AT] ?: 0L
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

    suspend fun setCacheEnabled(enabled: Boolean) {
        context.dataStore.edit { it[KEY_CACHE_ENABLED] = enabled }
        FgoLogger.debug(tag, "Setting updated: cache_enabled=$enabled")
    }

    suspend fun setDbLastCheckAt(checkedAt: Long) {
        context.dataStore.edit { it[KEY_DB_LAST_CHECK_AT] = checkedAt }
        FgoLogger.debug(tag, "Setting updated: db_last_check_at=$checkedAt")
    }

    suspend fun saveDbUpdateMetadata(
        contentVersion: String,
        sha256: String,
        locale: String,
        updatedAt: Long
    ) {
        context.dataStore.edit {
            it[KEY_DB_CONTENT_VERSION] = contentVersion
            it[KEY_DB_SHA256] = sha256
            it[KEY_DB_LOCALE] = locale
            it[KEY_DB_LAST_UPDATE_AT] = updatedAt
        }
        FgoLogger.info(
            tag,
            "DB metadata updated: version=$contentVersion, locale=$locale, sha256=$sha256"
        )
    }
}
