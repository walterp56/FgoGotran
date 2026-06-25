package com.fgogotran.update

import android.content.Context
import androidx.core.content.pm.PackageInfoCompat
import com.fgogotran.util.FgoLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

data class AppVersionStatus(
    val isChecking: Boolean = false,
    val message: String = "",
    val detail: String = "",
    val isError: Boolean = false
)

data class AppVersionInfo(
    val versionName: String,
    val versionCode: Long
)

sealed class AppVersionCheckResult {
    data class UpdateAvailable(val info: AppVersionInfo) : AppVersionCheckResult()
    object UpToDate : AppVersionCheckResult()
    data class Failed(val message: String) : AppVersionCheckResult()
}

@Singleton
class AppVersionManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val httpClient = HttpClient {
        install(HttpTimeout) {
            connectTimeoutMillis = CONNECT_TIMEOUT_MS
            requestTimeoutMillis = REQUEST_TIMEOUT_MS
            socketTimeoutMillis = REQUEST_TIMEOUT_MS
        }
    }
    private val json = Json { ignoreUnknownKeys = true }
    private val checkInProgress = AtomicBoolean(false)
    private val tag = "AppVersion"

    private val _status = MutableStateFlow(
        AppVersionStatus(
            message = "当前版本 ${currentVersionName()}",
            detail = "手动检查新版本"
        )
    )
    val status: StateFlow<AppVersionStatus> = _status

    fun currentVersionName(): String {
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        return packageInfo.versionName ?: "unknown"
    }

    fun currentVersionCode(): Long {
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        return PackageInfoCompat.getLongVersionCode(packageInfo)
    }

    suspend fun checkNow(): AppVersionCheckResult = withContext(Dispatchers.IO) {
        if (!checkInProgress.compareAndSet(false, true)) {
            return@withContext AppVersionCheckResult.Failed("正在检查版本")
        }
        try {
            _status.value = AppVersionStatus(
                isChecking = true,
                message = "正在检查版本",
                detail = "当前版本 ${currentVersionName()}"
            )
            val manifest = fetchManifest()
            validateManifest(manifest)
            FgoLogger.info(
                tag,
                "Version check: current=${currentVersionName()}(${currentVersionCode()}), " +
                    "latest=${manifest.versionName}(${manifest.versionCode})"
            )

            if (manifest.versionCode > currentVersionCode()) {
                val info = AppVersionInfo(manifest.versionName, manifest.versionCode.toLong())
                _status.value = AppVersionStatus(
                    message = "发现新版本 ${info.versionName}",
                    detail = "当前版本 ${currentVersionName()}"
                )
                AppVersionCheckResult.UpdateAvailable(info)
            } else {
                _status.value = AppVersionStatus(
                    message = "已是最新版本",
                    detail = "当前版本 ${currentVersionName()}"
                )
                AppVersionCheckResult.UpToDate
            }
        } catch (e: Exception) {
            FgoLogger.warn(tag, "Version check failed", e)
            val message = userFacingError(e)
            _status.value = AppVersionStatus(
                message = message,
                detail = "当前版本 ${currentVersionName()}",
                isError = true
            )
            AppVersionCheckResult.Failed(message)
        } finally {
            checkInProgress.set(false)
        }
    }

    private suspend fun fetchManifest(): AppManifest {
        val manifestUrl = cacheBustedUrl(MANIFEST_URL)
        val response = httpClient.get(manifestUrl) {
            header(HttpHeaders.CacheControl, "no-cache, no-store, max-age=0")
            header(HttpHeaders.Pragma, "no-cache")
        }
        val body = response.bodyAsText()
        if (!response.status.isSuccess()) {
            throw IllegalStateException("Manifest HTTP ${response.status.value}: ${body.take(240)}")
        }
        return json.decodeFromString(AppManifest.serializer(), body)
    }

    private fun validateManifest(manifest: AppManifest) {
        require(manifest.manifestVersion == 1) {
            "Unsupported manifest version: ${manifest.manifestVersion}"
        }
        require(manifest.versionName.isNotBlank()) { "Missing version name" }
        require(manifest.versionCode > 0) { "Invalid version code: ${manifest.versionCode}" }
    }

    private fun userFacingError(error: Exception): String {
        val message = error.message.orEmpty()
        return when {
            message.contains("HTTP", ignoreCase = true) -> "暂时无法读取版本信息"
            message.contains("version", ignoreCase = true) -> "版本信息格式不正确"
            message.isBlank() -> "暂时无法检查版本"
            else -> "暂时无法检查版本"
        }
    }

    private fun cacheBustedUrl(url: String): String {
        val separator = if ('?' in url) '&' else '?'
        return "$url${separator}ts=${System.currentTimeMillis()}"
    }

    @Serializable
    private data class AppManifest(
        val manifestVersion: Int,
        val versionName: String,
        val versionCode: Int,
        val releaseDate: String = "",
        val minimumAndroid: String = "",
        val apkUrl: String = "",
        val apkSha256: String = "",
        val apkSize: Long = 0L,
        val changelog: List<String> = emptyList()
    )

    companion object {
        const val DOWNLOAD_PAGE_URL = "https://fgogotran.com/download/"
        private const val MANIFEST_URL = "https://cdn.fgogotran.com/app/android/latest/manifest.json"
        private const val CONNECT_TIMEOUT_MS = 10_000L
        private const val REQUEST_TIMEOUT_MS = 15_000L
    }
}
