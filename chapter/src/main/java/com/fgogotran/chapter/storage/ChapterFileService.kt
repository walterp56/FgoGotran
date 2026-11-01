package com.fgogotran.chapter.storage

import android.content.Context
import android.os.Binder
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import androidx.annotation.Keep
import java.io.File
import java.security.MessageDigest

/** Not an Android Service: Shizuku constructs this Binder in its own non-daemon process. */
@Keep
class ChapterFileService @Keep constructor(context: Context) : IChapterFileService.Stub() {
    private val ownerUid = context.applicationInfo.uid

    private fun enforceReader() {
        check(Os.getuid() == 2000) { "Only the non-root Shizuku shell route is supported" }
        check(Binder.getCallingUid() == ownerUid && ownerUid / 100000 == 0) { "Caller is not the owner-profile client" }
    }

    override fun getServiceUid(): Int { enforceReader(); verifiedRoot(); return Os.getuid() }

    private fun verifiedRoot(): File = File(ChapterAssetPolicy.ROOT).also { root ->
        check(root.canonicalPath == ChapterAssetPolicy.ROOT && root.isDirectory) { "JP FGO asset root is unavailable or unverified" }
    }

    override fun openOriginal(filename: String): ParcelFileDescriptor {
        enforceReader()
        val root = verifiedRoot()
        val target = ChapterAssetPolicy.resolve(root, filename)
        val fd = Os.open(target.path, OsConstants.O_RDONLY or OsConstants.O_CLOEXEC or OsConstants.O_NOFOLLOW, 0)
        try {
            val stat = Os.fstat(fd)
            check(OsConstants.S_ISREG(stat.st_mode) && stat.st_size in 0..ChapterAssetPolicy.MAX_BYTES.toLong()) { "Asset is not a bounded regular file" }
            return ParcelFileDescriptor.dup(fd)
        } finally {
            Os.close(fd)
        }
    }

    override fun fingerprint(filename: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        ParcelFileDescriptor.AutoCloseInputStream(openOriginal(filename)).use { stream ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                size += count
                check(size <= ChapterAssetPolicy.MAX_BYTES) { "Asset grew during read" }
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) } + ":$size"
    }

    override fun destroy() {
        val caller = Binder.getCallingUid()
        check(caller == ownerUid || caller == 2000 || caller == 0)
        kotlin.system.exitProcess(0) // This is the dedicated Chapter shell process, never the app.
    }
}
