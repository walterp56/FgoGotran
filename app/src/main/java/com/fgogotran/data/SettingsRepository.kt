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
        val KEY_API_BASE_URL = stringPreferencesKey("api_base_url")
        val KEY_API_MODEL = stringPreferencesKey("api_model")
        val KEY_PLAYER_NAME = stringPreferencesKey("player_name")
        val KEY_CACHE_ENABLED = booleanPreferencesKey("cache_enabled")
        val KEY_DB_CONTENT_VERSION = stringPreferencesKey("db_content_version")
        val KEY_DB_SHA256 = stringPreferencesKey("db_sha256")
        val KEY_DB_LOCALE = stringPreferencesKey("db_locale")
        val KEY_DB_LAST_CHECK_AT = longPreferencesKey("db_last_check_at")
        val KEY_DB_LAST_UPDATE_AT = longPreferencesKey("db_last_update_at")

        /** FgoGotran hosted backend API. */
        const val BACKEND_FGOGOTRAN = "fgogotran"
        /** DeepSeek Chat API (default). */
        const val BACKEND_DEEPSEEK = "deepseek"
        /** Anthropic Claude Messages API. */
        const val BACKEND_CLAUDE = "claude"
        /** OpenAI GPT Chat Completions API. */
        const val BACKEND_GPT = "gpt"
        /** Custom OpenAI-compatible Chat Completions API. */
        const val BACKEND_CUSTOM_OPENAI = "custom_openai"

        const val DEFAULT_FGOGOTRAN_BASE_URL = "https://api.fgogotran.com/v1/chat/completions"
        const val DEFAULT_DEEPSEEK_BASE_URL = "https://api.deepseek.com/v1/chat/completions"
        const val DEFAULT_OPENAI_BASE_URL = "https://api.openai.com/v1/chat/completions"
        const val DEFAULT_CLAUDE_BASE_URL = "https://api.anthropic.com/v1/messages"

        const val DEFAULT_FGOGOTRAN_MODEL = "fast"
        const val DEFAULT_DEEPSEEK_MODEL = "deepseek-v4-flash"
        const val DEFAULT_OPENAI_MODEL = "gpt-4o"
        const val DEFAULT_CLAUDE_MODEL = "claude-sonnet-4-20250514"
        const val DEFAULT_CUSTOM_MODEL = "deepseek-v4-flash"

        fun defaultApiBaseUrl(backend: String): String = when (backend) {
            BACKEND_FGOGOTRAN -> DEFAULT_FGOGOTRAN_BASE_URL
            BACKEND_CLAUDE -> DEFAULT_CLAUDE_BASE_URL
            BACKEND_GPT -> DEFAULT_OPENAI_BASE_URL
            BACKEND_CUSTOM_OPENAI -> DEFAULT_DEEPSEEK_BASE_URL
            else -> DEFAULT_DEEPSEEK_BASE_URL
        }

        fun defaultApiModel(backend: String): String = when (backend) {
            BACKEND_FGOGOTRAN -> DEFAULT_FGOGOTRAN_MODEL
            BACKEND_CLAUDE -> DEFAULT_CLAUDE_MODEL
            BACKEND_GPT -> DEFAULT_OPENAI_MODEL
            BACKEND_CUSTOM_OPENAI -> DEFAULT_CUSTOM_MODEL
            else -> DEFAULT_DEEPSEEK_MODEL
        }

        fun backendDisplayName(backend: String): String = when (backend) {
            BACKEND_FGOGOTRAN -> "FgoGotran 后端"
            BACKEND_DEEPSEEK -> "DeepSeek"
            BACKEND_CLAUDE -> "Claude"
            BACKEND_GPT -> "OpenAI"
            BACKEND_CUSTOM_OPENAI -> "自定义接口"
            else -> "DeepSeek"
        }

        fun requiresApiKey(backend: String): Boolean = backend != BACKEND_FGOGOTRAN
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

    /** Chat completions endpoint for the selected backend. Blank means provider default. */
    val apiBaseUrl: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_API_BASE_URL] ?: ""
    }

    /** Model name for the selected backend. */
    val apiModel: Flow<String> = context.dataStore.data.map { prefs ->
        val backend = prefs[KEY_TRANSLATION_BACKEND] ?: BACKEND_DEEPSEEK
        prefs[KEY_API_MODEL] ?: defaultApiModel(backend)
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

    suspend fun setApiBaseUrl(url: String) {
        context.dataStore.edit { it[KEY_API_BASE_URL] = url.trim() }
        FgoLogger.debug(tag, "Setting updated: api_base_url=${url.trim()}")
    }

    suspend fun setApiModel(model: String) {
        context.dataStore.edit { it[KEY_API_MODEL] = model.trim() }
        FgoLogger.debug(tag, "Setting updated: api_model=${model.trim()}")
    }

    suspend fun saveApiSettings(
        backend: String,
        apiKey: String,
        apiBaseUrl: String,
        apiModel: String
    ) {
        context.dataStore.edit {
            it[KEY_TRANSLATION_BACKEND] = backend
            it[KEY_API_KEY] = apiKey.trim()
            it[KEY_API_BASE_URL] = apiBaseUrl.trim()
            it[KEY_API_MODEL] = apiModel.trim()
        }
        FgoLogger.debug(
            tag,
            "API settings updated: backend=$backend, model=${apiModel.trim()}, api_key=(redacted, ${apiKey.trim().length} chars)"
        )
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
