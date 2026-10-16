package com.fgogotran.chapter.install

import java.io.File
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Android supplies AtomicFile; unit tests supply a temporary-directory implementation. */
interface ChapterDurableFiles {
    fun read(file: File, maxBytes: Int): ByteArray?
    fun write(file: File, bytes: ByteArray)
    fun syncDirectory(directory: File)
}

/** Originals are immutable, retained and independent of disposable prepared/staged files. */
class ChapterBackupStore(private val root: File, private val durable: ChapterDurableFiles) {
    private val monitor = Any()
    private fun child(parent: File, name: String): File = File(parent, name).also {
        check(it.canonicalFile.parentFile == parent.canonicalFile && it.canonicalFile.name == name) { "Private backup path is not confined" }
    }
    private fun job(id: String): File = child(root, ChapterInstallPolicy.transactionId(id))
    private fun <T> locked(block: () -> T): T = synchronized(monitor) {
        check(root.isDirectory || root.mkdirs()) { "Cannot create backup directory" }
        FileChannel.open(child(root, "store.lock").toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { channel ->
            channel.lock().use { block() }
        }
    }
    private fun loadUnlocked(): ChapterInstallRecord? {
        val active = durable.read(child(root, "active"), 64)?.toString(Charsets.UTF_8) ?: return null
        val text = requireNotNull(durable.read(child(job(active), "record.json"), ChapterInstallPolicy.MAX_RECORD)) { "Backup journal is missing; do not start a new installation" }
        return ChapterInstallRecordCodec.decode(text.toString(Charsets.UTF_8)).also { check(it.id == active) }
    }
    fun load(): ChapterInstallRecord? = locked { loadUnlocked() }
    fun create(game: ChapterGameIdentity, bundle: ByteArray, manifest: ByteArray): ChapterInstallRecord = locked {
        val previous = loadUnlocked()
        // An exported baseline that was NEVER prepared/staged/installed has no game
        // writes to recover. A game-version update may start a new, separately verified
        // baseline; retain its old folder and originals, never reinterpret them as new.
        check(previous == null || previous.state == ChapterInstallState.RESTORED ||
            (previous.state == ChapterInstallState.BACKED_UP && previous.mode == null &&
                previous.exportSha256 != null && previous.game.versionName != game.versionName)) {
            "Finish or recover the existing operation first"
        }
        val record = ChapterInstallRecord(UUID.randomUUID().toString().replace("-", ""), game, InstallFileHash.of(bundle), InstallFileHash.of(manifest))
        val folder = job(record.id)
        check(folder.mkdir())
        fun original(name: String, bytes: ByteArray) {
            FileChannel.open(child(folder, name).toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            check(requireNotNull(durable.read(child(folder, name), ChapterInstallPolicy.MAX_FILE)).contentEquals(bytes)) { "Backup read-back failed" }
        }
        original("original-bundle", bundle); original("original-manifest", manifest)
        durable.syncDirectory(folder)
        durable.write(child(folder, "record.json"), ChapterInstallRecordCodec.encode(record).toByteArray(Charsets.UTF_8))
        durable.syncDirectory(root)
        durable.write(child(root, "active"), record.id.toByteArray(Charsets.UTF_8))
        record
    }
    fun replace(expected: ChapterInstallRecord, updated: ChapterInstallRecord): ChapterInstallRecord = locked {
        check(loadUnlocked() == expected && expected.id == updated.id) { "Journal changed; reload the operation" }
        require(expected.game == updated.game && expected.originalBundle == updated.originalBundle && expected.originalManifest == updated.originalManifest)
        durable.write(child(job(updated.id), "record.json"), ChapterInstallRecordCodec.encode(updated).toByteArray(Charsets.UTF_8))
        updated
    }
    fun original(record: ChapterInstallRecord, bundle: Boolean): ByteArray = locked {
        readVerified(record, if (bundle) "original-bundle" else "original-manifest", if (bundle) record.originalBundle else record.originalManifest)
    }
    fun prepared(record: ChapterInstallRecord, bundle: Boolean): ByteArray = locked {
        readVerified(record, if (bundle) "prepared-bundle" else "prepared-manifest", requireNotNull(if (bundle) record.replacementBundle else record.replacementManifest))
    }
    private fun readVerified(record: ChapterInstallRecord, name: String, hash: InstallFileHash): ByteArray =
        requireNotNull(durable.read(child(job(record.id), name), ChapterInstallPolicy.MAX_FILE)) { "Saved file is missing" }.also(hash::verify)

    fun savePrepared(expected: ChapterInstallRecord, mode: ChapterRebuildMode, bundle: ByteArray, manifest: ByteArray): ChapterInstallRecord = locked {
        check(loadUnlocked() == expected)
        require(expected.state in listOf(ChapterInstallState.BACKED_UP, ChapterInstallState.PREPARED, ChapterInstallState.RESTORED))
        require(mode != ChapterRebuildMode.MARKER || expected.unchangedVerified)
        val bundleHash = InstallFileHash.of(bundle)
        val manifestHash = InstallFileHash.of(manifest)
        // An old PREPARED journal may survive a crash here. Its saved hashes then fail closed.
        durable.write(child(job(expected.id), "prepared-bundle"), bundle)
        durable.write(child(job(expected.id), "prepared-manifest"), manifest)
        val record = expected.copy(state = ChapterInstallState.PREPARED, mode = mode, replacementBundle = bundleHash,
            replacementManifest = manifestHash, playbackConfirmed = false)
        readVerified(record, "prepared-bundle", requireNotNull(record.replacementBundle))
        readVerified(record, "prepared-manifest", requireNotNull(record.replacementManifest))
        durable.write(child(job(record.id), "record.json"), ChapterInstallRecordCodec.encode(record).toByteArray(Charsets.UTF_8))
        record
    }
    fun export(record: ChapterInstallRecord, output: OutputStream) = locked {
        check(loadUnlocked() == record)
        val bundle = readVerified(record, "original-bundle", record.originalBundle)
        val manifest = readVerified(record, "original-manifest", record.originalManifest)
        ZipOutputStream(output).use { zip ->
            fun entry(name: String, bytes: ByteArray) { zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
            entry(ChapterInstallPolicy.BUNDLE, bundle); entry(ChapterInstallPolicy.MANIFEST, manifest)
            entry("record.json", ChapterInstallRecordCodec.encode(record).toByteArray(Charsets.UTF_8))
        }
    }
    /** Only fixed app-private filenames can be opened for read-only Binder transfer. */
    fun transferFile(record: ChapterInstallRecord, bundle: Boolean, prepared: Boolean): File = locked {
        val name = (if (prepared) "prepared-" else "original-") + if (bundle) "bundle" else "manifest"
        val hash = if (prepared) requireNotNull(if (bundle) record.replacementBundle else record.replacementManifest) else if (bundle) record.originalBundle else record.originalManifest
        readVerified(record, name, hash)
        child(job(record.id), name)
    }
}
