package com.fgogotran.terminology

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.fgogotran.data.SettingsRepository
import com.fgogotran.translation.Translator
import com.fgogotran.util.FgoLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

data class DbUpdateStatus(
    val isChecking: Boolean = false,
    val message: String = "空闲",
    val detail: String = ""
)

/**
 * Best-effort CDN terminology DB updater.
 *
 * The Android app keeps translation local. This updater only downloads a full
 * prebuilt DB package from cdn.fgogotran.com, verifies it, and swaps it in.
 */
@Singleton
class GlossaryUpdateManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val termDatabase: TermDatabase,
    private val translator: Translator
) {
    private val httpClient = HttpClient {
        install(HttpTimeout) {
            connectTimeoutMillis = CONNECT_TIMEOUT_MS
            requestTimeoutMillis = REQUEST_TIMEOUT_MS
            socketTimeoutMillis = REQUEST_TIMEOUT_MS
        }
    }
    private val json = Json { ignoreUnknownKeys = true }
    private val tag = "GlossaryUpdate"
    private val _updateStatus = MutableStateFlow(DbUpdateStatus())
    val updateStatus: StateFlow<DbUpdateStatus> = _updateStatus

    companion object {
        private const val MANIFEST_URL = "https://cdn.fgogotran.com/db/zh-Hans/latest/manifest.json"
        private const val SUPPORTED_SCHEMA_VERSION = 1
        private const val TEMP_DB_NAME = "fgo_terms.db.download"
        private const val CONNECT_TIMEOUT_MS = 10_000L
        private const val REQUEST_TIMEOUT_MS = 30_000L
        private val hasAttemptedUpdate = AtomicBoolean(false)
        private val updateInProgress = AtomicBoolean(false)
    }

    suspend fun updateIfNeeded(force: Boolean = false) {
        if (!force && !hasAttemptedUpdate.compareAndSet(false, true)) return
        if (!updateInProgress.compareAndSet(false, true)) {
            _updateStatus.value = DbUpdateStatus(message = "正在更新")
            return
        }

        try {
            val now = System.currentTimeMillis()
            settingsRepository.setDbLastCheckAt(now)
            _updateStatus.value = DbUpdateStatus(isChecking = true, message = "正在检查")
            FgoLogger.info(tag, "DB update: checking manifest $MANIFEST_URL")
            val manifest = fetchManifest()
            validateManifest(manifest)
            FgoLogger.info(
                tag,
                "DB update: manifest version=${manifest.contentVersion}, rows=${manifest.totalCount}, " +
                    "sha=${manifest.dbSha256.take(12)}..., dbUrl=${manifest.dbUrl}"
            )

            val dbFile = TermDatabase.databaseFile(context)
            val installedVersion = settingsRepository.dbContentVersion.first()
            val installedSha = settingsRepository.dbSha256.first()
            val currentSha = dbFile.takeIf { it.exists() && it.length() > 0L }?.let(::sha256File).orEmpty()
            val packageMetadata = TermDatabase.onlinePackageMetadata(context)
            val knownCurrentMetadata = when {
                packageMetadata != null && dbFile.exists() && dbFile.length() > 0L ->
                    KnownDbMetadata(packageMetadata.contentVersion, packageMetadata.sha256)
                installedSha.equals(currentSha, ignoreCase = true) && installedVersion.isNotBlank() ->
                    KnownDbMetadata(installedVersion, installedSha)
                else -> null
            }
            val effectiveInstalledVersion = knownCurrentMetadata?.contentVersion ?: installedVersion
            val effectiveInstalledSha = knownCurrentMetadata?.sha256 ?: installedSha
            FgoLogger.info(
                tag,
                "DB update: local version=$effectiveInstalledVersion, " +
                    "settings=$installedVersion, package=${packageMetadata?.contentVersion.orEmpty()}, " +
                    "currentSha=${currentSha.take(12)}..."
            )

            if (isContentVersionOlder(manifest.contentVersion, effectiveInstalledVersion)) {
                FgoLogger.warn(
                    tag,
                    "DB update: ignoring older manifest version=${manifest.contentVersion}, " +
                        "installed=$effectiveInstalledVersion"
                )
                if (knownCurrentMetadata != null) {
                    settingsRepository.saveDbUpdateMetadata(
                        contentVersion = knownCurrentMetadata.contentVersion,
                        sha256 = knownCurrentMetadata.sha256,
                        locale = manifest.locale,
                        updatedAt = System.currentTimeMillis()
                    )
                }
                _updateStatus.value = DbUpdateStatus(
                    message = "已是最新",
                    detail = "远端版本：${manifest.contentVersion}，本地版本：$effectiveInstalledVersion"
                )
                return
            }

            if (
                effectiveInstalledSha.equals(manifest.dbSha256, ignoreCase = true) &&
                    dbFile.exists() &&
                    dbFile.length() > 0L
            ) {
                settingsRepository.saveDbUpdateMetadata(
                    contentVersion = manifest.contentVersion,
                    sha256 = manifest.dbSha256,
                    locale = manifest.locale,
                    updatedAt = System.currentTimeMillis()
                )
                TermDatabase.markOnlineInstall(
                    context = context,
                    contentVersion = manifest.contentVersion,
                    sha256 = manifest.dbSha256,
                    generatedAt = manifest.generatedAt
                )
                FgoLogger.info(
                    tag,
                    "DB update: already current by metadata sha version=${manifest.contentVersion}"
                )
                _updateStatus.value = DbUpdateStatus(
                    message = "已是最新",
                    detail = formatStatusDetail(manifest)
                )
                return
            }

            if (currentSha.equals(manifest.dbSha256, ignoreCase = true)) {
                settingsRepository.saveDbUpdateMetadata(
                    contentVersion = manifest.contentVersion,
                    sha256 = manifest.dbSha256,
                    locale = manifest.locale,
                    updatedAt = System.currentTimeMillis()
                )
                TermDatabase.markOnlineInstall(
                    context = context,
                    contentVersion = manifest.contentVersion,
                    sha256 = manifest.dbSha256,
                    generatedAt = manifest.generatedAt
                )
                FgoLogger.info(
                    tag,
                    "DB update: already current version=${manifest.contentVersion}, rows=${manifest.totalCount}"
                )
                _updateStatus.value = DbUpdateStatus(
                    message = "已是最新",
                    detail = formatStatusDetail(manifest)
                )
                return
            }

            _updateStatus.value = DbUpdateStatus(
                isChecking = true,
                message = "正在下载",
                detail = "版本：${manifest.contentVersion}"
            )
            FgoLogger.info(
                tag,
                "DB update: installing version=${manifest.contentVersion} over installed=$effectiveInstalledVersion"
            )

            val downloadedDb = downloadDb(manifest)
            validateDownloadedDb(downloadedDb, manifest)
            installDb(downloadedDb, manifest)
            settingsRepository.saveDbUpdateMetadata(
                contentVersion = manifest.contentVersion,
                sha256 = manifest.dbSha256,
                locale = manifest.locale,
                updatedAt = System.currentTimeMillis()
            )
            translator.clearGlossaryCache()
            FgoLogger.info(
                tag,
                "DB update: installed version=${manifest.contentVersion}, rows=${manifest.totalCount}"
            )
            _updateStatus.value = DbUpdateStatus(
                message = "更新完成",
                detail = formatStatusDetail(manifest)
            )
        } catch (e: Exception) {
            FgoLogger.warn(tag, "DB update failed; keeping existing glossary DB", e)
            _updateStatus.value = DbUpdateStatus(
                message = "更新失败",
                detail = userFacingError(e)
            )
        } finally {
            updateInProgress.set(false)
        }
    }

    private suspend fun fetchManifest(): GlossaryDbManifest {
        val manifestUrl = cacheBustedUrl(MANIFEST_URL)
        FgoLogger.info(tag, "DB update: fetching manifest $manifestUrl")
        val response = httpClient.get(manifestUrl) {
            header(HttpHeaders.CacheControl, "no-cache, no-store, max-age=0")
            header(HttpHeaders.Pragma, "no-cache")
        }
        val body = response.bodyAsText()
        if (!response.status.isSuccess()) {
            throw IllegalStateException("Manifest HTTP ${response.status.value}: ${body.take(240)}")
        }
        return json.decodeFromString(GlossaryDbManifest.serializer(), body)
    }

    private fun formatStatusDetail(manifest: GlossaryDbManifest): String {
        return "版本：${manifest.contentVersion}，条目：${manifest.totalCount}"
    }

    private fun userFacingError(error: Exception): String {
        val message = error.message.orEmpty()
        return when {
            message.contains("HTTP", ignoreCase = true) -> "网络请求失败"
            message.contains("SHA", ignoreCase = true) -> "数据库校验失败"
            message.contains("size", ignoreCase = true) -> "数据库大小不一致"
            message.contains("schema", ignoreCase = true) -> "数据库结构不兼容"
            message.contains("locale", ignoreCase = true) -> "数据库语言不兼容"
            message.contains("version", ignoreCase = true) -> "数据库版本不兼容"
            message.isBlank() -> "请稍后重试"
            else -> "请稍后重试"
        }
    }

    private fun cacheBustedUrl(url: String): String {
        val separator = if ('?' in url) '&' else '?'
        return "$url${separator}ts=${System.currentTimeMillis()}"
    }

    private fun isContentVersionOlder(candidate: String, installed: String): Boolean {
        if (candidate.isBlank() || installed.isBlank()) return false
        val candidateParts = parseContentVersion(candidate) ?: return false
        val installedParts = parseContentVersion(installed) ?: return false
        val maxSize = maxOf(candidateParts.size, installedParts.size)
        for (index in 0 until maxSize) {
            val candidatePart = candidateParts.getOrElse(index) { 0 }
            val installedPart = installedParts.getOrElse(index) { 0 }
            if (candidatePart != installedPart) return candidatePart < installedPart
        }
        return false
    }

    private fun parseContentVersion(value: String): List<Int>? {
        val parts = value.split('.')
        if (parts.isEmpty()) return null
        return parts.map { part ->
            part.toIntOrNull() ?: return null
        }
    }

    private fun validateManifest(manifest: GlossaryDbManifest) {
        require(manifest.manifestVersion == 1) {
            "Unsupported manifest version: ${manifest.manifestVersion}"
        }
        require(manifest.schemaVersion == SUPPORTED_SCHEMA_VERSION) {
            "Unsupported DB schema version: ${manifest.schemaVersion}"
        }
        require(manifest.locale == "zh-Hans") {
            "Unsupported DB locale: ${manifest.locale}"
        }
        require(manifest.contentVersion.isNotBlank()) { "Missing DB content version" }
        require(manifest.dbUrl.startsWith("https://cdn.fgogotran.com/")) {
            "Unexpected DB URL: ${manifest.dbUrl}"
        }
        require(manifest.dbSha256.matches(Regex("[a-fA-F0-9]{64}"))) {
            "Invalid DB SHA-256: ${manifest.dbSha256}"
        }
        require(manifest.dbSize > 0L) { "Invalid DB size: ${manifest.dbSize}" }
        require(manifest.characterNameCount > 0) { "Manifest has no character names" }
        require(manifest.termCount > 0) { "Manifest has no terms" }
    }

    private suspend fun downloadDb(manifest: GlossaryDbManifest): File {
        FgoLogger.info(tag, "DB update: downloading ${manifest.dbUrl}")
        _updateStatus.value = DbUpdateStatus(
            isChecking = true,
            message = "正在下载",
            detail = "版本：${manifest.contentVersion}"
        )
        val dbFile = TermDatabase.databaseFile(context)
        val tempFile = File(dbFile.parentFile, TEMP_DB_NAME)
        tempFile.parentFile?.mkdirs()
        if (tempFile.exists() && !tempFile.delete()) {
            throw IllegalStateException("Unable to delete stale download: ${tempFile.absolutePath}")
        }

        val response = httpClient.get(manifest.dbUrl)
        if (!response.status.isSuccess()) {
            val errorBody = response.bodyAsText()
            throw IllegalStateException("DB download HTTP ${response.status.value}: ${errorBody.take(240)}")
        }

        val channel = response.bodyAsChannel()
        tempFile.outputStream().buffered().use { output ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = channel.readAvailable(buffer, 0, buffer.size)
                if (read == -1) break
                if (read > 0) {
                    output.write(buffer, 0, read)
                }
            }
        }
        FgoLogger.info(tag, "DB update: downloaded ${tempFile.length()} bytes")
        return tempFile
    }

    private fun validateDownloadedDb(file: File, manifest: GlossaryDbManifest) {
        _updateStatus.value = DbUpdateStatus(
            isChecking = true,
            message = "正在校验",
            detail = "版本：${manifest.contentVersion}"
        )
        require(file.exists() && file.length() > 0L) { "Downloaded DB is empty" }
        require(file.length() == manifest.dbSize) {
            "DB size mismatch: expected=${manifest.dbSize}, actual=${file.length()}"
        }

        val actualSha = sha256File(file)
        require(actualSha.equals(manifest.dbSha256, ignoreCase = true)) {
            "DB SHA mismatch: expected=${manifest.dbSha256}, actual=$actualSha"
        }

        SQLiteDatabase.openDatabase(
            file.absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY
        ).use { db ->
            val schemaVersion = db.rawQuery("PRAGMA user_version", null).use { cursor ->
                if (cursor.moveToFirst()) cursor.getInt(0) else 0
            }
            require(schemaVersion == manifest.schemaVersion) {
                "DB schema mismatch: expected=${manifest.schemaVersion}, actual=$schemaVersion"
            }
            require(tableExists(db, "character_names")) { "DB missing character_names table" }
            require(tableExists(db, "terms")) { "DB missing terms table" }

            val characterNameCount = countRows(db, "character_names")
            val termCount = countRows(db, "terms")
            require(characterNameCount == manifest.characterNameCount) {
                "character_names count mismatch: expected=${manifest.characterNameCount}, actual=$characterNameCount"
            }
            require(termCount == manifest.termCount) {
                "terms count mismatch: expected=${manifest.termCount}, actual=$termCount"
            }
        }
        FgoLogger.info(tag, "DB update: downloaded DB verified")
    }

    private fun installDb(downloadedDb: File, manifest: GlossaryDbManifest) {
        _updateStatus.value = DbUpdateStatus(
            isChecking = true,
            message = "正在安装",
            detail = "版本：${manifest.contentVersion}"
        )
        val dbFile = TermDatabase.databaseFile(context)
        dbFile.parentFile?.mkdirs()

        FgoLogger.info(tag, "DB update: closing current Room database")
        termDatabase.close()
        deleteSidecars(dbFile)

        try {
            Files.move(
                downloadedDb.toPath(),
                dbFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(
                downloadedDb.toPath(),
                dbFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING
            )
        }

        TermDatabase.markOnlineInstall(
            context = context,
            contentVersion = manifest.contentVersion,
            sha256 = manifest.dbSha256,
            generatedAt = manifest.generatedAt
        )
        termDatabase.openHelper.readableDatabase
        FgoLogger.info(tag, "DB update: installed DB at ${dbFile.absolutePath}")
    }

    private fun tableExists(db: SQLiteDatabase, tableName: String): Boolean {
        db.rawQuery(
            "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name=?",
            arrayOf(tableName)
        ).use { cursor ->
            return cursor.moveToFirst() && cursor.getInt(0) > 0
        }
    }

    private fun countRows(db: SQLiteDatabase, tableName: String): Int {
        db.rawQuery("SELECT COUNT(*) FROM $tableName", null).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    private fun deleteSidecars(dbFile: File) {
        listOf(
            File("${dbFile.absolutePath}-wal"),
            File("${dbFile.absolutePath}-shm")
        ).forEach { file ->
            if (file.exists() && !file.delete()) {
                throw IllegalStateException("Unable to delete DB sidecar: ${file.absolutePath}")
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

    private data class KnownDbMetadata(
        val contentVersion: String,
        val sha256: String
    )

    @Serializable
    private data class GlossaryDbManifest(
        val manifestVersion: Int,
        val contentVersion: String,
        val schemaVersion: Int,
        val locale: String,
        val generatedAt: String,
        val minimumAppVersion: String,
        val releaseNotes: String,
        val dbUrl: String,
        val dbSha256: String,
        val dbSize: Long,
        val characterNameCount: Int,
        val termCount: Int,
        val totalCount: Int
    )
}
