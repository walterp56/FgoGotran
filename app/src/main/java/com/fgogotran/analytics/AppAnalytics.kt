package com.fgogotran.analytics

import android.content.Context
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import com.fgogotran.data.SettingsRepository
import com.fgogotran.translation.TranslationMode
import com.fgogotran.util.FgoLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.ZoneOffset
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppAnalytics @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository
) {
    private val httpClient = HttpClient {
        install(HttpTimeout) {
            connectTimeoutMillis = REQUEST_TIMEOUT_MS
            requestTimeoutMillis = REQUEST_TIMEOUT_MS
            socketTimeoutMillis = REQUEST_TIMEOUT_MS
        }
        install(ContentNegotiation) {
            json(Json {
                encodeDefaults = false
                ignoreUnknownKeys = true
            })
        }
    }
    private val sendMutex = Mutex()
    private val tag = "Analytics"

    suspend fun reportAppUsed() = withContext(Dispatchers.IO) {
        sendMutex.withLock {
            val today = currentUtcDate()
            val installId = settingsRepository.getOrCreateAnalyticsInstallId()

            if (settingsRepository.shouldSendAnalyticsFirstInstall()) {
                val firstInstallResult = sendEvent(
                    eventType = EVENT_FIRST_INSTALL,
                    installId = installId
                )
                if (firstInstallResult.shouldMarkHandled()) {
                    settingsRepository.markAnalyticsFirstInstallSent()
                }
            }

            if (settingsRepository.shouldSendAnalyticsDailyActive(today)) {
                val dailyActiveResult = sendEvent(
                    eventType = EVENT_DAILY_ACTIVE,
                    installId = installId
                )
                if (dailyActiveResult.shouldMarkHandled()) {
                    settingsRepository.markAnalyticsDailyActiveSent(today)
                }
            }
        }
    }

    suspend fun reportCurrentBackendType() {
        reportBackendType(settingsRepository.translationBackend.first())
    }

    suspend fun reportBackendType(backend: String) = withContext(Dispatchers.IO) {
        val normalizedBackend = SettingsRepository.normalizeBackend(backend)
        sendMutex.withLock {
            val today = currentUtcDate()
            if (!settingsRepository.shouldSendAnalyticsBackend(normalizedBackend, today)) return@withLock

            val installId = settingsRepository.getOrCreateAnalyticsInstallId()
            val result = sendEvent(
                eventType = EVENT_API_BACKEND_TYPE,
                installId = installId,
                backendType = normalizedBackend
            )
            if (result.shouldMarkHandled()) {
                settingsRepository.markAnalyticsBackendSent(normalizedBackend, today)
            }
        }
    }

    suspend fun reportTranslationMode(mode: TranslationMode) {
        reportMode(
            when (mode) {
                TranslationMode.MANUAL -> "manual"
                TranslationMode.SEMI_AUTO -> "semi_auto"
                TranslationMode.AUTO -> "auto"
            }
        )
    }

    suspend fun reportCropModeUsed() {
        reportMode("crop")
    }

    suspend fun reportGameServerUsed(server: String) {
        reportServerEvent(EVENT_GAME_SERVER_USED, server)
    }

    suspend fun reportVoiceServerUsed(server: String) {
        reportServerEvent(EVENT_VOICE_SERVER_USED, server)
    }

    private suspend fun reportMode(mode: String) = withContext(Dispatchers.IO) {
        sendMutex.withLock {
            val today = currentUtcDate()
            if (!settingsRepository.shouldSendAnalyticsMode(mode, today)) return@withLock

            val installId = settingsRepository.getOrCreateAnalyticsInstallId()
            val result = sendEvent(
                eventType = EVENT_TRANSLATION_MODE_USED,
                installId = installId,
                mode = mode
            )
            if (result.shouldMarkHandled()) {
                settingsRepository.markAnalyticsModeSent(mode, today)
            }
        }
    }

    private suspend fun reportServerEvent(eventType: String, server: String) = withContext(Dispatchers.IO) {
        val normalizedServer = SettingsRepository.normalizeGameServer(server)
        sendMutex.withLock {
            val today = currentUtcDate()
            if (!settingsRepository.shouldSendAnalyticsServerEvent(eventType, normalizedServer, today)) {
                return@withLock
            }

            val installId = settingsRepository.getOrCreateAnalyticsInstallId()
            val result = sendEvent(
                eventType = eventType,
                installId = installId,
                server = normalizedServer
            )
            if (result.shouldMarkHandled()) {
                settingsRepository.markAnalyticsServerEventSent(eventType, normalizedServer, today)
            }
        }
    }

    private suspend fun sendEvent(
        eventType: String,
        installId: String,
        mode: String? = null,
        backendType: String? = null,
        server: String? = null
    ): AnalyticsSendResult {
        val payload = AnalyticsPayload(
            installId = installId,
            eventType = eventType,
            appVersion = currentVersionName(),
            appVersionCode = currentVersionCode(),
            locale = settingsRepository.targetLanguage.first(),
            androidVersion = currentAndroidVersion(),
            mode = mode,
            backendType = backendType,
            server = server
        )

        return runCatching {
            val response = httpClient.post(ANALYTICS_ENDPOINT) {
                contentType(ContentType.Application.Json)
                setBody(payload)
            }
            val status = response.status.value
            when {
                status in 200..299 -> AnalyticsSendResult.SENT
                status in 400..499 -> {
                    // Non-retryable rejection (for example an unknown server value). Mark it as
                    // handled so it is not resent on every dialogue, but keep the log line.
                    FgoLogger.debug(
                        tag,
                        "Analytics event rejected: $eventType HTTP $status; not retrying today"
                    )
                    AnalyticsSendResult.REJECTED
                }
                else -> {
                    FgoLogger.debug(tag, "Analytics event failed: $eventType HTTP $status")
                    AnalyticsSendResult.FAILED
                }
            }
        }.getOrElse { error ->
            FgoLogger.debug(tag, "Analytics event failed: $eventType (${error.message})")
            AnalyticsSendResult.FAILED
        }
    }

    private fun AnalyticsSendResult.shouldMarkHandled(): Boolean {
        return this == AnalyticsSendResult.SENT || this == AnalyticsSendResult.REJECTED
    }

    private fun currentVersionName(): String {
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        return packageInfo.versionName ?: "unknown"
    }

    private fun currentVersionCode(): Long {
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        return PackageInfoCompat.getLongVersionCode(packageInfo)
    }

    private fun currentAndroidVersion(): String {
        return Build.VERSION.RELEASE.takeIf { it.isNotBlank() } ?: Build.VERSION.SDK_INT.toString()
    }

    private fun currentUtcDate(): String {
        return Instant.now().atZone(ZoneOffset.UTC).toLocalDate().toString()
    }

    @Serializable
    private data class AnalyticsPayload(
        @SerialName("install_id") val installId: String,
        @SerialName("event_type") val eventType: String,
        @SerialName("app_version") val appVersion: String,
        @SerialName("app_version_code") val appVersionCode: Long,
        val locale: String,
        @SerialName("android_version") val androidVersion: String,
        val mode: String? = null,
        @SerialName("backend_type") val backendType: String? = null,
        val server: String? = null
    )

    private companion object {
        private const val ANALYTICS_ENDPOINT = "https://cdn.fgogotran.com/api/app-events"
        private const val REQUEST_TIMEOUT_MS = 3_000L
        private const val EVENT_FIRST_INSTALL = "first_install"
        private const val EVENT_DAILY_ACTIVE = "daily_active"
        private const val EVENT_TRANSLATION_MODE_USED = "translation_mode_used"
        private const val EVENT_API_BACKEND_TYPE = "api_backend_type"
        private const val EVENT_GAME_SERVER_USED = "game_server_used"
        private const val EVENT_VOICE_SERVER_USED = "voice_server_used"
    }
}

private enum class AnalyticsSendResult {
    SENT,
    REJECTED,
    FAILED
}
