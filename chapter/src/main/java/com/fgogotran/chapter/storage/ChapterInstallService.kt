package com.fgogotran.chapter.storage

import android.content.Context
import android.os.Binder
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import androidx.annotation.Keep
import com.fgogotran.chapter.codec.FgoJpFormatProfile
import com.fgogotran.chapter.install.*
import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.FutureTask

@Keep
class ChapterInstallService @Keep constructor(private val context: Context) : IChapterInstallService.Stub() {
    private val ownerUid = context.applicationInfo.uid
    private fun enforceOwner() {
        check(Os.getuid() == 2000 && Binder.getCallingUid() == ownerUid && ownerUid / 100000 == 0) { "Authorised non-root owner-profile client required" }
    }
    private fun status(): ChapterGameStatus {
        val info = context.packageManager.getPackageInfo(FgoJpFormatProfile.GAME_PACKAGE, 0)
        val game = ChapterGameIdentity(requireNotNull(info.versionName), info.longVersionCode, info.lastUpdateTime, requireNotNull(info.applicationInfo).uid)
        // Fixed read-only process-table probe. No shell, caller arguments or arbitrary command API.
        val process = ProcessBuilder("/system/bin/ps", "-A", "-n", "-o", "UID,PID,NAME").redirectErrorStream(true).start()
        val output = FutureTask { readBounded(process.inputStream, 128 * 1024) }
        Thread(output, "chapter-process-probe").apply { isDaemon = true; start() }
        try {
            check(process.waitFor(2, TimeUnit.SECONDS) && process.exitValue() == 0) { "Cannot verify FGO running state" }
            val text = output.get(1, TimeUnit.SECONDS).toString(Charsets.UTF_8)
            return ChapterGameStatus(game, gameStoppedFromProcessTable(text, game.uid))
        } finally { process.destroy(); process.inputStream.close(); output.cancel(true) }
    }
    private fun engine() = ChapterTransactionEngine(AndroidChapterInstallFiles(), ::status)
    override fun environment(): String {
        enforceOwner()
        val status = status()
        return buildJsonObject {
            put("name", status.identity.versionName); put("code", status.identity.versionCode)
            put("updated", status.identity.updatedAt); put("uid", status.identity.uid); put("stopped", status.stopped)
        }.toString()
    }
    override fun inspect(record: String): String { enforceOwner(); return engine().inspect(ChapterInstallRecordCodec.decode(record)).name }
    override fun stage(record: String, bundle: ParcelFileDescriptor, manifest: ParcelFileDescriptor) {
        enforceOwner()
        val bytes = descriptors(bundle, manifest)
        val plan = ChapterInstallRecordCodec.decode(record).also(ChapterInstallPolicy::requireOriginals)
        engine().stage(plan, bytes.first, bytes.second)
    }
    override fun commit(record: String) { enforceOwner(); engine().commit(ChapterInstallRecordCodec.decode(record).also(ChapterInstallPolicy::requireOriginals)) }
    override fun restore(record: String, bundle: ParcelFileDescriptor, manifest: ParcelFileDescriptor) {
        enforceOwner()
        val bytes = descriptors(bundle, manifest)
        val plan = ChapterInstallRecordCodec.decode(record).also(ChapterInstallPolicy::requireOriginals)
        engine().restore(plan, bytes.first, bytes.second)
    }
    private fun descriptors(bundle: ParcelFileDescriptor, manifest: ParcelFileDescriptor): Pair<ByteArray, ByteArray> =
        ParcelFileDescriptor.AutoCloseInputStream(bundle).use { a ->
            ParcelFileDescriptor.AutoCloseInputStream(manifest).use { b ->
                for (fd in listOf(bundle.fileDescriptor, manifest.fileDescriptor)) {
                    val stat = Os.fstat(fd)
                    check(OsConstants.S_ISREG(stat.st_mode) && stat.st_size in 1..ChapterInstallPolicy.MAX_FILE.toLong()) { "Transfer must be a bounded regular file" }
                }
                readBounded(a, ChapterInstallPolicy.MAX_FILE) to readBounded(b, ChapterInstallPolicy.MAX_FILE)
            }
        }
    override fun destroy() {
        check(Binder.getCallingUid() in listOf(ownerUid, 2000, 0))
        kotlin.system.exitProcess(0)
    }
}

private fun readBounded(input: java.io.InputStream, max: Int): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = input.read(buffer); if (count < 0) break
        check(output.size().toLong() + count <= max) { "Input exceeds limit" }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

/** Every write is a confined, fixed-role operation. Live originals are never truncated. */
internal class AndroidChapterInstallFiles : ChapterInstallFiles {
    private fun root(): File = File(ChapterAssetPolicy.ROOT).also {
        check(it.canonicalPath == ChapterAssetPolicy.ROOT && it.isDirectory)
    }
    private fun path(name: String): File {
        ChapterInstallPolicy.filename(name)
        val parent = root()
        return File(parent, name).also { check(it.canonicalFile.parentFile == parent && it.canonicalFile.name == name) { "Unsafe install path" } }
    }
    override fun lock(): Closeable {
        val file = path(".fgogotran-install.lock")
        val fd = Os.open(file.path, OsConstants.O_RDWR or OsConstants.O_CREAT or OsConstants.O_CLOEXEC or OsConstants.O_NOFOLLOW, 384)
        val stream = try {
            check(OsConstants.S_ISREG(Os.fstat(fd).st_mode))
            ParcelFileDescriptor.AutoCloseOutputStream(ParcelFileDescriptor.dup(fd))
        } finally { Os.close(fd) }
        try {
            val lock = stream.channel.tryLock() ?: error("Another installation is running")
            return Closeable { try { lock.release() } finally { stream.close() } }
        } catch (failure: Exception) { stream.close(); throw failure }
    }
    override fun exists(name: String): Boolean {
        val file = path(name)
        return file.exists()
    }
    override fun read(name: String): ByteArray {
        val fd = Os.open(path(name).path, OsConstants.O_RDONLY or OsConstants.O_CLOEXEC or OsConstants.O_NOFOLLOW, 0)
        val pfd = try {
            val stat = Os.fstat(fd)
            check(OsConstants.S_ISREG(stat.st_mode) && stat.st_size in 1..ChapterInstallPolicy.MAX_FILE.toLong())
            ParcelFileDescriptor.dup(fd)
        } finally { Os.close(fd) }
        return ParcelFileDescriptor.AutoCloseInputStream(pfd).use { readBounded(it, ChapterInstallPolicy.MAX_FILE) }
    }
    override fun freeBytes(): Long {
        // Conservative physical space only. Do not reclaim game/cache data to make room.
        val stat = Os.statvfs(root().path)
        check(stat.f_bavail >= 0 && stat.f_frsize > 0)
        return Math.multiplyExact(stat.f_bavail, stat.f_frsize)
    }
    private fun syncDirectory() = AndroidChapterDurableFiles().syncDirectory(root())
    override fun stage(name: String, bytes: ByteArray) {
        require(name.endsWith(".new") || name.endsWith(".restore") || name.endsWith("-probe.a"))
        require(bytes.size in 1..ChapterInstallPolicy.MAX_FILE)
        val file = path(name)
        if (file.exists()) { check(read(name).contentEquals(bytes)) { "Existing staging file differs; recover first" }; return }
        val fd = Os.open(file.path, OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL or OsConstants.O_CLOEXEC or OsConstants.O_NOFOLLOW, 384)
        try {
            check(OsConstants.S_ISREG(Os.fstat(fd).st_mode))
            ParcelFileDescriptor.AutoCloseOutputStream(ParcelFileDescriptor.dup(fd)).use { output -> output.write(bytes); output.fd.sync() }
        } finally { Os.close(fd) }
        syncDirectory()
    }
    override fun replace(stage: String, live: String) {
        require(live in listOf(ChapterInstallPolicy.BUNDLE, ChapterInstallPolicy.MANIFEST))
        val role = if (live == ChapterInstallPolicy.BUNDLE) "bundle" else "manifest"
        require(stage.matches(Regex("\\.fgogotran-[0-9a-f]{32}-$role\\.(new|restore)")))
        val source = path(stage); val target = path(live)
        check(OsConstants.S_ISREG(Os.lstat(source.path).st_mode) && OsConstants.S_ISREG(Os.lstat(target.path).st_mode))
        Os.rename(source.path, target.path)
        syncDirectory()
    }
    override fun verifyReplacementAccess(stage: String, live: String, gameUid: Int) {
        require(live in listOf(ChapterInstallPolicy.BUNDLE, ChapterInstallPolicy.MANIFEST))
        val target = Os.lstat(path(live).path)
        val fd = Os.open(path(stage).path, OsConstants.O_RDONLY or OsConstants.O_CLOEXEC or OsConstants.O_NOFOLLOW, 0)
        try {
            val staged = Os.fstat(fd)
            check(OsConstants.S_ISREG(target.st_mode) && OsConstants.S_ISREG(staged.st_mode))
            // A shell-owned 0600 rename passes byte hashes but makes FGO lose access.
            // Never substitute world-readable/writable modes or elevate to root as a fix.
            check(target.st_uid == gameUid && staged.st_uid == target.st_uid && staged.st_gid == target.st_gid) {
                "This storage backend cannot preserve FGO ownership on rename; originals were not replaced"
            }
            val mode = target.st_mode and 511
            check(mode and 384 == 384) { "FGO original must remain owner-readable and writable" }
            if ((staged.st_mode and 511) != mode) { Os.fchmod(fd, mode); Os.fsync(fd) }
            val actual = Os.fstat(fd)
            check(actual.st_uid == target.st_uid && actual.st_gid == target.st_gid && (actual.st_mode and 511) == mode) {
                "Cannot preserve FGO file access; originals were not replaced"
            }
        } finally { Os.close(fd) }
    }
    override fun deleteStage(name: String) {
        require(name.startsWith(".fgogotran-") && name != ".fgogotran-install.lock")
        val file = path(name)
        if (file.exists()) { check(OsConstants.S_ISREG(Os.lstat(file.path).st_mode)); Os.remove(file.path); syncDirectory() }
    }
    override fun probe(id: String) {
        ChapterInstallPolicy.transactionId(id)
        val a = ".fgogotran-$id-probe.a"; val b = ".fgogotran-$id-probe.b"
        check(!exists(a) && !exists(b)) { "Stale staging probe; review before retry" }
        val bytes = "FgoGotran staging probe $id".toByteArray(Charsets.UTF_8)
        try {
            stage(a, bytes)
            Os.rename(path(a).path, path(b).path); syncDirectory()
            check(read(b).contentEquals(bytes)) { "Staging rename/read-back unsupported" }
        } finally { deleteStage(a); deleteStage(b) }
    }
}
