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
                val firstInstallSent = sendEvent(
                    eventType = EVENT_FIRST_INSTALL,
                    installId = installId
                )
                if (firstInstallSent) {
                    settingsRepository.markAnalyticsFirstInstallSent()
                }
            }

            if (settingsRepository.shouldSendAnalyticsDailyActive(today)) {
                val dailyActiveSent = sendEvent(
                    eventType = EVENT_DAILY_ACTIVE,
                    installId = installId
                )
                if (dailyActiveSent) {
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
            val sent = sendEvent(
                eventType = EVENT_API_BACKEND_TYPE,
                installId = installId,
                backendType = normalizedBackend
            )
            if (sent) {
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

    private suspend fun reportMode(mode: String) = withContext(Dispatchers.IO) {
        sendMutex.withLock {
            val today = currentUtcDate()
            if (!settingsRepository.shouldSendAnalyticsMode(mode, today)) return@withLock

            val installId = settingsRepository.getOrCreateAnalyticsInstallId()
            val sent = sendEvent(
                eventType = EVENT_TRANSLATION_MODE_USED,
                installId = installId,
                mode = mode
            )
            if (sent) {
                settingsRepository.markAnalyticsModeSent(mode, today)
            }
        }
    }

    private suspend fun sendEvent(
        eventType: String,
        installId: String,
        mode: String? = null,
        backendType: String? = null
    ): Boolean {
        val payload = AnalyticsPayload(
            installId = installId,
            eventType = eventType,
            appVersion = currentVersionName(),
            appVersionCode = currentVersionCode(),
            locale = settingsRepository.targetChineseLocale.first(),
            androidVersion = currentAndroidVersion(),
            mode = mode,
            backendType = backendType
        )

        return runCatching {
            val response = httpClient.post(ANALYTICS_ENDPOINT) {
                contentType(ContentType.Application.Json)
                setBody(payload)
            }
            val success = response.status.value in 200..299
            if (!success) {
                FgoLogger.debug(tag, "Analytics event rejected: $eventType HTTP ${response.status.value}")
            }
            success
        }.getOrElse { error ->
            FgoLogger.debug(tag, "Analytics event failed: $eventType (${error.message})")
            false
        }
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
        @SerialName("backend_type") val backendType: String? = null
    )

    private companion object {
        private const val ANALYTICS_ENDPOINT = "https://cdn.fgogotran.com/api/app-events"
        private const val REQUEST_TIMEOUT_MS = 3_000L
        private const val EVENT_FIRST_INSTALL = "first_install"
        private const val EVENT_DAILY_ACTIVE = "daily_active"
        private const val EVENT_TRANSLATION_MODE_USED = "translation_mode_used"
        private const val EVENT_API_BACKEND_TYPE = "api_backend_type"
    }
}
