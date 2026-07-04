package com.fgogotran.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.fgogotran.terminology.LocalCharacterNameEntity
import com.fgogotran.terminology.LocalGlossaryDao
import com.fgogotran.terminology.LocalGlossaryDatabase
import com.fgogotran.util.FgoLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID
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
    @ApplicationContext private val context: Context,
    private val localGlossaryDao: LocalGlossaryDao
) {
    companion object {
        val KEY_TRANSLATION_BACKEND = stringPreferencesKey("translation_backend")
        val KEY_API_KEY = stringPreferencesKey("api_key")
        val KEY_API_BASE_URL = stringPreferencesKey("api_base_url")
        val KEY_API_MODEL = stringPreferencesKey("api_model")
        val KEY_PLAYER_NAME = stringPreferencesKey("player_name")
        val KEY_CACHE_ENABLED = booleanPreferencesKey("cache_enabled")
        val KEY_SHOW_ORIGINAL_GAME_TEXT = booleanPreferencesKey("show_original_game_text")
        val KEY_DEBUG_LOGGING_ENABLED = booleanPreferencesKey("debug_logging_enabled")
        val KEY_TARGET_CHINESE_LOCALE = stringPreferencesKey("target_chinese_locale")
        val KEY_DB_CONTENT_VERSION = stringPreferencesKey("db_content_version")
        val KEY_DB_SHA256 = stringPreferencesKey("db_sha256")
        val KEY_DB_LOCALE = stringPreferencesKey("db_locale")
        val KEY_DB_LAST_CHECK_AT = longPreferencesKey("db_last_check_at")
        val KEY_DB_LAST_UPDATE_AT = longPreferencesKey("db_last_update_at")
        val KEY_IGNORED_APP_UPDATE_VERSION_CODE = longPreferencesKey("ignored_app_update_version_code")
        val KEY_FLOATING_BUTTON_X = intPreferencesKey("floating_button_x")
        val KEY_FLOATING_BUTTON_Y = intPreferencesKey("floating_button_y")
        val KEY_FLOATING_BUTTON_PORTRAIT_X = intPreferencesKey("floating_button_portrait_x")
        val KEY_FLOATING_BUTTON_PORTRAIT_Y = intPreferencesKey("floating_button_portrait_y")
        val KEY_FLOATING_BUTTON_LANDSCAPE_X = intPreferencesKey("floating_button_landscape_x")
        val KEY_FLOATING_BUTTON_LANDSCAPE_Y = intPreferencesKey("floating_button_landscape_y")
        val KEY_FLOATING_BUTTON_SIZE_DP = intPreferencesKey("floating_button_size_dp")
        val KEY_LAST_TRANSLATION_MODE = stringPreferencesKey("last_translation_mode")
        val KEY_ANALYTICS_INSTALL_ID = stringPreferencesKey("analytics_install_id")
        val KEY_ANALYTICS_FIRST_INSTALL_SENT = booleanPreferencesKey("analytics_first_install_sent")
        val KEY_ANALYTICS_DAILY_ACTIVE_DATE = stringPreferencesKey("analytics_daily_active_date")
        val KEY_QWEN_SITE = stringPreferencesKey("qwen_site")

        const val DEFAULT_FLOATING_BUTTON_X = 8
        const val DEFAULT_FLOATING_BUTTON_Y = 300
        const val MIN_FLOATING_BUTTON_SIZE_DP = 44
        const val DEFAULT_FLOATING_BUTTON_SIZE_DP = 54
        const val MAX_FLOATING_BUTTON_SIZE_DP = 72
        const val DEFAULT_TRANSLATION_MODE = "MANUAL"

        /** DeepSeek Chat API (default). */
        const val BACKEND_DEEPSEEK = "deepseek"
        /** Zhipu BigModel GLM OpenAI-compatible API. */
        const val BACKEND_ZHIPU = "zhipu"
        /** Alibaba Cloud Bailian / Model Studio Qwen OpenAI-compatible API. */
        const val BACKEND_QWEN = "qwen"
        /** Anthropic Claude Messages API. */
        const val BACKEND_CLAUDE = "claude"
        /** OpenAI GPT Chat Completions API. */
        const val BACKEND_GPT = "gpt"
        /** Google Gemini OpenAI-compatible API. */
        const val BACKEND_GEMINI = "gemini"
        /** Custom OpenAI-compatible Chat Completions API. */
        const val BACKEND_CUSTOM_OPENAI = "custom_openai"

        const val DEFAULT_DEEPSEEK_BASE_URL = "https://api.deepseek.com/v1/chat/completions"
        const val DEFAULT_ZHIPU_BASE_URL = "https://open.bigmodel.cn/api/paas/v4/chat/completions"
        const val QWEN_SITE_CHINA = "china"
        const val QWEN_SITE_INTERNATIONAL = "international"
        const val DEFAULT_QWEN_SITE = QWEN_SITE_CHINA

        const val DEFAULT_QWEN_CHINA_BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions"
        const val DEFAULT_QWEN_INTERNATIONAL_BASE_URL = "https://dashscope-intl.aliyuncs.com/compatible-mode/v1/chat/completions"
        const val DEFAULT_QWEN_BASE_URL = DEFAULT_QWEN_CHINA_BASE_URL
        const val DEFAULT_OPENAI_BASE_URL = "https://api.openai.com/v1/chat/completions"
        const val DEFAULT_GEMINI_BASE_URL = "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions"
        const val DEFAULT_CLAUDE_BASE_URL = "https://api.anthropic.com/v1/messages"

        const val DEFAULT_DEEPSEEK_MODEL = "deepseek-v4-flash"
        const val DEFAULT_ZHIPU_MODEL = "glm-4.5-air"
        const val DEFAULT_QWEN_MODEL = "qwen-flash"
        const val DEFAULT_OPENAI_MODEL = "gpt-4o"
        const val DEFAULT_GEMINI_MODEL = "gemini-3.1-flash-lite"
        const val DEFAULT_CLAUDE_MODEL = "claude-sonnet-4-20250514"
        const val DEFAULT_CUSTOM_MODEL = "deepseek-v4-flash"
        const val TARGET_LOCALE_SIMPLIFIED = "zh-Hans"
        const val TARGET_LOCALE_TRADITIONAL = "zh-Hant"

        private val SUPPORTED_BACKENDS = setOf(
            BACKEND_DEEPSEEK,
            BACKEND_ZHIPU,
            BACKEND_QWEN,
            BACKEND_CLAUDE,
            BACKEND_GPT,
            BACKEND_GEMINI,
            BACKEND_CUSTOM_OPENAI
        )
        private val SUPPORTED_TRANSLATION_MODES = setOf("MANUAL", "SEMI_AUTO", "AUTO")

        fun normalizeBackend(backend: String): String =
            backend.takeIf { it in SUPPORTED_BACKENDS } ?: BACKEND_DEEPSEEK

        fun normalizeTranslationMode(mode: String): String =
            mode.takeIf { it in SUPPORTED_TRANSLATION_MODES } ?: DEFAULT_TRANSLATION_MODE

        fun normalizeFloatingButtonSizeDp(sizeDp: Int): Int =
            sizeDp.coerceIn(MIN_FLOATING_BUTTON_SIZE_DP, MAX_FLOATING_BUTTON_SIZE_DP)

        fun normalizeTargetChineseLocale(locale: String): String = when (locale) {
            TARGET_LOCALE_TRADITIONAL -> TARGET_LOCALE_TRADITIONAL
            else -> TARGET_LOCALE_SIMPLIFIED
        }

        fun normalizeQwenSite(site: String): String = when (site) {
            QWEN_SITE_INTERNATIONAL -> QWEN_SITE_INTERNATIONAL
            else -> QWEN_SITE_CHINA
        }

        fun defaultQwenBaseUrl(site: String): String = when (normalizeQwenSite(site)) {
            QWEN_SITE_INTERNATIONAL -> DEFAULT_QWEN_INTERNATIONAL_BASE_URL
            else -> DEFAULT_QWEN_CHINA_BASE_URL
        }

        fun inferQwenSiteFromBaseUrl(baseUrl: String): String? {
            val normalized = baseUrl.trim().lowercase()
            return when {
                "dashscope-intl.aliyuncs.com" in normalized -> QWEN_SITE_INTERNATIONAL
                "ap-southeast-1.maas.aliyuncs.com" in normalized -> QWEN_SITE_INTERNATIONAL
                "dashscope.aliyuncs.com" in normalized -> QWEN_SITE_CHINA
                "cn-beijing.maas.aliyuncs.com" in normalized -> QWEN_SITE_CHINA
                "cn-hongkong.maas.aliyuncs.com" in normalized -> QWEN_SITE_INTERNATIONAL
                else -> null
            }
        }

        fun defaultApiBaseUrl(backend: String): String = when (normalizeBackend(backend)) {
            BACKEND_ZHIPU -> DEFAULT_ZHIPU_BASE_URL
            BACKEND_QWEN -> DEFAULT_QWEN_BASE_URL
            BACKEND_CLAUDE -> DEFAULT_CLAUDE_BASE_URL
            BACKEND_GPT -> DEFAULT_OPENAI_BASE_URL
            BACKEND_GEMINI -> DEFAULT_GEMINI_BASE_URL
            BACKEND_CUSTOM_OPENAI -> DEFAULT_DEEPSEEK_BASE_URL
            else -> DEFAULT_DEEPSEEK_BASE_URL
        }

        fun defaultApiModel(backend: String): String = when (normalizeBackend(backend)) {
            BACKEND_ZHIPU -> DEFAULT_ZHIPU_MODEL
            BACKEND_QWEN -> DEFAULT_QWEN_MODEL
            BACKEND_CLAUDE -> DEFAULT_CLAUDE_MODEL
            BACKEND_GPT -> DEFAULT_OPENAI_MODEL
            BACKEND_GEMINI -> DEFAULT_GEMINI_MODEL
            BACKEND_CUSTOM_OPENAI -> DEFAULT_CUSTOM_MODEL
            else -> DEFAULT_DEEPSEEK_MODEL
        }

        fun backendDisplayName(backend: String): String = when (normalizeBackend(backend)) {
            BACKEND_DEEPSEEK -> "DeepSeek"
            BACKEND_ZHIPU -> "智谱 GLM"
            BACKEND_QWEN -> "阿里云百炼 Qwen"
            BACKEND_CLAUDE -> "Anthropic Claude"
            BACKEND_GPT -> "OpenAI GPT"
            BACKEND_GEMINI -> "Google Gemini"
            BACKEND_CUSTOM_OPENAI -> "自定义接口"
            else -> "DeepSeek"
        }

        fun requiresApiKey(backend: String): Boolean = normalizeBackend(backend).isNotBlank()

        fun apiKeyPreferenceKey(backend: String) =
            stringPreferencesKey("api_key_${normalizeBackend(backend)}")

        fun apiBaseUrlPreferenceKey(backend: String) =
            stringPreferencesKey("api_base_url_${normalizeBackend(backend)}")

        fun apiModelPreferenceKey(backend: String) =
            stringPreferencesKey("api_model_${normalizeBackend(backend)}")

        private fun analyticsModeDatePreferenceKey(mode: String) =
            stringPreferencesKey("analytics_mode_${analyticsSafeSegment(mode)}_date")

        private fun analyticsBackendDatePreferenceKey(backend: String) =
            stringPreferencesKey("analytics_backend_${normalizeBackend(backend)}_date")

        private fun analyticsSafeSegment(value: String): String {
            return value.lowercase()
                .replace(Regex("[^a-z0-9_]+"), "_")
                .trim('_')
                .ifBlank { "unknown" }
        }
    }

    private val tag = "Settings"

    /** Which LLM backend to use for translation. Default: [BACKEND_DEEPSEEK]. */
    val translationBackend: Flow<String> = context.dataStore.data.map { prefs ->
        normalizeBackend(prefs[KEY_TRANSLATION_BACKEND] ?: BACKEND_DEEPSEEK)
    }

    /** User's API key for the selected translation backend. */
    val apiKey: Flow<String> = context.dataStore.data.map { prefs ->
        val backend = normalizeBackend(prefs[KEY_TRANSLATION_BACKEND] ?: BACKEND_DEEPSEEK)
        prefs[apiKeyPreferenceKey(backend)] ?: ""
    }

    suspend fun getApiKeyForBackend(backend: String): String {
        val normalizedBackend = normalizeBackend(backend)
        return context.dataStore.data.map { prefs ->
            prefs[apiKeyPreferenceKey(normalizedBackend)] ?: ""
        }.first()
    }

    /** Chat completions endpoint for the selected backend. Blank means provider default. */
    val apiBaseUrl: Flow<String> = context.dataStore.data.map { prefs ->
        val backend = normalizeBackend(prefs[KEY_TRANSLATION_BACKEND] ?: BACKEND_DEEPSEEK)
        if (backend == BACKEND_CUSTOM_OPENAI) {
            prefs[apiBaseUrlPreferenceKey(backend)] ?: prefs[KEY_API_BASE_URL] ?: ""
        } else if (backend == BACKEND_QWEN) {
            defaultQwenBaseUrl(resolveQwenSite(prefs))
        } else {
            defaultApiBaseUrl(backend)
        }
    }

    suspend fun getApiBaseUrlForBackend(backend: String): String {
        val normalizedBackend = normalizeBackend(backend)
        if (normalizedBackend == BACKEND_QWEN) {
            return context.dataStore.data.map { prefs ->
                defaultQwenBaseUrl(resolveQwenSite(prefs))
            }.first()
        }
        if (normalizedBackend != BACKEND_CUSTOM_OPENAI) {
            return defaultApiBaseUrl(normalizedBackend)
        }
        return context.dataStore.data.map { prefs ->
            val selectedBackend = normalizeBackend(prefs[KEY_TRANSLATION_BACKEND] ?: BACKEND_DEEPSEEK)
            prefs[apiBaseUrlPreferenceKey(normalizedBackend)]
                ?: prefs[KEY_API_BASE_URL]?.takeIf { normalizedBackend == selectedBackend }
                ?: ""
        }.first()
    }

    val qwenSite: Flow<String> = context.dataStore.data.map { prefs ->
        resolveQwenSite(prefs)
    }

    suspend fun getQwenSite(): String {
        return qwenSite.first()
    }

    /** Model name for the selected backend. */
    val apiModel: Flow<String> = context.dataStore.data.map { prefs ->
        val backend = normalizeBackend(prefs[KEY_TRANSLATION_BACKEND] ?: BACKEND_DEEPSEEK)
        prefs[apiModelPreferenceKey(backend)] ?: prefs[KEY_API_MODEL] ?: defaultApiModel(backend)
    }

    suspend fun getApiModelForBackend(backend: String): String {
        val normalizedBackend = normalizeBackend(backend)
        return context.dataStore.data.map { prefs ->
            val selectedBackend = normalizeBackend(prefs[KEY_TRANSLATION_BACKEND] ?: BACKEND_DEEPSEEK)
            prefs[apiModelPreferenceKey(normalizedBackend)]
                ?: prefs[KEY_API_MODEL]?.takeIf { normalizedBackend == selectedBackend }
                ?: ""
        }.first()
    }

    /** The player's FGO Master name for dialogue personalization. */
    val playerName: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_PLAYER_NAME] ?: ""
    }

    /** Whether translation caching is enabled. */
    val cacheEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_CACHE_ENABLED] ?: true
    }

    /** Whether the dialogue overlay also renders the original game text below translation. */
    val showOriginalGameText: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_SHOW_ORIGINAL_GAME_TEXT] ?: false
    }

    /** Whether diagnostic Logcat output is enabled. Disabled by default for privacy. */
    val debugLoggingEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_DEBUG_LOGGING_ENABLED] ?: false
    }

    /** Target Chinese script for translated output. */
    val targetChineseLocale: Flow<String> = context.dataStore.data.map { prefs ->
        normalizeTargetChineseLocale(prefs[KEY_TARGET_CHINESE_LOCALE] ?: TARGET_LOCALE_SIMPLIFIED)
    }

    /** Latest installed CDN terminology DB content version, blank before online DB install. */
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

    /** Latest app update version code the user chose not to be reminded about automatically. */
    val ignoredAppUpdateVersionCode: Flow<Long> = context.dataStore.data.map { prefs ->
        prefs[KEY_IGNORED_APP_UPDATE_VERSION_CODE] ?: 0L
    }

    /** Last user-positioned floating button x coordinate. */
    val floatingButtonX: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[KEY_FLOATING_BUTTON_X] ?: DEFAULT_FLOATING_BUTTON_X
    }

    /** Last user-positioned floating button y coordinate. */
    val floatingButtonY: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[KEY_FLOATING_BUTTON_Y] ?: DEFAULT_FLOATING_BUTTON_Y
    }

    /** User-selected floating button diameter in dp. */
    val floatingButtonSizeDp: Flow<Int> = context.dataStore.data.map { prefs ->
        normalizeFloatingButtonSizeDp(prefs[KEY_FLOATING_BUTTON_SIZE_DP] ?: DEFAULT_FLOATING_BUTTON_SIZE_DP)
    }

    /** Last translation mode selected by the user from the floating menu. */
    val lastTranslationMode: Flow<String> = context.dataStore.data.map { prefs ->
        normalizeTranslationMode(prefs[KEY_LAST_TRANSLATION_MODE] ?: DEFAULT_TRANSLATION_MODE)
    }

    suspend fun getLastTranslationMode(): String {
        return lastTranslationMode.first()
    }

    suspend fun setLastTranslationMode(mode: String) {
        val normalizedMode = normalizeTranslationMode(mode)
        context.dataStore.edit { it[KEY_LAST_TRANSLATION_MODE] = normalizedMode }
        FgoLogger.debug(tag, "Setting updated: last_translation_mode=$normalizedMode")
    }

    suspend fun getOrCreateAnalyticsInstallId(): String {
        val existing = context.dataStore.data.map { prefs ->
            prefs[KEY_ANALYTICS_INSTALL_ID].orEmpty()
        }.first()
        if (existing.isNotBlank()) return existing

        val generated = UUID.randomUUID().toString()
        context.dataStore.edit { prefs ->
            if (prefs[KEY_ANALYTICS_INSTALL_ID].isNullOrBlank()) {
                prefs[KEY_ANALYTICS_INSTALL_ID] = generated
            }
        }
        return context.dataStore.data.map { prefs ->
            prefs[KEY_ANALYTICS_INSTALL_ID] ?: generated
        }.first()
    }

    suspend fun shouldSendAnalyticsFirstInstall(): Boolean {
        return context.dataStore.data.map { prefs ->
            prefs[KEY_ANALYTICS_FIRST_INSTALL_SENT] != true
        }.first()
    }

    suspend fun markAnalyticsFirstInstallSent() {
        context.dataStore.edit { it[KEY_ANALYTICS_FIRST_INSTALL_SENT] = true }
    }

    suspend fun shouldSendAnalyticsDailyActive(date: String): Boolean {
        return context.dataStore.data.map { prefs ->
            prefs[KEY_ANALYTICS_DAILY_ACTIVE_DATE] != date
        }.first()
    }

    suspend fun markAnalyticsDailyActiveSent(date: String) {
        context.dataStore.edit { it[KEY_ANALYTICS_DAILY_ACTIVE_DATE] = date }
    }

    suspend fun shouldSendAnalyticsMode(mode: String, date: String): Boolean {
        val key = analyticsModeDatePreferenceKey(mode)
        return context.dataStore.data.map { prefs ->
            prefs[key] != date
        }.first()
    }

    suspend fun markAnalyticsModeSent(mode: String, date: String) {
        val key = analyticsModeDatePreferenceKey(mode)
        context.dataStore.edit { it[key] = date }
    }

    suspend fun shouldSendAnalyticsBackend(backend: String, date: String): Boolean {
        val key = analyticsBackendDatePreferenceKey(backend)
        return context.dataStore.data.map { prefs ->
            prefs[key] != date
        }.first()
    }

    suspend fun markAnalyticsBackendSent(backend: String, date: String) {
        val key = analyticsBackendDatePreferenceKey(backend)
        context.dataStore.edit { it[key] = date }
    }

    suspend fun getIgnoredAppUpdateVersionCode(): Long {
        return ignoredAppUpdateVersionCode.first()
    }

    suspend fun setIgnoredAppUpdateVersionCode(versionCode: Long) {
        val safeVersionCode = versionCode.coerceAtLeast(0L)
        context.dataStore.edit { it[KEY_IGNORED_APP_UPDATE_VERSION_CODE] = safeVersionCode }
        FgoLogger.debug(tag, "Setting updated: ignored_app_update_version_code=$safeVersionCode")
    }

    suspend fun saveApiSettings(
        backend: String,
        apiKey: String,
        apiBaseUrl: String,
        apiModel: String,
        qwenSite: String = DEFAULT_QWEN_SITE
    ) {
        val normalizedBackend = normalizeBackend(backend)
        val normalizedQwenSite = normalizeQwenSite(qwenSite)
        context.dataStore.edit {
            it[KEY_TRANSLATION_BACKEND] = normalizedBackend
            it[apiKeyPreferenceKey(normalizedBackend)] = apiKey.trim()
            it[apiBaseUrlPreferenceKey(normalizedBackend)] = apiBaseUrl.trim()
            it[apiModelPreferenceKey(normalizedBackend)] = apiModel.trim()
            it[KEY_API_BASE_URL] = apiBaseUrl.trim()
            it[KEY_API_MODEL] = apiModel.trim()
            if (normalizedBackend == BACKEND_QWEN) {
                it[KEY_QWEN_SITE] = normalizedQwenSite
            }
        }
        FgoLogger.debug(
            tag,
            "API settings updated: backend=$normalizedBackend, model=${apiModel.trim()}, api_key=(redacted, ${apiKey.trim().length} chars)"
        )
    }

    suspend fun setPlayerName(name: String) {
        val trimmedName = name.trim()
        context.dataStore.edit { it[KEY_PLAYER_NAME] = trimmedName }
        if (trimmedName.isBlank()) {
            localGlossaryDao.deleteCharacterName(LocalGlossaryDatabase.PLAYER_NAME_RECORD_ID)
            FgoLogger.debug(tag, "Local player name glossary row removed")
        } else {
            localGlossaryDao.upsertCharacterName(
                LocalCharacterNameEntity(
                    id = LocalGlossaryDatabase.PLAYER_NAME_RECORD_ID,
                    jpName = trimmedName,
                    cnName = trimmedName,
                    aliases = null
                )
            )
            FgoLogger.debug(tag, "Local player name glossary row updated")
        }
        FgoLogger.debug(tag, "Setting updated: player_name=$trimmedName")
    }

    suspend fun setCacheEnabled(enabled: Boolean) {
        context.dataStore.edit { it[KEY_CACHE_ENABLED] = enabled }
        FgoLogger.debug(tag, "Setting updated: cache_enabled=$enabled")
    }

    suspend fun setShowOriginalGameText(enabled: Boolean) {
        context.dataStore.edit { it[KEY_SHOW_ORIGINAL_GAME_TEXT] = enabled }
        FgoLogger.debug(tag, "Setting updated: show_original_game_text=$enabled")
    }

    suspend fun setDebugLoggingEnabled(enabled: Boolean) {
        context.dataStore.edit { it[KEY_DEBUG_LOGGING_ENABLED] = enabled }
        FgoLogger.setEnabled(enabled)
        FgoLogger.debug(tag, "Setting updated: debug_logging_enabled=$enabled")
    }

    suspend fun setTargetChineseLocale(locale: String) {
        val normalizedLocale = normalizeTargetChineseLocale(locale)
        context.dataStore.edit { it[KEY_TARGET_CHINESE_LOCALE] = normalizedLocale }
        FgoLogger.debug(tag, "Setting updated: target_chinese_locale=$normalizedLocale")
    }

    private fun resolveQwenSite(prefs: Preferences): String {
        prefs[KEY_QWEN_SITE]?.let { return normalizeQwenSite(it) }

        val selectedBackend = normalizeBackend(prefs[KEY_TRANSLATION_BACKEND] ?: BACKEND_DEEPSEEK)
        val savedQwenBaseUrl = prefs[apiBaseUrlPreferenceKey(BACKEND_QWEN)]
        val legacySelectedBaseUrl = prefs[KEY_API_BASE_URL]?.takeIf { selectedBackend == BACKEND_QWEN }
        return inferQwenSiteFromBaseUrl(savedQwenBaseUrl.orEmpty())
            ?: inferQwenSiteFromBaseUrl(legacySelectedBaseUrl.orEmpty())
            ?: DEFAULT_QWEN_SITE
    }

    suspend fun setFloatingButtonPosition(x: Int, y: Int) {
        val safeX = x.coerceAtLeast(0)
        val safeY = y.coerceAtLeast(0)
        context.dataStore.edit {
            it[KEY_FLOATING_BUTTON_X] = safeX
            it[KEY_FLOATING_BUTTON_Y] = safeY
        }
        FgoLogger.debug(tag, "Setting updated: floating_button=($safeX,$safeY)")
    }

    suspend fun getFloatingButtonPosition(isLandscape: Boolean): Pair<Int, Int> {
        return context.dataStore.data.map { prefs ->
            if (isLandscape) {
                Pair(
                    prefs[KEY_FLOATING_BUTTON_LANDSCAPE_X]
                        ?: prefs[KEY_FLOATING_BUTTON_X]
                        ?: DEFAULT_FLOATING_BUTTON_X,
                    prefs[KEY_FLOATING_BUTTON_LANDSCAPE_Y]
                        ?: prefs[KEY_FLOATING_BUTTON_Y]
                        ?: DEFAULT_FLOATING_BUTTON_Y
                )
            } else {
                Pair(
                    prefs[KEY_FLOATING_BUTTON_PORTRAIT_X] ?: DEFAULT_FLOATING_BUTTON_X,
                    prefs[KEY_FLOATING_BUTTON_PORTRAIT_Y] ?: DEFAULT_FLOATING_BUTTON_Y
                )
            }
        }.first()
    }

    suspend fun setFloatingButtonPosition(x: Int, y: Int, isLandscape: Boolean) {
        val safeX = x.coerceAtLeast(0)
        val safeY = y.coerceAtLeast(0)
        context.dataStore.edit {
            if (isLandscape) {
                it[KEY_FLOATING_BUTTON_LANDSCAPE_X] = safeX
                it[KEY_FLOATING_BUTTON_LANDSCAPE_Y] = safeY
            } else {
                it[KEY_FLOATING_BUTTON_PORTRAIT_X] = safeX
                it[KEY_FLOATING_BUTTON_PORTRAIT_Y] = safeY
            }
        }
        val orientation = if (isLandscape) "landscape" else "portrait"
        FgoLogger.debug(tag, "Setting updated: floating_button_$orientation=($safeX,$safeY)")
    }

    suspend fun getFloatingButtonSizeDp(): Int {
        return floatingButtonSizeDp.first()
    }

    suspend fun setFloatingButtonSizeDp(sizeDp: Int) {
        val safeSize = normalizeFloatingButtonSizeDp(sizeDp)
        context.dataStore.edit { it[KEY_FLOATING_BUTTON_SIZE_DP] = safeSize }
        FgoLogger.debug(tag, "Setting updated: floating_button_size_dp=$safeSize")
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
