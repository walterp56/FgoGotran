package com.fgogotran.update

import android.content.Context
import android.os.Build
import com.fgogotran.util.FgoLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

data class AppUpdateStatus(
    val currentVersionName: String = "",
    val currentVersionCode: Int = 0,
    val isChecking: Boolean = false,
    val message: String = "未检查",
    val latestVersionName: String = "",
    val latestVersionCode: Int = 0,
    val downloadUrl: String = "",
    val releaseNotes: List<String> = emptyList(),
    val hasUpdate: Boolean = false
)

@Singleton
class AppUpdateManager @Inject constructor(
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
    private val tag = "AppUpdate"
    private val currentVersion = currentVersionInfo()
    private val _updateStatus = MutableStateFlow(baseStatus())
    val updateStatus: StateFlow<AppUpdateStatus> = _updateStatus

    suspend fun checkForUpdate() {
        if (_updateStatus.value.isChecking) return

        _updateStatus.value = _updateStatus.value.copy(
            isChecking = true,
            message = "正在检查应用更新"
        )

        try {
            FgoLogger.info(tag, "Checking app update manifest $MANIFEST_URL")
            val manifest = fetchManifest()
            validateManifest(manifest)

            if (manifest.versionCode <= currentVersion.code) {
                _updateStatus.value = baseStatus(
                    message = "已是最新版本",
                    latestVersionName = manifest.versionName,
                    latestVersionCode = manifest.versionCode
                )
                return
            }

            if (manifest.minimumAndroidSdk > Build.VERSION.SDK_INT) {
                _updateStatus.value = baseStatus(
                    message = "新版本不支持当前系统",
                    latestVersionName = manifest.versionName,
                    latestVersionCode = manifest.versionCode,
                    releaseNotes = manifest.releaseNotes
                )
                return
            }

            _updateStatus.value = baseStatus(
                message = "发现新版本",
                latestVersionName = manifest.versionName,
                latestVersionCode = manifest.versionCode,
                downloadUrl = manifest.resolvedDownloadUrl,
                releaseNotes = manifest.releaseNotes,
                hasUpdate = true
            )
        } catch (e: Exception) {
            FgoLogger.warn(tag, "App update check failed", e)
            _updateStatus.value = baseStatus(
                message = "检查失败"
            )
        }
    }

    private suspend fun fetchManifest(): AppUpdateManifest {
        val response = httpClient.get(MANIFEST_URL)
        val body = response.bodyAsText()
        if (!response.status.isSuccess()) {
            throw IllegalStateException("Manifest HTTP ${response.status.value}: ${body.take(240)}")
        }
        return json.decodeFromString(AppUpdateManifest.serializer(), body)
    }

    private fun validateManifest(manifest: AppUpdateManifest) {
        require(manifest.manifestVersion == 1) {
            "Unsupported app manifest version: ${manifest.manifestVersion}"
        }
        require(manifest.appId == context.packageName) {
            "Unexpected app id: ${manifest.appId}"
        }
        require(manifest.versionName.isNotBlank()) { "Missing app versionName" }
        require(manifest.versionCode > 0) { "Invalid app versionCode: ${manifest.versionCode}" }
        require(manifest.minimumAndroidSdk > 0) { "Invalid minimumAndroidSdk: ${manifest.minimumAndroidSdk}" }
        if (manifest.versionCode > currentVersion.code) {
            require(manifest.resolvedDownloadUrl.startsWith("https://")) {
                "Invalid update download URL"
            }
        }
    }

    private fun baseStatus(
        isChecking: Boolean = false,
        message: String = "未检查",
        latestVersionName: String = "",
        latestVersionCode: Int = 0,
        downloadUrl: String = "",
        releaseNotes: List<String> = emptyList(),
        hasUpdate: Boolean = false
    ): AppUpdateStatus {
        return AppUpdateStatus(
            currentVersionName = currentVersion.name,
            currentVersionCode = currentVersion.code,
            isChecking = isChecking,
            message = message,
            latestVersionName = latestVersionName,
            latestVersionCode = latestVersionCode,
            downloadUrl = downloadUrl,
            releaseNotes = releaseNotes,
            hasUpdate = hasUpdate
        )
    }

    private fun currentVersionInfo(): VersionInfo {
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode.toInt()
        } else {
            @Suppress("DEPRECATION")
            packageInfo.versionCode
        }
        return VersionInfo(
            name = packageInfo.versionName ?: "未知",
            code = code
        )
    }

    private data class VersionInfo(
        val name: String,
        val code: Int
    )

    @Serializable
    private data class AppUpdateManifest(
        val manifestVersion: Int,
        val appId: String,
        val versionName: String,
        val versionCode: Int,
        val releaseId: String = "",
        val locale: String = "zh-Hans",
        val releasedAt: String = "",
        val minimumAndroidSdk: Int = 30,
        val forceUpdate: Boolean = false,
        val releaseNotes: List<String> = emptyList(),
        val apkUrl: String = "",
        val downloadPageUrl: String = "",
        val apkSha256: String = "",
        val apkSize: Long = 0L
    ) {
        val resolvedDownloadUrl: String
            get() = downloadPageUrl.ifBlank { apkUrl }
    }

    companion object {
        private const val MANIFEST_URL = "https://cdn.fgogotran.com/app/zh-Hans/latest/manifest.json"
        private const val CONNECT_TIMEOUT_MS = 10_000L
        private const val REQUEST_TIMEOUT_MS = 20_000L
    }
}
