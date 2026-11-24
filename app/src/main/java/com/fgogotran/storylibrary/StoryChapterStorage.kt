package com.fgogotran.storylibrary

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * File storage for downloaded chapter databases.
 *
 * One active chapter at a time: `filesDir/story_chapters/<chapter-id>.db`. The
 * switch in settings points at the installed chapter, so a failed download can
 * never replace a working one — the temp file is verified first and the final
 * move is atomic.
 */
@Singleton
class StoryChapterStorage @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun directory(): File = File(context.filesDir, DIRECTORY_NAME).apply { mkdirs() }

    fun chapterFile(chapterId: String): File = File(directory(), "${safeId(chapterId)}.db")

    fun downloadFile(chapterId: String): File = File(directory(), "${safeId(chapterId)}.db.download")

    fun hasChapter(chapterId: String): Boolean {
        val file = chapterFile(chapterId)
        return file.isFile && file.length() > 0L
    }

    fun installedBytes(chapterId: String): Long = chapterFile(chapterId).takeIf(File::isFile)?.length() ?: 0L

    fun deleteChapter(chapterId: String) {
        val file = chapterFile(chapterId)
        if (file.exists() && !file.delete()) {
            throw IOException("Unable to delete chapter database: ${file.absolutePath}")
        }
        deleteSidecars(file)
        val download = downloadFile(chapterId)
        if (download.exists() && !download.delete()) {
            throw IOException("Unable to delete stale download: ${download.absolutePath}")
        }
    }

    fun install(downloaded: File, chapterId: String) {
        val target = chapterFile(chapterId)
        target.parentFile?.mkdirs()
        try {
            Files.move(
                downloaded.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(downloaded.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        deleteSidecars(target)
    }

    fun sha256(file: File): String {
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

    private fun deleteSidecars(dbFile: File) {
        listOf(File("${dbFile.absolutePath}-wal"), File("${dbFile.absolutePath}-shm"))
            .forEach { file -> if (file.exists()) file.delete() }
    }

    private fun safeId(chapterId: String): String {
        require(CHAPTER_ID.matches(chapterId)) { "Invalid chapter id: $chapterId" }
        return chapterId
    }

    companion object {
        private const val DIRECTORY_NAME = "story_chapters"
        private val CHAPTER_ID = Regex("[a-z0-9][a-z0-9-]*")
    }
}
