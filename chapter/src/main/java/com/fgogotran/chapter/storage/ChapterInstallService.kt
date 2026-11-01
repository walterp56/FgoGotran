package com.fgogotran.chapter.storage

import android.content.Context
import android.os.Binder
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
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
    override fun inspectKnown(record: String, originalManifest: ParcelFileDescriptor, preparedManifest: ParcelFileDescriptor): String {
        enforceOwner()
        val references = descriptors(originalManifest, preparedManifest)
        val plan = ChapterInstallRecordCodec.decode(record).also(ChapterInstallPolicy::requireOriginals)
        return ChapterTransactionEngine(AndroidChapterInstallFiles(), ::status,
            manifestRecovery = ChapterManifestRecovery(plan, references.first, references.second)).inspect(plan).name
    }
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
    override fun restoreKnown(record: String, bundle: ParcelFileDescriptor, manifest: ParcelFileDescriptor, preparedManifest: ParcelFileDescriptor) {
        enforceOwner()
        // Consume/close every received descriptor even if validation fails.
        ParcelFileDescriptor.AutoCloseInputStream(preparedManifest).use { prepared ->
            val bytes = descriptors(bundle, manifest)
            val stat = Os.fstat(preparedManifest.fileDescriptor)
            check(OsConstants.S_ISREG(stat.st_mode) && stat.st_size in 1..ChapterInstallPolicy.MAX_FILE.toLong())
            val replacement = readBounded(prepared, ChapterInstallPolicy.MAX_FILE)
            val plan = ChapterInstallRecordCodec.decode(record).also(ChapterInstallPolicy::requireOriginals)
            ChapterTransactionEngine(AndroidChapterInstallFiles(), ::status,
                manifestRecovery = ChapterManifestRecovery(plan, bytes.second, replacement)).restore(plan, bytes.first, bytes.second)
        }
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
    override fun writeAsset(filename: String, bytes: ParcelFileDescriptor) {
        enforceOwner()
        val name = ChapterAssetPolicy.requireFilename(filename)
        val data = ParcelFileDescriptor.AutoCloseInputStream(bytes).use { readBounded(it, ChapterAssetPolicy.MAX_BYTES) }
        val target = File(ChapterAssetPolicy.ROOT, name)
        check(target.isFile) { "Target asset is missing" }
        java.io.FileOutputStream(target, false).use { out ->
            out.write(data)
            out.fd.sync()
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

/** Fixed-role operations only. Live game-owned inodes are never renamed/deleted/recreated. */
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
            check(OsConstants.S_ISREG(stat.st_mode) && stat.st_size in 0..ChapterInstallPolicy.MAX_FILE.toLong())
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
        require(name.matches(Regex("\\.fgogotran-[0-9a-f]{32}-(?:(bundle|manifest)\\.(new|restore)(?:\\.(before|intent|done))?|probe\\.a)")))
        require(bytes.size in 1..ChapterInstallPolicy.MAX_FILE)
        val file = path(name)
        if (file.exists()) {
            val fd = Os.open(file.path, OsConstants.O_RDONLY or OsConstants.O_CLOEXEC or OsConstants.O_NOFOLLOW, 0)
            try {
                val stat = Os.fstat(fd)
                check(OsConstants.S_ISREG(stat.st_mode) && stat.st_uid == Os.getuid() && stat.st_mode and 511 == 384) { "Foreign or non-private staging file" }
                check(read(name).contentEquals(bytes)) { "Existing staging file differs; recover first" }
                Os.fsync(fd); syncDirectory()
            } finally { Os.close(fd) }
            return
        }
        val fd = Os.open(file.path, OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL or OsConstants.O_CLOEXEC or OsConstants.O_NOFOLLOW, 384)
        try {
            val stat = Os.fstat(fd)
            check(OsConstants.S_ISREG(stat.st_mode) && stat.st_uid == Os.getuid() && stat.st_mode and 511 == 384) { "Private staging is unsupported" }
            ParcelFileDescriptor.AutoCloseOutputStream(ParcelFileDescriptor.dup(fd)).use { output -> output.write(bytes); output.fd.sync() }
        } finally { Os.close(fd) }
        syncDirectory()
    }
    private fun StructStat.identity(): ChapterFileIdentity {
        check(OsConstants.S_ISREG(st_mode)) { "Live target must be a regular file" }
        return ChapterFileIdentity(st_dev, st_ino, st_uid, st_gid, st_mode and 511)
    }
    override fun identity(name: String): ChapterFileIdentity {
        require(name in listOf(ChapterInstallPolicy.BUNDLE, ChapterInstallPolicy.MANIFEST))
        return Os.lstat(path(name).path).identity()
    }
    override fun openLive(name: String, gameUid: Int): ChapterLiveFile {
        require(name in listOf(ChapterInstallPolicy.BUNDLE, ChapterInstallPolicy.MANIFEST))
        val target = path(name)
        val expected = Os.lstat(target.path).identity().also { it.requireGameAccess(gameUid) }
        // Opening is a capability check, not a write. No CREATE/TRUNCATE fallback is permitted.
        val fd = Os.open(target.path, OsConstants.O_RDWR or OsConstants.O_CLOEXEC or OsConstants.O_NOFOLLOW, 0)
        try {
            check(Os.fstat(fd).identity() == expected)
            return object : ChapterLiveFile {
                override val identity = expected
                override fun verifyIdentity() {
                    check(Os.fstat(fd).identity() == expected && Os.lstat(target.path).identity() == expected) { "Live inode/access changed" }
                }
                override fun read(): ByteArray {
                    verifyIdentity()
                    val size = Os.fstat(fd).st_size.also { check(it in 0..ChapterInstallPolicy.MAX_FILE.toLong()) }
                    val bytes = ByteArray(size.toInt())
                    var offset = 0
                    while (offset < bytes.size) {
                        val count = Os.pread(fd, bytes, offset, bytes.size - offset, offset.toLong())
                        check(count > 0) { "Short live read" }; offset += count
                    }
                    verifyIdentity(); check(Os.fstat(fd).st_size == size) { "Live size changed during read" }
                    return bytes
                }
                override fun write(offset: Int, bytes: ByteArray, count: Int) {
                    require(offset >= 0 && count > 0 && offset.toLong() + count <= bytes.size && bytes.size <= ChapterInstallPolicy.MAX_FILE)
                    var written = 0
                    while (written < count) {
                        verifyIdentity()
                        val length = Os.pwrite(fd, bytes, offset + written, count - written, (offset + written).toLong())
                        check(length > 0) { "Short live write" }; written += length
                    }
                }
                override fun truncate(size: Int) {
                    require(size in 1..ChapterInstallPolicy.MAX_FILE)
                    verifyIdentity(); Os.ftruncate(fd, size.toLong())
                }
                override fun sync() { verifyIdentity(); Os.fsync(fd) }
                override fun close() = Os.close(fd)
            }.also { it.verifyIdentity() }
        } catch (failure: Exception) { Os.close(fd); throw failure }
    }
    override fun verifyReplacementAccess(stage: String, live: String, gameUid: Int) {
        require(live in listOf(ChapterInstallPolicy.BUNDLE, ChapterInstallPolicy.MANIFEST))
        val role = if (live == ChapterInstallPolicy.BUNDLE) "bundle" else "manifest"
        require(stage.matches(Regex("\\.fgogotran-[0-9a-f]{32}-$role\\.(new|restore)")))
        val fd = Os.open(path(stage).path, OsConstants.O_RDONLY or OsConstants.O_CLOEXEC or OsConstants.O_NOFOLLOW, 0)
        try {
            val staged = Os.fstat(fd)
            check(OsConstants.S_ISREG(staged.st_mode) && staged.st_uid == Os.getuid() && staged.st_size in 1..ChapterInstallPolicy.MAX_FILE.toLong())
        } finally { Os.close(fd) }
        // The shell owns only temporary bytes. FGO keeps its existing inode/UID/GID/mode.
        openLive(live, gameUid).use { it.verifyIdentity() }
    }
    override fun deleteStage(name: String) {
        require(name.startsWith(".fgogotran-") && name != ".fgogotran-install.lock")
        val file = path(name)
        if (file.exists()) {
            val stat = Os.lstat(file.path)
            check(OsConstants.S_ISREG(stat.st_mode) && stat.st_uid == Os.getuid()) { "Foreign recovery artifact" }
            Os.remove(file.path); syncDirectory()
        }
    }
    override fun probe(id: String) {
        ChapterInstallPolicy.transactionId(id)
        val a = ".fgogotran-$id-probe.a"; val b = ".fgogotran-$id-probe.b"
        check(!exists(a) && !exists(b)) { "Stale staging probe; review before retry" }
        val bytes = "FgoGotran staging probe $id".toByteArray(Charsets.UTF_8)
        try {
            stage(a, bytes)
            check(read(a).contentEquals(bytes)) { "Staging sync/read-back unsupported" }
        } finally { deleteStage(a); deleteStage(b) }
    }
}
