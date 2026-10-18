package com.fgogotran.chapter.install

import android.content.Context
import android.app.Application
import android.net.Uri
import com.fgogotran.chapter.ChapterOriginalStorage
import com.fgogotran.chapter.storage.ShizukuChapterInstaller
import kotlinx.coroutines.*
import java.io.File

/** Lazily created by Chapter entry, never by Application/Hilt. No idle worker or daemon. */
class ChapterInstallCoordinator private constructor(private val application: Application) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val store = ChapterBackupStore(File(application.noBackupFilesDir, "chapter/asset-test"), AndroidChapterDurableFiles())
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
    fun confirmPlayback() = start { session.confirmPlayback() }
    fun export(uri: Uri) = start {
        session.export(
            { requireNotNull(application.contentResolver.openOutputStream(uri, "wt")) { "Cannot create recovery export" } },
            { requireNotNull(application.contentResolver.openInputStream(uri)) { "Cannot verify recovery export" } }
        )
    }
    companion object {
        @Volatile private var instance: ChapterInstallCoordinator? = null
        fun get(context: Context): ChapterInstallCoordinator = instance ?: synchronized(this) {
            instance ?: ChapterInstallCoordinator(context.applicationContext as Application).also { instance = it }
        }
    }
}
