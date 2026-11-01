package com.fgogotran.chapter.install

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/** JVM-only files. Never points at Android data or calls ADB/network. */
internal class TestChapterDurableFiles : ChapterDurableFiles {
    var failRecordWrite = false
    var failState: ChapterInstallState? = null
    var failAfterState: ChapterInstallState? = null
    override fun read(file: File, maxBytes: Int): ByteArray? = if (!file.exists()) null else file.readBytes().also { check(it.size <= maxBytes) }
    override fun write(file: File, bytes: ByteArray) {
        if (failRecordWrite && file.name == "record.json") throw IOException("journal failure")
        val state = if (file.name == "record.json") ChapterInstallRecordCodec.decode(bytes.toString(Charsets.UTF_8)).state else null
        if (state != null && state == failState) throw IOException("before journal publication $state")
        val pending = File(file.path + ".test-new")
        pending.writeBytes(bytes)
        Files.move(pending.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        if (state != null && state == failAfterState) throw IOException("after journal publication $state")
    }
    override fun syncDirectory(directory: File) = Unit // Simulated semantics, not a physical power-loss claim.
}

internal class TestChapterInstallFiles(private val root: File) : ChapterInstallFiles {
    var available = Long.MAX_VALUE
    var denyStage = false
    var denyReplacementAccess = false
    var unsupportedProbe = false
    var failAfterReplace = 0
    var failBeforeReplace = 0
    var failDuringWrite = 0
    var partialBytes = 1
    var failBeforeTruncate = 0
    var failStagePart: String? = null
    var failAfterStagePart: String? = null
    var onLiveSynced: (String) -> Unit = {}
    val accessOverrides = mutableMapOf<String, ChapterFileIdentity>()
    var replacements = 0
    var lockCount = 0
    val writes = mutableListOf<String>()
    private fun path(name: String) = File(root, ChapterInstallPolicy.filename(name)).also { check(it.canonicalFile.parentFile == root.canonicalFile) }
    override fun lock(): Closeable { lockCount++; return Closeable {} }
    override fun read(name: String): ByteArray = path(name).readBytes()
    override fun exists(name: String) = path(name).exists()
    override fun freeBytes() = available
    override fun probe(id: String) { ChapterInstallPolicy.transactionId(id); if (unsupportedProbe) throw IOException("unsupported sync/rename") }
    override fun stage(name: String, bytes: ByteArray) {
        if (denyStage) throw IOException("permission denied")
        if (failStagePart?.let(name::endsWith) == true) throw IOException("before staging $name")
        writes += name
        val file = path(name)
        if (file.exists()) check(file.readBytes().contentEquals(bytes)) else Files.write(file.toPath(), bytes, StandardOpenOption.CREATE_NEW)
        if (failAfterStagePart?.let(name::endsWith) == true) throw IOException("after staging $name")
    }
    override fun verifyReplacementAccess(stage: String, live: String, gameUid: Int) {
        check(exists(stage) && exists(live) && gameUid == 10075)
        check(!denyReplacementAccess) { "Cannot preserve FGO ownership" }
    }
    override fun identity(name: String): ChapterFileIdentity = accessOverrides[name] ?: run {
        val attributes = Files.readAttributes(path(name).toPath(), BasicFileAttributes::class.java)
        // Windows' JDK may expose no fileKey. This is a model, not Android inode proof.
        val key = attributes.fileKey()?.toString() ?: (path(name).canonicalPath + ":" + attributes.creationTime())
        val inode = com.fgogotran.chapter.Sha256Digest.compute(key.toString().toByteArray()).hex.take(15).toLong(16) + 1
        ChapterFileIdentity(1, inode, 10075, 10075, 432)
    }
    override fun openLive(name: String, gameUid: Int): ChapterLiveFile {
        check(!denyReplacementAccess) { "Cannot write an existing game-owned inode" }
        val expected = identity(name).also { it.requireGameAccess(gameUid) }
        val file = RandomAccessFile(path(name), "rw")
        return object : ChapterLiveFile {
            override val identity = expected
            private var started = false
            override fun verifyIdentity() { check(this@TestChapterInstallFiles.identity(name) == expected) { "Live identity changed" } }
            override fun read(): ByteArray { verifyIdentity(); return path(name).readBytes() }
            override fun write(offset: Int, bytes: ByteArray, count: Int) {
                verifyIdentity()
                if (!started) {
                    started = true; replacements++
                    if (failBeforeReplace == replacements) throw IOException("before replacement $replacements")
                    writes += name
                }
                val length = if (failDuringWrite == replacements) minOf(partialBytes, count) else count
                file.seek(offset.toLong()); file.write(bytes, offset, length)
                if (failDuringWrite == replacements) throw IOException("partial replacement $replacements")
            }
            override fun truncate(size: Int) {
                verifyIdentity()
                if (failBeforeTruncate == replacements) throw IOException("before final truncate")
                file.setLength(size.toLong())
            }
            override fun sync() {
                file.fd.sync(); onLiveSynced(name)
                if (failAfterReplace == replacements) throw IOException("after replacement $replacements")
            }
            override fun close() = file.close()
        }
    }
    override fun deleteStage(name: String) {
        require(name.startsWith(".fgogotran-"))
        Files.deleteIfExists(path(name).toPath())
    }
    fun putOriginals(bundle: ByteArray, manifest: ByteArray) {
        path(ChapterInstallPolicy.BUNDLE).writeBytes(bundle); path(ChapterInstallPolicy.MANIFEST).writeBytes(manifest)
    }
    fun corruptManifest() = path(ChapterInstallPolicy.MANIFEST).writeText("other game data")
}

internal object InstallSamples {
    val game = ChapterGameIdentity("2.139.3", 501, 123456789, 10075)
    val bundle = "original bundle".toByteArray()
    val manifest = "original manifest".toByteArray()
    val replacementBundle = "rebuilt bundle".toByteArray()
    val replacementManifest = "rebuilt manifest".toByteArray()
    fun record() = ChapterInstallRecord("1".repeat(32), game, InstallFileHash.of(bundle), InstallFileHash.of(manifest),
        ChapterInstallState.PREPARED, ChapterRebuildMode.UNCHANGED, InstallFileHash.of(replacementBundle), InstallFileHash.of(replacementManifest), "e".repeat(64))
    fun validate(bundle: ByteArray, manifest: ByteArray) {
        check((bundle.contentEquals(this.bundle) && manifest.contentEquals(this.manifest)) ||
            (bundle.contentEquals(replacementBundle) && manifest.contentEquals(replacementManifest)))
    }
}
