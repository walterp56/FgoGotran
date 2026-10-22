package com.fgogotran.chapter.install

import android.content.Context
import com.fgogotran.chapter.GameAssetPath
import com.fgogotran.chapter.OriginalAssetReadResult
import com.fgogotran.chapter.remote.NativeStoryPackage
import com.fgogotran.chapter.storage.ChapterAssetPolicy
import com.fgogotran.chapter.ChapterOriginalStorage
import com.fgogotran.chapter.storage.ShizukuChapterInstaller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class BulkInstallResult(
    val installed: Int,
    val total: Int,
    val missing: List<String>,
)

internal object BulkStoryInstaller {
    suspend fun install(
        context: Context,
        storage: ChapterOriginalStorage,
        packages: List<NativeStoryPackage>,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): BulkInstallResult = withContext(Dispatchers.IO) {
        require(packages.isNotEmpty()) { "No packages selected" }
        val installer = ShizukuChapterInstaller(context) { _, _, _ -> error("unused") }
        val backupDir = File(context.noBackupFilesDir, "chapter/bulk-backup").apply { mkdirs() }
        val missing = mutableListOf<String>()
        var installed = 0
        packages.forEach { pkg ->
            val filename = ChapterAssetPolicy.requireFilename(pkg.assetFilename)
            val current = runCatching { storage.readOriginal(GameAssetPath.parse(filename)) }.getOrNull()
            if (current !is OriginalAssetReadResult.Read) {
                missing += pkg.logicalBundle.ifBlank { filename }
                withContext(Dispatchers.Main) { onProgress(installed, packages.size) }
                return@forEach
            }
            File(backupDir, filename).writeBytes(current.original.bytes.toByteArray())
            installer.writeAsset(filename, pkg.bundle)
            installed++
            withContext(Dispatchers.Main) { onProgress(installed, packages.size) }
        }
        BulkInstallResult(installed, packages.size, missing)
    }
}