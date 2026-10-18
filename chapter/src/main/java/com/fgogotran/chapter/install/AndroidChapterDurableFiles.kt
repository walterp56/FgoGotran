package com.fgogotran.chapter.install

import android.system.Os
import android.system.OsConstants
import android.util.AtomicFile
import java.io.ByteArrayOutputStream
import java.io.File

internal class AndroidChapterDurableFiles : ChapterDurableFiles {
    override fun read(file: File, maxBytes: Int): ByteArray? {
        if (!file.exists() && !File(file.path + ".bak").exists()) return null
        return AtomicFile(file).openRead().use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer); if (count < 0) break
                check(output.size().toLong() + count <= maxBytes) { "Saved file exceeds limit" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    }
    override fun write(file: File, bytes: ByteArray) {
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        var finished = false
        try {
            output.write(bytes); output.fd.sync(); atomic.finishWrite(output); finished = true
            syncDirectory(requireNotNull(file.parentFile))
            check(read(file, maxOf(ChapterInstallPolicy.MAX_RECORD, bytes.size))?.contentEquals(bytes) == true) { "Durable record read-back failed" }
        }
        catch (failure: Exception) { if (!finished) atomic.failWrite(output); throw failure }
    }
    override fun syncDirectory(directory: File) {
        val fd = Os.open(directory.path, OsConstants.O_RDONLY or OsConstants.O_CLOEXEC or OsConstants.O_NOFOLLOW, 0)
        try { check(OsConstants.S_ISDIR(Os.fstat(fd).st_mode)); Os.fsync(fd) } finally { Os.close(fd) }
    }
}
