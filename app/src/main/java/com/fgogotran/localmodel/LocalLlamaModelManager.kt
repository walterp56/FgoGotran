package com.fgogotran.localmodel

import android.content.Context
import android.net.Uri
import com.fgogotran.data.SettingsRepository
import com.fgogotran.util.FgoLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

data class LocalLlamaModelSpec(
    val id: String,
    val displayName: String,
    val description: String,
    val version: String,
    val downloadUrls: List<String>,
    val sha256: String,
    val sizeBytes: Long,
    val recommendedRamGb: Int,
    val contextSize: Int,
    val maxTokens: Int
)

data class LocalLlamaInstalledModel(
    val id: String,
    val version: String,
    val filePath: String,
    val sha256: String,
    val sizeBytes: Long
)

data class LocalLlamaModelStatus(
    val isBusy: Boolean = false,
    val message: String = "未同步模型清单",
    val detail: String = "",
    val availableModels: List<LocalLlamaModelSpec> = LocalLlamaModelManager.BUILT_IN_MODELS
)

@Singleton
class LocalLlamaModelManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository
) {
    private val httpClient = HttpClient()
    private val json = Json { ignoreUnknownKeys = true }
    private val tag = "LocalLlamaModel"
    private val _modelStatus = MutableStateFlow(LocalLlamaModelStatus())
    val modelStatus: StateFlow<LocalLlamaModelStatus> = _modelStatus

    companion object {
        private const val MANIFEST_URL = "https://cdn.fgogotran.com/models/zh-Hans/llama/latest/manifest.json"
        private const val SUPPORTED_MANIFEST_VERSION = 1
        private const val SUPPORTED_LOCALE = "zh-Hans"
        private const val MAX_DOWNLOAD_REDIRECTS = 5
        private val GGUF_MAGIC = byteArrayOf(0x47, 0x47, 0x55, 0x46)

        val BUILT_IN_MODELS = listOf(
            LocalLlamaModelSpec(
                id = "qwen2.5-0.5b-instruct-q4",
                displayName = "轻量：Qwen 0.5B",
                description = "体积小，适合先测试手机本地翻译速度",
                version = "",
                downloadUrls = emptyList(),
                sha256 = "",
                sizeBytes = 0L,
                recommendedRamGb = 4,
                contextSize = 1024,
                maxTokens = 160
            ),
            LocalLlamaModelSpec(
                id = "qwen2.5-1.5b-instruct-q4",
                displayName = "均衡：Qwen 1.5B",
                description = "质量和速度比较均衡，适合多数新手机",
                version = "",
                downloadUrls = emptyList(),
                sha256 = "",
                sizeBytes = 0L,
                recommendedRamGb = 6,
                contextSize = 1024,
                maxTokens = 180
            ),
            LocalLlamaModelSpec(
                id = "qwen2.5-3b-instruct-q4",
                displayName = "较高质量：Qwen 3B",
                description = "翻译质量更好，但下载更大，速度和内存压力更高",
                version = "",
                downloadUrls = emptyList(),
                sha256 = "",
                sizeBytes = 0L,
                recommendedRamGb = 8,
                contextSize = 1024,
                maxTokens = 200
            )
        )
    }

    suspend fun refreshAvailableModels() {
        if (_modelStatus.value.isBusy) return
        _modelStatus.value = _modelStatus.value.copy(isBusy = true, message = "正在同步模型清单", detail = "")
        try {
            val manifest = fetchManifest()
            val models = parseValidModels(manifest)
            _modelStatus.value = LocalLlamaModelStatus(
                message = "模型清单已同步：${models.size} 个模型",
                detail = manifest.generatedAt,
                availableModels = models
            )
            FgoLogger.info(tag, "Local llama manifest loaded: models=${models.size}")
        } catch (e: Exception) {
            FgoLogger.warn(tag, "Local llama manifest sync failed; using built-in placeholders", e)
            _modelStatus.value = LocalLlamaModelStatus(
                message = "模型清单同步失败",
                detail = e.message.orEmpty(),
                availableModels = BUILT_IN_MODELS
            )
        }
    }

    suspend fun downloadModel(modelId: String) {
        if (_modelStatus.value.isBusy) return
        val model = requireModel(modelId)
        try {
            require(model.downloadUrls.isNotEmpty() && model.sha256.isNotBlank() && model.sizeBytes > 0L) {
                "模型清单没有可下载文件，请先上传 llama 模型 manifest"
            }
            _modelStatus.value = _modelStatus.value.copy(
                isBusy = true,
                message = "正在下载模型",
                detail = model.displayName
            )
            withContext(Dispatchers.IO) {
                validateModelSpec(model)
                val tempFile = downloadToTempFile(model)
                validateDownloadedModel(tempFile, model)
                val installedFile = installModel(tempFile, model)
                settingsRepository.saveLocalLlamaModelInstall(
                    modelId = model.id,
                    version = model.version,
                    filePath = installedFile.absolutePath,
                    sha256 = model.sha256,
                    sizeBytes = model.sizeBytes
                )
                deleteOtherModels(installedFile)
            }
            _modelStatus.value = _modelStatus.value.copy(
                isBusy = false,
                message = "模型下载完成",
                detail = model.displayName
            )
        } catch (e: Exception) {
            FgoLogger.warn(tag, "Local llama model download failed", e)
            _modelStatus.value = _modelStatus.value.copy(
                isBusy = false,
                message = "模型下载失败",
                detail = e.message.orEmpty()
            )
        }
    }

    suspend fun importModelFromUri(modelId: String, sourceUri: Uri) {
        if (_modelStatus.value.isBusy) return
        val model = requireModel(modelId)
        _modelStatus.value = _modelStatus.value.copy(
            isBusy = true,
            message = "正在导入模型",
            detail = model.displayName
        )
        try {
            withContext(Dispatchers.IO) {
                val tempFile = copyUriToTempFile(sourceUri, model)
                validateImportedModel(tempFile, model)
                val actualSha = sha256File(tempFile)
                val installedFile = installModel(tempFile, model)
                settingsRepository.saveLocalLlamaModelInstall(
                    modelId = model.id,
                    version = model.version.ifBlank { "local-import" },
                    filePath = installedFile.absolutePath,
                    sha256 = actualSha,
                    sizeBytes = installedFile.length()
                )
                deleteOtherModels(installedFile)
            }
            _modelStatus.value = _modelStatus.value.copy(
                isBusy = false,
                message = "模型导入完成",
                detail = model.displayName
            )
        } catch (e: Exception) {
            FgoLogger.warn(tag, "Local llama model import failed", e)
            _modelStatus.value = _modelStatus.value.copy(
                isBusy = false,
                message = "模型导入失败",
                detail = e.message.orEmpty()
            )
        }
    }

    suspend fun deleteInstalledModel() {
        if (_modelStatus.value.isBusy) return
        _modelStatus.value = _modelStatus.value.copy(isBusy = true, message = "正在删除模型", detail = "")
        try {
            withContext(Dispatchers.IO) {
                val installed = installedModel()
                installed?.filePath
                    ?.takeIf { it.isNotBlank() }
                    ?.let(::File)
                    ?.takeIf { it.exists() }
                    ?.delete()
                settingsRepository.clearLocalLlamaModelInstall()
            }
            _modelStatus.value = _modelStatus.value.copy(
                isBusy = false,
                message = "模型已删除",
                detail = ""
            )
        } catch (e: Exception) {
            FgoLogger.warn(tag, "Local llama model delete failed", e)
            _modelStatus.value = _modelStatus.value.copy(
                isBusy = false,
                message = "模型删除失败",
                detail = e.message.orEmpty()
            )
        }
    }

    suspend fun installedModel(): LocalLlamaInstalledModel? {
        val id = settingsRepository.localLlamaModelId.first()
        val path = settingsRepository.localLlamaModelPath.first()
        if (id.isBlank() || path.isBlank()) return null
        val file = File(path)
        if (!file.exists() || file.length() <= 0L) return null
        return LocalLlamaInstalledModel(
            id = id,
            version = settingsRepository.localLlamaModelVersion.first(),
            filePath = path,
            sha256 = settingsRepository.localLlamaModelSha256.first(),
            sizeBytes = settingsRepository.localLlamaModelSize.first()
        )
    }

    fun modelSpec(modelId: String): LocalLlamaModelSpec? {
        return _modelStatus.value.availableModels.firstOrNull { it.id == modelId }
            ?: BUILT_IN_MODELS.firstOrNull { it.id == modelId }
    }

    private suspend fun fetchManifest(): LocalLlamaManifest {
        val body = httpClient.get(MANIFEST_URL).body<String>()
        return json.decodeFromString(LocalLlamaManifest.serializer(), body)
    }

    private fun validateManifest(manifest: LocalLlamaManifest) {
        require(manifest.manifestVersion == SUPPORTED_MANIFEST_VERSION) {
            "不支持的模型清单版本：${manifest.manifestVersion}"
        }
        require(manifest.locale == SUPPORTED_LOCALE) {
            "不支持的模型语言：${manifest.locale}"
        }
        require(manifest.models.isNotEmpty()) { "模型清单为空" }
    }

    private fun parseValidModels(manifest: LocalLlamaManifest): List<LocalLlamaModelSpec> {
        validateManifest(manifest)
        val validModels = manifest.models.mapNotNull { manifestModel ->
            runCatching {
                manifestModel.toSpec().also(::validateModelSpec)
            }.onFailure { error ->
                FgoLogger.warn(
                    tag,
                    "Ignoring invalid local llama manifest model id=${manifestModel.id}: ${error.message}"
                )
            }.getOrNull()
        }
        require(validModels.isNotEmpty()) { "模型清单没有可用模型" }
        return validModels
    }

    private fun validateModelSpec(model: LocalLlamaModelSpec) {
        require(model.id.isNotBlank()) { "模型 ID 为空" }
        require(model.displayName.isNotBlank()) { "模型名称为空：${model.id}" }
        require(model.version.isNotBlank()) { "模型版本为空：${model.id}" }
        require(model.downloadUrls.isNotEmpty()) { "模型下载地址为空：${model.id}" }
        model.downloadUrls.forEach(::validateDownloadUrl)
        require(model.sha256.matches(Regex("[a-fA-F0-9]{64}"))) {
            "模型 SHA-256 无效：${model.id}"
        }
        require(model.sizeBytes > 0L) { "模型大小无效：${model.id}" }
        require(model.contextSize in 512..4096) { "模型上下文大小不合理：${model.contextSize}" }
        require(model.maxTokens in 32..512) { "模型最大输出长度不合理：${model.maxTokens}" }
    }

    private fun requireModel(modelId: String): LocalLlamaModelSpec {
        return modelSpec(modelId) ?: error("未知模型：$modelId")
    }

    private fun modelDirectory(): File {
        return File(context.filesDir, "models/llama").apply { mkdirs() }
    }

    private fun safeModelFileName(model: LocalLlamaModelSpec): String {
        val safeId = model.id.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val safeVersion = model.version.ifBlank { "local-import" }.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return "$safeId-$safeVersion.gguf"
    }

    private fun downloadToTempFile(model: LocalLlamaModelSpec): File {
        val tempFile = File(modelDirectory(), "${safeModelFileName(model)}.download")
        if (tempFile.exists() && !tempFile.delete()) {
            error("无法删除旧的模型下载临时文件")
        }

        val failures = mutableListOf<String>()
        model.downloadUrls.forEachIndexed { index, downloadUrl ->
            try {
                return downloadUrlToTempFile(
                    model = model,
                    downloadUrl = downloadUrl,
                    mirrorIndex = index + 1,
                    mirrorCount = model.downloadUrls.size,
                    tempFile = tempFile
                )
            } catch (e: Exception) {
                FgoLogger.warn(tag, "Local llama model mirror failed: $downloadUrl", e)
                failures += "${hostLabel(downloadUrl)}: ${e.message.orEmpty()}"
                if (tempFile.exists() && !tempFile.delete()) {
                    FgoLogger.warn(tag, "Unable to delete failed local llama temp file: ${tempFile.absolutePath}")
                }
            }
        }

        error("所有模型下载源都失败：${failures.joinToString("; ")}")
    }

    private fun downloadUrlToTempFile(
        model: LocalLlamaModelSpec,
        downloadUrl: String,
        mirrorIndex: Int,
        mirrorCount: Int,
        tempFile: File
    ): File {
        var url = validateDownloadUrl(downloadUrl)
        FgoLogger.info(tag, "Downloading local llama model mirror $mirrorIndex/$mirrorCount: $downloadUrl")
        var redirectCount = 0
        while (true) {
            val connection = openDownloadConnection(url)
            try {
                val responseCode = connection.responseCode
                if (responseCode in 300..399) {
                    val location = connection.getHeaderField("Location")
                        ?: error("HTTP $responseCode missing redirect location")
                    redirectCount += 1
                    require(redirectCount <= MAX_DOWNLOAD_REDIRECTS) { "下载重定向次数过多" }
                    url = validateDownloadUrl(URL(url, location).toString())
                    continue
                }
                require(responseCode in 200..299) { "HTTP $responseCode" }
                connection.inputStream.use { input ->
                    tempFile.outputStream().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var copied = 0L
                        var lastStatusAt = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            copied += read
                            val now = System.currentTimeMillis()
                            if (now - lastStatusAt > 750L) {
                                lastStatusAt = now
                                _modelStatus.value = _modelStatus.value.copy(
                                    isBusy = true,
                                    message = "正在下载模型",
                                    detail = "来源 $mirrorIndex/$mirrorCount · ${formatBytes(copied)} / ${formatBytes(model.sizeBytes)}"
                                )
                            }
                        }
                    }
                }
                FgoLogger.info(tag, "Downloaded local llama model bytes=${tempFile.length()}")
                return tempFile
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun validateDownloadedModel(file: File, model: LocalLlamaModelSpec) {
        _modelStatus.value = _modelStatus.value.copy(
            isBusy = true,
            message = "正在校验模型",
            detail = model.displayName
        )
        require(file.exists() && file.length() > 0L) { "下载的模型文件为空" }
        require(isGgufFile(file)) { "下载的文件不是 GGUF 模型" }
        require(file.length() == model.sizeBytes) {
            "模型大小不一致：expected=${model.sizeBytes}, actual=${file.length()}"
        }
        val actualSha = sha256File(file)
        require(actualSha.equals(model.sha256, ignoreCase = true)) {
            "模型 SHA-256 不一致"
        }
    }

    private fun validateImportedModel(file: File, model: LocalLlamaModelSpec) {
        _modelStatus.value = _modelStatus.value.copy(
            isBusy = true,
            message = "正在校验模型",
            detail = model.displayName
        )
        require(file.exists() && file.length() > 0L) { "导入的模型文件为空" }
        require(isGgufFile(file)) { "导入的文件不是 GGUF 模型" }
        if (model.sizeBytes > 0L) {
            require(file.length() == model.sizeBytes) {
                "模型大小不一致：expected=${model.sizeBytes}, actual=${file.length()}"
            }
        }
        if (model.sha256.isNotBlank()) {
            val actualSha = sha256File(file)
            require(actualSha.equals(model.sha256, ignoreCase = true)) {
                "模型 SHA-256 不一致"
            }
        }
    }

    private fun copyUriToTempFile(sourceUri: Uri, model: LocalLlamaModelSpec): File {
        val tempFile = File(modelDirectory(), "${safeModelFileName(model)}.import")
        if (tempFile.exists() && !tempFile.delete()) {
            error("无法删除旧的模型导入临时文件")
        }
        context.contentResolver.openInputStream(sourceUri)?.use { input ->
            tempFile.outputStream().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var copied = 0L
                var lastStatusAt = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    copied += read
                    val now = System.currentTimeMillis()
                    if (now - lastStatusAt > 750L) {
                        lastStatusAt = now
                        _modelStatus.value = _modelStatus.value.copy(
                            isBusy = true,
                            message = "正在导入模型",
                            detail = formatBytes(copied)
                        )
                    }
                }
            }
        } ?: throw IOException("无法打开选择的模型文件")
        return tempFile
    }

    private fun installModel(tempFile: File, model: LocalLlamaModelSpec): File {
        _modelStatus.value = _modelStatus.value.copy(
            isBusy = true,
            message = "正在安装模型",
            detail = model.displayName
        )
        val targetFile = File(modelDirectory(), safeModelFileName(model))
        try {
            Files.move(
                tempFile.toPath(),
                targetFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(
                tempFile.toPath(),
                targetFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING
            )
        }
        return targetFile
    }

    private fun deleteOtherModels(keepFile: File) {
        modelDirectory().listFiles()
            ?.filter { it.extension == "gguf" && it.absolutePath != keepFile.absolutePath }
            ?.forEach { stale ->
                if (!stale.delete()) {
                    FgoLogger.warn(tag, "Unable to delete stale local llama model: ${stale.absolutePath}")
                }
            }
    }

    private fun sha256File(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun isGgufFile(file: File): Boolean {
        val header = ByteArray(GGUF_MAGIC.size)
        file.inputStream().use { input ->
            if (input.read(header) != GGUF_MAGIC.size) return false
        }
        return header.contentEquals(GGUF_MAGIC)
    }

    private fun validateDownloadUrl(downloadUrl: String): URL {
        val url = URL(downloadUrl)
        require(url.protocol.equals("https", ignoreCase = true)) {
            "模型下载地址必须使用 HTTPS：$downloadUrl"
        }
        require(url.host.isNotBlank()) { "模型下载地址无效：$downloadUrl" }
        return url
    }

    private fun openDownloadConnection(url: URL): HttpURLConnection {
        return (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 120_000
            requestMethod = "GET"
            instanceFollowRedirects = false
            setRequestProperty("User-Agent", "FgoGotran/1.0")
        }
    }

    private fun hostLabel(downloadUrl: String): String {
        return runCatching { URL(downloadUrl).host }.getOrDefault(downloadUrl)
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) return "未知大小"
        val mib = bytes / 1024.0 / 1024.0
        return if (mib >= 1024.0) {
            "%.2f GB".format(mib / 1024.0)
        } else {
            "%.1f MB".format(mib)
        }
    }

    @Serializable
    private data class LocalLlamaManifest(
        val manifestVersion: Int,
        val locale: String,
        val generatedAt: String = "",
        val models: List<LocalLlamaManifestModel>
    )

    @Serializable
    private data class LocalLlamaManifestModel(
        val id: String = "",
        val displayName: String = "",
        val description: String = "",
        val version: String = "",
        val downloadUrl: String = "",
        val downloadUrls: List<String> = emptyList(),
        val sha256: String = "",
        val sizeBytes: Long = 0L,
        val recommendedRamGb: Int = 6,
        val contextSize: Int = 1024,
        val maxTokens: Int = 180
    ) {
        fun toSpec(): LocalLlamaModelSpec {
            val urls = (downloadUrls + downloadUrl)
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
            return LocalLlamaModelSpec(
                id = id,
                displayName = displayName,
                description = description,
                version = version,
                downloadUrls = urls,
                sha256 = sha256,
                sizeBytes = sizeBytes,
                recommendedRamGb = recommendedRamGb,
                contextSize = contextSize,
                maxTokens = maxTokens
            )
        }
    }
}
