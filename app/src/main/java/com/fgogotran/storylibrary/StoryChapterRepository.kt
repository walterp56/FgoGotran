package com.fgogotran.storylibrary

import com.fgogotran.data.SettingsRepository
import com.fgogotran.util.FgoLogger
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class StoryCatalogUiState(
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val chapters: List<StoryCatalogChapter> = emptyList(),
    val generatedAt: String = "",
    val error: String = ""
)

enum class StoryInstallPhase { IDLE, DOWNLOADING, VERIFYING, INSTALLING, DONE, FAILED }

data class StoryInstallUiState(
    val chapterId: String = "",
    val phase: StoryInstallPhase = StoryInstallPhase.IDLE,
    val bytesRead: Long = 0L,
    val totalBytes: Long = 0L,
    val contentVersion: String = "",
    val error: String = ""
)

/**
 * Story Library state: the chapter catalog from the CDN, one downloaded chapter
 * database at a time, and the "use the downloaded chapter DB" switch.
 *
 * The app never falls back to the API while a chapter pack is active; that policy
 * lives in [StoryChapterResolver] and `Translator`.
 */
@Singleton
class StoryChapterRepository @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val storage: StoryChapterStorage
) {
    private val httpClient = HttpClient {
        install(HttpTimeout) {
            connectTimeoutMillis = CONNECT_TIMEOUT_MS
            requestTimeoutMillis = REQUEST_TIMEOUT_MS
            socketTimeoutMillis = REQUEST_TIMEOUT_MS
        }
    }
    private val json = Json { ignoreUnknownKeys = true }
    private val catalogMutex = Mutex()
    private val installMutex = Mutex()
    private val databaseLock = Any()

    private val _catalogState = MutableStateFlow(StoryCatalogUiState())
    val catalogState: StateFlow<StoryCatalogUiState> = _catalogState

    private val _installState = MutableStateFlow(StoryInstallUiState())
    val installState: StateFlow<StoryInstallUiState> = _installState

    private var catalogFetchedAt = 0L
    private var cachedDatabase: StoryChapterDatabase? = null
    private var cachedDatabaseKey = ""

    suspend fun refreshCatalog(force: Boolean = false) {
        catalogMutex.withLock {
            val ageMs = System.currentTimeMillis() - catalogFetchedAt
            val current = _catalogState.value
            if (!force && current.loaded && ageMs in 0 until CATALOG_TTL_MS) return
            _catalogState.value = current.copy(loading = true, error = "")
            try {
                val body = withContext(Dispatchers.IO) {
                    val response = httpClient.get(cacheBustedUrl(StoryCatalogRules.CATALOG_URL))
                    if (!response.status.isSuccess()) {
                        throw IllegalStateException("Catalog HTTP ${response.status.value}: ${response.bodyAsText().take(200)}")
                    }
                    response.bodyAsText()
                }
                val document = json.decodeFromString(StoryCatalogDocument.serializer(), body)
                if (document.schemaVersion > StoryCatalogRules.SUPPORTED_SCHEMA_VERSION) {
                    throw IllegalStateException("Catalog schema ${document.schemaVersion} is newer than this app")
                }
                val chapters = document.chapters
                    .filter { it.chapterId.isNotBlank() }
                    .sortedWith(compareByDescending<StoryCatalogChapter> { it.order }.thenBy { it.chapterId })
                _catalogState.value = StoryCatalogUiState(
                    loading = false,
                    loaded = true,
                    chapters = chapters,
                    generatedAt = document.generatedAt,
                    error = ""
                )
                catalogFetchedAt = System.currentTimeMillis()
                FgoLogger.info(tag, "Story catalog loaded: ${chapters.size} chapters, generated=${document.generatedAt}")
            } catch (error: Throwable) {
                FgoLogger.warn(tag, "Story catalog fetch failed", error)
                _catalogState.value = current.copy(
                    loading = false,
                    error = error.message ?: error.javaClass.simpleName
                )
            }
        }
    }

    /** Downloads, verifies, installs, and activates [chapter]. */
    suspend fun downloadAndApply(chapter: StoryCatalogChapter) {
        installMutex.withLock {
            val chapterId = chapter.chapterId
            val dbUrl = chapter.dbUrl
            val sha256 = chapter.dbSha256
            if (dbUrl.isNullOrBlank() || sha256.isNullOrBlank() || chapter.dbSize <= 0L) {
                _installState.value = StoryInstallUiState(
                    chapterId = chapterId,
                    phase = StoryInstallPhase.FAILED,
                    error = "Catalog entry has no downloadable database"
                )
                return
            }
            try {
                storage.deleteChapter(chapterId)
                val temp = storage.downloadFile(chapterId)
                temp.parentFile?.mkdirs()
                _installState.value = StoryInstallUiState(
                    chapterId = chapterId,
                    phase = StoryInstallPhase.DOWNLOADING,
                    totalBytes = chapter.dbSize,
                    contentVersion = chapter.contentVersion.orEmpty()
                )
                download(dbUrl, temp, chapter.dbSize)

                _installState.value = _installState.value.copy(phase = StoryInstallPhase.VERIFYING)
                val actualSha = storage.sha256(temp)
                check(actualSha.equals(sha256, ignoreCase = true)) {
                    "SHA-256 mismatch: expected=${sha256.take(12)}…, actual=${actualSha.take(12)}…"
                }
                check(temp.length() == chapter.dbSize) {
                    "Size mismatch: expected=${chapter.dbSize}, actual=${temp.length()}"
                }
                val database = StoryChapterDatabase.open(temp, chapterId)
                val version = database.contentVersion.ifBlank { chapter.contentVersion.orEmpty() }
                val entries = database.entryCount
                database.close()
                if (chapter.entryCount > 0) {
                    check(entries == chapter.entryCount) {
                        "Entry count mismatch: catalog=${chapter.entryCount}, database=$entries"
                    }
                }

                _installState.value = _installState.value.copy(phase = StoryInstallPhase.INSTALLING)
                storage.install(temp, chapterId)
                invalidateActiveDatabase()
                settingsRepository.setStoryChapter(chapterId, version, actualSha)
                settingsRepository.setStoryChapterDbEnabled(true)
                _installState.value = StoryInstallUiState(
                    chapterId = chapterId,
                    phase = StoryInstallPhase.DONE,
                    totalBytes = chapter.dbSize,
                    bytesRead = chapter.dbSize,
                    contentVersion = version
                )
                FgoLogger.info(tag, "Story chapter installed: $chapterId version=$version")
            } catch (error: Throwable) {
                runCatching { storage.downloadFile(chapterId).delete() }
                FgoLogger.warn(tag, "Story chapter install failed: $chapterId", error)
                _installState.value = StoryInstallUiState(
                    chapterId = chapterId,
                    phase = StoryInstallPhase.FAILED,
                    error = error.message ?: error.javaClass.simpleName
                )
            }
        }
    }

    /** Activates an already installed chapter without downloading it again. */
    suspend fun applyInstalled(chapter: StoryCatalogChapter) {
        val file = storage.chapterFile(chapter.chapterId)
        val database = runCatching { StoryChapterDatabase.open(file, chapter.chapterId) }.getOrNull()
        if (database == null) {
            _installState.value = StoryInstallUiState(
                chapterId = chapter.chapterId,
                phase = StoryInstallPhase.FAILED,
                error = "Chapter database is not installed"
            )
            return
        }
        val version = database.contentVersion.ifBlank { chapter.contentVersion.orEmpty() }
        database.close()
        invalidateActiveDatabase()
        settingsRepository.setStoryChapter(chapter.chapterId, version, storage.sha256(file))
        settingsRepository.setStoryChapterDbEnabled(true)
        _installState.value = StoryInstallUiState(
            chapterId = chapter.chapterId,
            phase = StoryInstallPhase.DONE,
            bytesRead = file.length(),
            totalBytes = file.length(),
            contentVersion = version
        )
    }

    suspend fun removeChapter(chapterId: String) {
        installMutex.withLock {
            runCatching { storage.deleteChapter(chapterId) }
            invalidateActiveDatabase()
            if (settingsRepository.storyChapterId.first() == chapterId) {
                settingsRepository.clearStoryChapter()
            }
            _installState.value = StoryInstallUiState()
        }
    }

    suspend fun setEnabled(enabled: Boolean) {
        settingsRepository.setStoryChapterDbEnabled(enabled)
    }

    suspend fun isEnabled(): Boolean = settingsRepository.storyChapterDbEnabled.first()

    suspend fun activeChapterId(): String = settingsRepository.storyChapterId.first()

    suspend fun activeVersion(): String = settingsRepository.storyChapterVersion.first()

    suspend fun hasInstalledFile(chapterId: String): Boolean = storage.hasChapter(chapterId)

    /**
     * The database for the active chapter, or null when the switch is off, no
     * chapter is selected, or the installed file is missing or corrupt.
     */
    internal suspend fun activeDatabase(): StoryChapterDatabase? {
        if (!isEnabled()) return null
        val chapterId = activeChapterId()
        if (chapterId.isBlank()) return null
        val version = activeVersion()
        val key = "$chapterId@$version"
        synchronized(databaseLock) {
            val cached = cachedDatabase
            if (cached != null && cachedDatabaseKey == key) return cached
            cached?.close()
            cachedDatabase = null
            cachedDatabaseKey = ""
            val file = storage.chapterFile(chapterId)
            if (!file.isFile || file.length() <= 0L) return null
            val opened = runCatching { StoryChapterDatabase.open(file, chapterId) }
                .onFailure { FgoLogger.warn(tag, "Active chapter database is unusable: $chapterId", it) }
                .getOrNull() ?: return null
            cachedDatabase = opened
            cachedDatabaseKey = key
            return opened
        }
    }

    fun invalidateActiveDatabase() {
        synchronized(databaseLock) {
            cachedDatabase?.close()
            cachedDatabase = null
            cachedDatabaseKey = ""
        }
    }

    private suspend fun download(url: String, target: File, expectedBytes: Long) {
        withContext(Dispatchers.IO) {
            val response = httpClient.get(url)
            if (!response.status.isSuccess()) {
                throw IllegalStateException("HTTP ${response.status.value} for $url")
            }
            val channel = response.bodyAsChannel()
            var written = 0L
            var lastReported = 0L
            target.outputStream().buffered().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = channel.readAvailable(buffer, 0, buffer.size)
                    if (read == -1) break
                    if (read > 0) {
                        output.write(buffer, 0, read)
                        written += read
                        if (written - lastReported >= PROGRESS_STEP_BYTES) {
                            lastReported = written
                            _installState.value = _installState.value.copy(
                                bytesRead = written,
                                totalBytes = if (expectedBytes > 0) expectedBytes else written
                            )
                        }
                    }
                }
            }
            _installState.value = _installState.value.copy(bytesRead = written, totalBytes = expectedBytes.coerceAtLeast(written))
        }
    }

    private fun cacheBustedUrl(url: String): String {
        val separator = if (url.contains('?')) '&' else '?'
        return "$url${separator}t=${System.currentTimeMillis()}"
    }

    companion object {
        private const val tag = "StoryLibrary"
        private const val CONNECT_TIMEOUT_MS = 10_000L
        private const val REQUEST_TIMEOUT_MS = 60_000L
        private const val CATALOG_TTL_MS = 10 * 60 * 1000L
        private const val PROGRESS_STEP_BYTES = 256 * 1024L
    }
}
