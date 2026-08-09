package com.fgogotran.voice

import android.content.Context
import com.fgogotran.data.SettingsRepository
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton

data class VoiceDataUpdateStatus(
    val isChecking: Boolean = false,
    val message: String = "",
    val detail: String = "",
    val visible: Boolean = false,
    val isError: Boolean = false
)

@Singleton
class VoiceDataUpdateManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val characterVoiceRepository: CharacterVoiceRepository
) {
    private val httpClient = HttpClient {
        install(HttpTimeout) {
            connectTimeoutMillis = CONNECT_TIMEOUT_MS
            requestTimeoutMillis = REQUEST_TIMEOUT_MS
            socketTimeoutMillis = REQUEST_TIMEOUT_MS
        }
    }
    private val json = Json { ignoreUnknownKeys = true }
    private val tag = "VoiceDataUpdate"
    private val _updateStatus = MutableStateFlow(VoiceDataUpdateStatus())
    val updateStatus: StateFlow<VoiceDataUpdateStatus> = _updateStatus

    suspend fun updateIfNeeded(force: Boolean = false) {
        if (!force && !hasAttemptedUpdate.compareAndSet(false, true)) return
        if (!updateInProgress.compareAndSet(false, true)) {
            _updateStatus.value = _updateStatus.value.copy(isChecking = true)
            return
        }

        var visibleUpdateStarted = false
        try {
            settingsRepository.setVoiceDataLastCheckAt(System.currentTimeMillis())
            _updateStatus.value = VoiceDataUpdateStatus(isChecking = true)

            FgoLogger.info(tag, "Voice data update: checking manifest $MANIFEST_URL")
            val manifest = fetchManifest()
            validateManifest(manifest)

            val installedVersion = settingsRepository.voiceDataContentVersion.first()
            FgoLogger.info(
                tag,
                "Voice data update: manifest version=${manifest.contentVersion}, " +
                    "profiles=${manifest.profileCount}, map=${manifest.nameMapCount}, " +
                    "installed=$installedVersion"
            )

            if (isContentVersionOlder(manifest.contentVersion, installedVersion)) {
                FgoLogger.warn(
                    tag,
                    "Voice data update: ignoring older manifest version=${manifest.contentVersion}, " +
                        "installed=$installedVersion"
                )
                _updateStatus.value = VoiceDataUpdateStatus()
                return
            }

            if (installedFilesMatch(manifest)) {
                settingsRepository.saveVoiceDataUpdateMetadata(
                    contentVersion = manifest.contentVersion,
                    packageSha256 = manifest.packageSha256,
                    profileSha256 = manifest.profileSha256,
                    nameMapSha256 = manifest.nameMapSha256,
                    locale = manifest.locale,
                    updatedAt = System.currentTimeMillis()
                )
                FgoLogger.info(tag, "Voice data update: already current version=${manifest.contentVersion}")
                _updateStatus.value = VoiceDataUpdateStatus()
                return
            }

            visibleUpdateStarted = force
            if (force) {
                _updateStatus.value = visibleStatus(
                    message = "Updating voice data",
                    detail = formatStatusDetail(manifest)
                )
            }

            val packageFile = downloadPackage(manifest)
            validateDownloadedPackage(packageFile, manifest)
            val unpackedDir = unpackPackage(packageFile)
            validateVoiceFiles(unpackedDir, manifest)
            installVoiceFiles(unpackedDir)
            settingsRepository.saveVoiceDataUpdateMetadata(
                contentVersion = manifest.contentVersion,
                packageSha256 = manifest.packageSha256,
                profileSha256 = manifest.profileSha256,
                nameMapSha256 = manifest.nameMapSha256,
                locale = manifest.locale,
                updatedAt = System.currentTimeMillis()
            )
            characterVoiceRepository.reload()
            packageFile.delete()
            FgoLogger.info(
                tag,
                "Voice data update: installed version=${manifest.contentVersion}, " +
                    "profiles=${manifest.profileCount}, map=${manifest.nameMapCount}"
            )
            _updateStatus.value = if (force) {
                visibleStatus(
                    message = "Voice data updated",
                    detail = formatStatusDetail(manifest),
                    isChecking = false
                )
            } else {
                VoiceDataUpdateStatus()
            }
        } catch (e: Exception) {
            FgoLogger.warn(tag, "Voice data update failed; keeping existing voice data", e)
            hasAttemptedUpdate.set(false)
            _updateStatus.value = if (visibleUpdateStarted || _updateStatus.value.visible) {
                visibleStatus(
                    message = "Voice data update failed",
                    detail = e.message.orEmpty().ifBlank { "Unknown error" },
                    isChecking = false,
                    isError = true
                )
            } else {
                VoiceDataUpdateStatus()
            }
        } finally {
            updateInProgress.set(false)
        }
    }

    private suspend fun fetchManifest(): VoiceDataManifest {
        val manifestUrl = cacheBustedUrl(MANIFEST_URL)
        val response = httpClient.get(manifestUrl) {
            header(HttpHeaders.CacheControl, "no-cache, no-store, max-age=0")
            header(HttpHeaders.Pragma, "no-cache")
        }
        val body = response.bodyAsText()
        if (!response.status.isSuccess()) {
            throw IllegalStateException("Manifest HTTP ${response.status.value}: ${body.take(240)}")
        }
        return json.decodeFromString(VoiceDataManifest.serializer(), body)
    }

    private fun validateManifest(manifest: VoiceDataManifest) {
        require(manifest.manifestVersion == 1) {
            "Unsupported voice manifest version: ${manifest.manifestVersion}"
        }
        require(manifest.schemaVersion == SUPPORTED_SCHEMA_VERSION) {
            "Unsupported voice schema version: ${manifest.schemaVersion}"
        }
        require(manifest.locale == "zh") {
            "Unsupported voice locale: ${manifest.locale}"
        }
        require(manifest.contentVersion.isNotBlank()) { "Missing voice content version" }
        require(manifest.packageUrl.startsWith("https://cdn.fgogotran.com/")) {
            "Unexpected voice package URL: ${manifest.packageUrl}"
        }
        require(isSha256(manifest.packageSha256)) { "Invalid voice package SHA-256" }
        require(isSha256(manifest.profileSha256)) { "Invalid profile SHA-256" }
        require(isSha256(manifest.nameMapSha256)) { "Invalid name map SHA-256" }
        require(manifest.packageSize > 0L) { "Invalid voice package size" }
        require(manifest.profileSize > 0L) { "Invalid profile size" }
        require(manifest.nameMapSize > 0L) { "Invalid name map size" }
        require(manifest.profileCount > 0) { "Manifest has no voice profiles" }
        require(manifest.nameMapCount > 0) { "Manifest has no name map rows" }
        require(manifest.profileFile == VoiceDataFiles.PROFILE_FILE) {
            "Unexpected profile file: ${manifest.profileFile}"
        }
        require(manifest.nameMapFile == VoiceDataFiles.NAME_MAP_FILE) {
            "Unexpected name map file: ${manifest.nameMapFile}"
        }
    }

    private fun isSha256(value: String): Boolean {
        return value.matches(Regex("[a-fA-F0-9]{64}"))
    }

    private fun installedFilesMatch(manifest: VoiceDataManifest): Boolean {
        if (!VoiceDataFiles.installedPackageExists(context)) return false
        return runCatching {
            val stats = validateVoiceFiles(VoiceDataFiles.installedDir(context), manifest)
            stats.profileSha256.equals(manifest.profileSha256, ignoreCase = true) &&
                stats.nameMapSha256.equals(manifest.nameMapSha256, ignoreCase = true)
        }.onFailure { e ->
            FgoLogger.warn(tag, "Voice data update: installed file validation failed", e)
        }.getOrDefault(false)
    }

    private suspend fun downloadPackage(manifest: VoiceDataManifest): File {
        val rootDir = VoiceDataFiles.rootDir(context)
        rootDir.mkdirs()
        val tempFile = File(rootDir, TEMP_PACKAGE_NAME)
        if (tempFile.exists() && !tempFile.delete()) {
            throw IllegalStateException("Unable to delete stale voice package: ${tempFile.absolutePath}")
        }

        FgoLogger.info(tag, "Voice data update: downloading ${manifest.packageUrl}")
        val response = httpClient.get(manifest.packageUrl)
        if (!response.status.isSuccess()) {
            val errorBody = response.bodyAsText()
            throw IllegalStateException("Voice package HTTP ${response.status.value}: ${errorBody.take(240)}")
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
        FgoLogger.info(tag, "Voice data update: downloaded ${tempFile.length()} bytes")
        return tempFile
    }

    private fun validateDownloadedPackage(file: File, manifest: VoiceDataManifest) {
        require(file.exists() && file.length() > 0L) { "Downloaded voice package is empty" }
        require(file.length() == manifest.packageSize) {
            "Voice package size mismatch: expected=${manifest.packageSize}, actual=${file.length()}"
        }
        val actualSha = sha256File(file)
        require(actualSha.equals(manifest.packageSha256, ignoreCase = true)) {
            "Voice package SHA mismatch: expected=${manifest.packageSha256}, actual=$actualSha"
        }
    }

    private fun unpackPackage(packageFile: File): File {
        val rootDir = VoiceDataFiles.rootDir(context)
        val targetDir = File(rootDir, "unpack-${System.currentTimeMillis()}")
        deleteRecursivelyIfExists(targetDir)
        targetDir.mkdirs()

        val expected = mutableSetOf(VoiceDataFiles.PROFILE_FILE, VoiceDataFiles.NAME_MAP_FILE)
        ZipInputStream(packageFile.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                try {
                    if (!entry.isDirectory) {
                        val entryName = entry.name.replace('\\', '/')
                        require('/' !in entryName) { "Unexpected zip entry path: $entryName" }
                        require(entryName in expected) { "Unexpected zip entry: $entryName" }
                        val outputFile = File(targetDir, entryName)
                        outputFile.outputStream().buffered().use { output ->
                            zip.copyTo(output)
                        }
                        expected.remove(entryName)
                    }
                } finally {
                    zip.closeEntry()
                }
            }
        }

        require(expected.isEmpty()) {
            "Voice package missing files: ${expected.joinToString()}"
        }
        return targetDir
    }

    private fun validateVoiceFiles(dir: File, manifest: VoiceDataManifest): VoiceFileStats {
        val profileFile = File(dir, VoiceDataFiles.PROFILE_FILE)
        val nameMapFile = File(dir, VoiceDataFiles.NAME_MAP_FILE)
        val profileCount = validateTsvFile(
            file = profileFile,
            expectedHeader = VoiceDataFiles.PROFILE_HEADER,
            expectedMinColumns = VoiceDataFiles.PROFILE_HEADER.size,
            expectedCount = manifest.profileCount,
            validateRow = ::validateProfileRow
        )
        val nameMapCount = validateTsvFile(
            file = nameMapFile,
            expectedHeader = VoiceDataFiles.NAME_MAP_HEADER,
            expectedMinColumns = VoiceDataFiles.NAME_MAP_HEADER.size,
            expectedCount = manifest.nameMapCount,
            validateRow = ::validateNameMapRow
        )
        require(profileFile.length() == manifest.profileSize) {
            "Profile size mismatch: expected=${manifest.profileSize}, actual=${profileFile.length()}"
        }
        require(nameMapFile.length() == manifest.nameMapSize) {
            "Name map size mismatch: expected=${manifest.nameMapSize}, actual=${nameMapFile.length()}"
        }
        val profileSha = sha256File(profileFile)
        val nameMapSha = sha256File(nameMapFile)
        require(profileSha.equals(manifest.profileSha256, ignoreCase = true)) {
            "Profile SHA mismatch: expected=${manifest.profileSha256}, actual=$profileSha"
        }
        require(nameMapSha.equals(manifest.nameMapSha256, ignoreCase = true)) {
            "Name map SHA mismatch: expected=${manifest.nameMapSha256}, actual=$nameMapSha"
        }
        return VoiceFileStats(
            profileCount = profileCount,
            nameMapCount = nameMapCount,
            profileSha256 = profileSha,
            nameMapSha256 = nameMapSha
        )
    }

    private fun validateTsvFile(
        file: File,
        expectedHeader: List<String>,
        expectedMinColumns: Int,
        expectedCount: Int,
        validateRow: (lineNo: Int, columns: List<String>) -> Unit
    ): Int {
        require(file.exists() && file.length() > 0L) { "Missing TSV file: ${file.name}" }
        val rows = file.bufferedReader(Charsets.UTF_8).useLines { lines ->
            val iterator = lines.iterator()
            require(iterator.hasNext()) { "Missing TSV header: ${file.name}" }
            val header = parseTsvLine(iterator.next().removePrefix("\uFEFF"))
            require(header == expectedHeader) {
                "Unexpected TSV header for ${file.name}: ${header.joinToString("|")}"
            }
            var count = 0
            val seenKeys = mutableSetOf<String>()
            while (iterator.hasNext()) {
                val line = iterator.next().trimEnd('\r', '\n')
                if (line.isBlank() || line.startsWith("#")) continue
                val columns = parseTsvLine(line)
                require(columns.size >= expectedMinColumns) {
                    "Too few TSV columns in ${file.name}:$count"
                }
                validateRow(count + 2, columns)
                val key = columns.first().trim()
                require(key !in seenKeys) { "Duplicate key $key in ${file.name}" }
                seenKeys.add(key)
                count += 1
            }
            count
        }
        require(rows == expectedCount) {
            "TSV row count mismatch for ${file.name}: expected=$expectedCount, actual=$rows"
        }
        return rows
    }

    private fun parseTsvLine(line: String): List<String> {
        return line.split('\t')
    }

    private fun validateProfileRow(lineNo: Int, columns: List<String>) {
        require(columns[0].trim().isNotBlank()) { "Blank speaker_id at profile:$lineNo" }
        require(columns[3].trim().isNotBlank()) { "Blank voice name at profile:$lineNo" }
    }

    private fun validateNameMapRow(lineNo: Int, columns: List<String>) {
        require(columns[0].trim().isNotBlank()) { "Blank jp_name at name map:$lineNo" }
        require(!containsKana(columns[1])) {
            "Japanese kana in cn_name_simp at name map:$lineNo"
        }
    }

    private fun containsKana(value: String): Boolean {
        return value.any { char -> char in '\u3040'..'\u30ff' }
    }

    private fun installVoiceFiles(unpackedDir: File) {
        val rootDir = VoiceDataFiles.rootDir(context)
        val installedDir = VoiceDataFiles.installedDir(context)
        val backupDir = File(rootDir, "installed.backup")
        deleteRecursivelyIfExists(backupDir)
        try {
            if (installedDir.exists()) {
                movePath(installedDir, backupDir)
            }
            movePath(unpackedDir, installedDir)
            deleteRecursivelyIfExists(backupDir)
        } catch (e: Exception) {
            if (installedDir.exists()) {
                deleteRecursivelyIfExists(installedDir)
            }
            if (backupDir.exists()) {
                movePath(backupDir, installedDir)
            }
            throw e
        }
    }

    private fun movePath(source: File, target: File) {
        target.parentFile?.mkdirs()
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE
            )
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING
            )
        }
    }

    private fun deleteRecursivelyIfExists(file: File) {
        if (file.exists() && !file.deleteRecursively()) {
            throw IllegalStateException("Unable to delete ${file.absolutePath}")
        }
    }

    private fun formatStatusDetail(manifest: VoiceDataManifest): String {
        return "version=${manifest.contentVersion}, profiles=${manifest.profileCount}, map=${manifest.nameMapCount}"
    }

    private fun visibleStatus(
        message: String,
        detail: String = "",
        isChecking: Boolean = true,
        isError: Boolean = false
    ): VoiceDataUpdateStatus {
        return VoiceDataUpdateStatus(
            isChecking = isChecking,
            message = message,
            detail = detail,
            visible = true,
            isError = isError
        )
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

    private data class VoiceFileStats(
        val profileCount: Int,
        val nameMapCount: Int,
        val profileSha256: String,
        val nameMapSha256: String
    )

    @Serializable
    private data class VoiceDataManifest(
        val manifestVersion: Int,
        val contentVersion: String,
        val schemaVersion: Int,
        val locale: String,
        val generatedAt: String,
        val minimumAppVersion: String,
        val releaseNotes: String,
        val packageUrl: String,
        val packageSha256: String,
        val packageSize: Long,
        val profileFile: String,
        val profileSha256: String,
        val profileSize: Long,
        val profileCount: Int,
        val nameMapFile: String,
        val nameMapSha256: String,
        val nameMapSize: Long,
        val nameMapCount: Int
    )

    private companion object {
        const val MANIFEST_URL = "https://cdn.fgogotran.com/voice/zh/latest/manifest.json"
        const val SUPPORTED_SCHEMA_VERSION = 1
        const val TEMP_PACKAGE_NAME = "voice_data.zip.download"
        const val CONNECT_TIMEOUT_MS = 10_000L
        const val REQUEST_TIMEOUT_MS = 30_000L
        val hasAttemptedUpdate = AtomicBoolean(false)
        val updateInProgress = AtomicBoolean(false)
    }
}
