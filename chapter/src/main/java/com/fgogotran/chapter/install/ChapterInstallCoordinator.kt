package com.fgogotran.chapter.install

import android.content.Context
import android.app.Application
import android.net.Uri
import com.fgogotran.chapter.ChapterOriginalStorage
import com.fgogotran.chapter.remote.NativeStoryPackage
import com.fgogotran.chapter.storage.ShizukuChapterInstaller
import kotlinx.coroutines.*
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest

/** Lazily created by Chapter entry, never by Application/Hilt. No idle worker or daemon. */
class ChapterInstallCoordinator private constructor(private val application: Application) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val store = ChapterBackupStore(dataDirectory(), AndroidChapterDurableFiles())
    private val session = ChapterInstallSession(store, ShizukuChapterInstaller(application, store))
    val state = session.state
    private val operations = ChapterOperationLauncher(scope) { state.value }
    private fun start(block: suspend () -> Unit) = operations.start(block = block)
    fun foreground() = operations.start(reload = true) { session.reload() }
    fun background() = operations.background()
    fun cancelPreparation() = background()
    fun backup(storage: ChapterOriginalStorage) = start { session.backup(storage) }
    fun prepare(mode: ChapterRebuildMode) = start { session.prepare(mode) }
    fun inspect() = start { session.inspect() }
    fun install(operatorApproved: Boolean) = start { session.install(operatorApproved) }
    fun restore(operatorApproved: Boolean) = start { session.restore(operatorApproved) }

    /** One-click restore used by the main screen; propagates the session error. */
    suspend fun restoreOriginal(operatorApproved: Boolean) {
        session.restore(operatorApproved)
        val result = session.state.value
        check(result.record?.state == ChapterInstallState.RESTORED) {
            result.error ?: "Restore did not complete"
        }
    }
    fun confirmPlayback() = start { session.confirmPlayback() }
    fun export(uri: Uri) = start {
        session.export(
            { requireNotNull(application.contentResolver.openOutputStream(uri, "wt")) { "Cannot create recovery export" } },
            { requireNotNull(application.contentResolver.openInputStream(uri)) { "Cannot verify recovery export" } }
        )
    }

    /**
     * Installs a verified catalogue package. The first install creates the local
     * baseline and an app-private recovery export automatically, then commits the
     * bundle through the normal transaction path.
     */
    suspend fun installRemotePackage(
        storage: ChapterOriginalStorage,
        pkg: NativeStoryPackage,
        expectedVersion: String,
        operatorApproved: Boolean,
    ) {
        check(operatorApproved) { "Installation must be confirmed" }
        var loaded = store.load()
        if (loaded == null) {
            session.backup(storage)
            loaded = store.load() ?: error(session.state.value.error ?: "Could not capture the original assets")
        } else {
            check(loaded.state != ChapterInstallState.INSTALLED) {
                "Restore the original story before installing another language pack"
            }
        }
        if (loaded.exportSha256 == null) {
            val directory = File(application.noBackupFilesDir, "chapter/recovery").apply { mkdirs() }
            val file = File(directory, "recovery-${System.currentTimeMillis()}.bin")
            session.export({ FileOutputStream(file) }, { FileInputStream(file) })
            loaded = requireNotNull(store.load())
        }
        var record: ChapterInstallRecord = loaded
        check(record.game.versionName == expectedVersion) {
            "This pack targets FGO $expectedVersion"
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(pkg.bundle)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        check(digest == pkg.bundleSha256) { "Downloaded bundle failed verification" }
        check(record.state in listOf(ChapterInstallState.BACKED_UP, ChapterInstallState.PREPARED, ChapterInstallState.RESTORED)) {
            "Restore the original story first"
        }
        store.savePrepared(record, ChapterRebuildMode.UNCHANGED, pkg.bundle, store.original(record, false))
        session.reload()
        session.install(true)
        val result = session.state.value
        check(result.record?.state == ChapterInstallState.INSTALLED) {
            result.error ?: "Install did not complete"
        }
    }

    /** Installs every published bundle for one language, keeping all official index rows. */
    suspend fun installRemotePackages(
        storage: ChapterOriginalStorage,
        packages: List<NativeStoryPackage>,
        expectedVersion: String,
        operatorApproved: Boolean,
    ) {
        check(operatorApproved) { "Installation must be confirmed" }
        check(packages.isNotEmpty()) { "No packages selected" }
        var loaded = store.load()
        if (loaded == null) {
            session.backup(storage)
            loaded = store.load() ?: error(session.state.value.error ?: "Could not capture the original assets")
        } else {
            check(loaded.state != ChapterInstallState.INSTALLED) {
                "Restore the original story before installing another language pack"
            }
        }
        if (loaded.exportSha256 == null) {
            val directory = File(application.noBackupFilesDir, "chapter/recovery").apply { mkdirs() }
            val file = File(directory, "recovery-${System.currentTimeMillis()}.bin")
            session.export({ FileOutputStream(file) }, { FileInputStream(file) })
            loaded = requireNotNull(store.load())
        }
        var record: ChapterInstallRecord = loaded
        check(record.game.versionName == expectedVersion) { "This pack targets FGO $expectedVersion" }
        packages.forEachIndexed { index, pkg ->
            val digest = MessageDigest.getInstance("SHA-256").digest(pkg.bundle)
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            check(digest == pkg.bundleSha256) { "Downloaded bundle failed verification" }
            check(record.state in listOf(ChapterInstallState.BACKED_UP, ChapterInstallState.PREPARED, ChapterInstallState.RESTORED)) {
                "Restore the original story first"
            }
            store.savePrepared(record, ChapterRebuildMode.UNCHANGED, pkg.bundle, store.original(record, false))
            session.reload()
            session.install(true)
            val result = session.state.value
            check(result.record?.state == ChapterInstallState.INSTALLED) {
                result.error ?: "Install did not complete for ${pkg.language}"
            }
            record = requireNotNull(store.load())
            if (index < packages.lastIndex) {
                record = store.replace(record, record.copy(state = ChapterInstallState.BACKED_UP))
                session.reload()
            }
        }
    }
    suspend fun restoreLanguage(
        storage: ChapterOriginalStorage,
        onProgress: (Int, Int) -> Unit,
    ): Int = BulkStoryInstaller.restore(application, storage, onProgress)

    fun hasLanguageBackup(): Boolean = BulkStoryInstaller.hasBackup(application)
    suspend fun switchLanguage(
        storage: ChapterOriginalStorage,
        packages: List<NativeStoryPackage>,
        onProgress: (Int, Int) -> Unit,
    ): BulkInstallResult = BulkStoryInstaller.install(application, storage, packages, onProgress)
    private fun dataDirectory(): File {
        val current = File(application.noBackupFilesDir, "chapter/native")
        val legacy = File(application.noBackupFilesDir, "chapter/asset-test")
        if (!current.exists() && legacy.exists()) legacy.copyRecursively(current, overwrite = false)
        return current
    }

    companion object {
        @Volatile private var instance: ChapterInstallCoordinator? = null
        fun get(context: Context): ChapterInstallCoordinator = instance ?: synchronized(this) {
            instance ?: ChapterInstallCoordinator(context.applicationContext as Application).also { instance = it }
        }
    }
}
