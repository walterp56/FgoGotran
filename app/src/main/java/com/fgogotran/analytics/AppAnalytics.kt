package com.fgogotran.analytics

import android.content.Context
import androidx.core.content.pm.PackageInfoCompat
import com.fgogotran.data.SettingsRepository
import com.fgogotran.localization.AppLanguageManager
import com.fgogotran.translation.TranslateResult
import com.fgogotran.util.FgoLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.utils.io.core.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
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
    }
    private val tag = "Analytics"
    private val reportingScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val v2Uploader = AnalyticsUploader(
        reportingScope,
        AnalyticsUploadSession(
            store = settingsRepository,
            send = { payload ->
                val response = httpClient.post(ANALYTICS_ENDPOINT) {
                    contentType(ContentType.Application.Json)
                    setBody(AnalyticsProtocol.encode(payload))
                }
                val body = if (response.status.value in 200..299) {
                    response.bodyAsChannel().readRemaining(AnalyticsProtocol.MAX_ACK_BYTES.toLong() + 1).readText()
                } else ""
                AnalyticsHttpResponse(response.status.value, body)
            },
            log = { message -> FgoLogger.debug(tag, message) }
        )
    )
    private sealed interface Recording {
        data class Profile(val uiLanguage: String, val foregroundDate: String?) : Recording
        data class Success(val facts: AnalyticsSuccess, val uiLanguage: String) : Recording
    }
    private val recordings = Channel<Recording>(Channel.UNLIMITED)

    init {
        // One FIFO recorder preserves profile and A -> B -> A success order without UI/HTTP waits.
        reportingScope.launch {
            for (recording in recordings) {
                try {
                    val uiLanguage = when (recording) {
                        is Recording.Profile -> recording.uiLanguage
                        is Recording.Success -> recording.uiLanguage
                    }
                    val profile = AnalyticsProfile(currentVersionName(), currentVersionCode(), uiLanguage)
                    settingsRepository.updateAnalyticsOutbox { current ->
                        when (recording) {
                            is Recording.Profile -> if (recording.foregroundDate == null) current.withProfile(profile)
                                else current.onForeground(profile, recording.foregroundDate)
                            is Recording.Success -> current.withProfile(profile).withSuccess(recording.facts)
                        }
                    }
                    v2Uploader.requestFlush()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    FgoLogger.debug(tag, "Analytics v2 persistence unavailable; existing facts retained")
                }
            }
        }
    }

    /** Called only by the app Activity, not accessibility-service connection. */
    fun reportAppForeground(uiLanguage: String) {
        recordings.trySend(Recording.Profile(uiLanguage, currentUtcDate()))
    }

    fun reportAppProfile(uiLanguage: String) {
        recordings.trySend(Recording.Profile(uiLanguage, foregroundDate = null))
    }

    /** Call after the existing valid-display decision, never after OCR/API completion alone. */
    fun reportTranslationDisplayed(origin: AnalyticsUsageOrigin, displayed: List<TranslateResult>) {
        recordSuccess { AnalyticsSuccess.translation(currentUtcDate(), origin, displayed) }
    }

    /** Call immediately after actual playback starts, including cached audio. */
    fun reportVoicePlaybackStarted(origin: AnalyticsUsageOrigin) {
        recordSuccess { AnalyticsSuccess.voice(currentUtcDate(), origin) }
    }

    private fun recordSuccess(create: () -> AnalyticsSuccess?) {
        try {
            val success = create() ?: return
            // Only compact facts enter IO; analytics failure must not undo display/playback.
            recordings.trySend(Recording.Success(success, AppLanguageManager.effectiveLanguageTag(context)))
        } catch (_: Exception) {
            FgoLogger.debug(tag, "Analytics v2 delivery fact unavailable; output unaffected")
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

    private fun currentUtcDate(): String = Instant.now().atZone(ZoneOffset.UTC).toLocalDate().toString()

    private companion object {
        private const val ANALYTICS_ENDPOINT = "https://cdn.fgogotran.com/api/app-events"
        private const val REQUEST_TIMEOUT_MS = 3_000L
    }
}
