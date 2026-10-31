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
            val liveBytes = current.original.bytes.toByteArray()
            val backup = File(backupDir, filename)
            val proof = File(backupDir, "$filename.sha256")
            val proofOk = backup.exists() && proof.isFile && sha256(backup.readBytes()) == pkg.originalSha256
            if (pkg.originalSha256.isNotBlank() && sha256(liveBytes) == pkg.originalSha256 && !proofOk) {
                backup.writeBytes(liveBytes)
                proof.writeText(pkg.originalSha256)
            }
            installer.writeAsset(filename, pkg.bundle)
            installed++
            withContext(Dispatchers.Main) { onProgress(installed, packages.size) }
        }
        BulkInstallResult(installed, packages.size, missing)
    }
    fun backupDirectory(context: Context): File =
        File(context.noBackupFilesDir, "chapter/bulk-backup").apply { mkdirs() }

    fun hasBackup(context: Context): Boolean {
        val dir = backupDirectory(context)
        return dir.listFiles { file -> file.isFile && file.name.endsWith(".bin") }?.any { file ->
            File(dir, "${file.name}.sha256").isFile
        } == true
    }

    /** Writes every saved JP original back. The official index is never touched. */
    suspend fun restore(
        context: Context,
        storage: ChapterOriginalStorage,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): Int = withContext(Dispatchers.IO) {
        val dir = backupDirectory(context)
        val files = dir.listFiles { file -> file.isFile && file.name.endsWith(".bin") }
            ?.filter { file -> File(dir, "${file.name}.sha256").isFile }
            ?.sortedBy { it.name }
            .orEmpty()
        require(files.isNotEmpty()) { "No original backup found" }
        val installer = ShizukuChapterInstaller(context) { _, _, _ -> error("unused") }
        var restored = 0
        files.forEach { file ->
            val filename = ChapterAssetPolicy.requireFilename(file.name)
            val live = runCatching { storage.readOriginal(GameAssetPath.parse(filename)) }.getOrNull()
            val expected = File(dir, "${file.name}.sha256").readText().trim()
            val bytes = file.readBytes()
            if (live is OriginalAssetReadResult.Read && expected.isNotBlank() && sha256(bytes) == expected) {
                installer.writeAsset(filename, bytes)
                restored++
            }
            withContext(Dispatchers.Main) { onProgress(restored, files.size) }
        }
        restored
    }
}

private fun sha256(bytes: ByteArray): String =
    java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }