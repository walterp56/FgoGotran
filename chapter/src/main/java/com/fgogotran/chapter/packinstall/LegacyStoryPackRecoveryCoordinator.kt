package com.fgogotran.chapter.packinstall

import android.app.Application
import android.content.Context
import android.net.Uri
import com.fgogotran.chapter.install.*
import com.fgogotran.chapter.storage.ShizukuChapterInstaller
import java.io.File
import kotlinx.coroutines.*

/** Recovery for existing source-pack journals only. Never creates a new source-pack installation. */
class LegacyStoryPackRecoveryCoordinator private constructor(private val application: Application) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val base = application.noBackupFilesDir.canonicalFile
    private val store = StoryPackOperationStore(File(base, "chapter-language-pack-operations-v1"), AndroidChapterDurableFiles())
    private val session = StoryPackRecoverySession(store, ShizukuChapterInstaller(application, store::transferFile))
    val state = session.state
    private val operations = ChapterOperationLauncher(scope) { ChapterAssetTestState(busy = state.value.busy, critical = state.value.critical) }
    fun foreground() = operations.start(reload = true) { session.reload() }
    fun background() = operations.background()
    fun inspect() = operations.start { session.inspect() }
    fun restore(approved: Boolean) = operations.start { session.restore(approved) }
    fun export(uri: Uri) = operations.start {
        session.export({ requireNotNull(application.contentResolver.openOutputStream(uri, "wt")) },
            { requireNotNull(application.contentResolver.openInputStream(uri)) })
    }
    companion object {
        @Volatile private var instance: LegacyStoryPackRecoveryCoordinator? = null
        fun get(context: Context): LegacyStoryPackRecoveryCoordinator = instance ?: synchronized(this) {
            instance ?: LegacyStoryPackRecoveryCoordinator(context.applicationContext as Application).also { instance = it }
        }
    }
}
