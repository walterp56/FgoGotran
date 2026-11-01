package com.fgogotran.chapter.packinstall

import com.fgogotran.chapter.install.*
import com.fgogotran.chapter.library.*
import com.fgogotran.chapter.packs.StoryLanguage
import java.io.File
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Independent private schema/root. Never opens, migrates or clears an asset-test journal. */
class StoryPackOperationStore(private val root: File, private val durable: ChapterDurableFiles) : StoryPackSelectionPersistence {
    private fun child(parent: File, name: String) = File(parent, name).also {
        check(it.canonicalFile == it.absoluteFile && it.canonicalFile.parentFile == parent.canonicalFile) { "Unsafe private operation path" }
    }
    private fun job(id: String) = child(root, ChapterInstallPolicy.transactionId(id))
    private fun <T> locked(block: () -> T): T = synchronized(this) {
        check(root.canonicalFile == root.absoluteFile && (root.isDirectory || root.mkdirs()))
        FileChannel.open(child(root, ".lock").toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { channel ->
            val lock = checkNotNull(channel.tryLock()) { "Language-pack state is busy" }
            lock.use { block() }
        }
    }
    private fun desired() = durable.read(child(root, "desired.json"), StoryPackOperationJson.MAX)?.let(StoryPackOperationJson::selection) ?: StoryPackSelection()
    override fun loadSelection() = locked { desired() }
    override fun saveSelection(expected: StoryPackSelection, language: StoryLanguage, archives: Set<String>) = locked {
        check(desired() == expected) { "Saved selection changed; reload first" }
        val next = StoryPackSelection(Math.addExact(expected.revision, 1), language, archives)
        durable.write(child(root, "desired.json"), StoryPackOperationJson.selection(next)); next
    }
    private fun active(): StoryPackOperation? {
        val pointer = durable.read(child(root, "active"), 64)?.toString(Charsets.UTF_8)
        if (pointer == null) {
            check(root.listFiles().orEmpty().none { it.name.matches(Regex("[0-9a-f]{32}")) }) { "Orphan language-pack operation retained; inspect recovery files before continuing" }
            return null
        }
        val operation = StoryPackOperationJson.decode(requireNotNull(durable.read(child(job(pointer), "operation.json"), StoryPackOperationJson.MAX)) { "Operation journal missing; preserve backups" })
        check(operation.transport.id == pointer); return operation
    }
    fun load(): StoryPackOperation? = locked { active() }
    // Conservative physical free space only. Never ask Android to evict game/app caches for this experiment.
    @Suppress("UsableSpace")
    fun create(operation: StoryPackOperation, originalBundle: ByteArray, originalManifest: ByteArray, bundle: ByteArray, manifest: ByteArray): StoryPackOperation = locked {
        check(active()?.transport?.state in listOf(null, ChapterInstallState.RESTORED)) { "Explicitly restore/close the prior operation before preparing a new composition" }
        check(operation.matches(desired())) { "Desired selection changed during compilation" }
        val record = operation.transport
        require(record.state == ChapterInstallState.PREPARED && record.exportSha256 == null)
        record.originalBundle.verify(originalBundle); record.originalManifest.verify(originalManifest)
        requireNotNull(record.replacementBundle).verify(bundle); requireNotNull(record.replacementManifest).verify(manifest)
        check(root.usableSpace >= 3L * (originalBundle.size.toLong() + originalManifest.size + bundle.size + manifest.size) + 1024 * 1024) { "Insufficient private recovery space" }
        val folder = job(record.id); check(folder.mkdir())
        listOf("original-bundle" to originalBundle, "original-manifest" to originalManifest, "prepared-bundle" to bundle, "prepared-manifest" to manifest).forEach { (name, bytes) ->
            val file = child(folder, name)
            FileChannel.open(file.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { channel ->
                val buffer = ByteBuffer.wrap(bytes); while (buffer.hasRemaining()) channel.write(buffer); channel.force(true)
            }
            check(durable.read(file, ChapterInstallPolicy.MAX_FILE)?.contentEquals(bytes) == true)
        }
        durable.syncDirectory(folder)
        durable.write(child(folder, "operation.json"), StoryPackOperationJson.encode(operation))
        durable.syncDirectory(root)
        durable.write(child(root, "active"), record.id.toByteArray()); operation
    }
    fun replace(expected: StoryPackOperation, record: ChapterInstallRecord): StoryPackOperation = locked {
        check(active()?.let { StoryPackOperationJson.encode(it).contentEquals(StoryPackOperationJson.encode(expected)) } == true) { "Operation changed; reload first" }
        val before = expected.transport
        require(before.copy(state = record.state, exportSha256 = record.exportSha256) == record) { "Prepared operation identities and bytes are immutable" }
        val next = expected.withTransport(record)
        durable.write(child(job(record.id), "operation.json"), StoryPackOperationJson.encode(next)); next
    }
    private fun file(record: ChapterInstallRecord, bundle: Boolean, prepared: Boolean): File {
        check(active()?.transport == record)
        val hash = if (prepared) requireNotNull(if (bundle) record.replacementBundle else record.replacementManifest) else if (bundle) record.originalBundle else record.originalManifest
        return child(job(record.id), (if (prepared) "prepared-" else "original-") + if (bundle) "bundle" else "manifest").also {
            hash.verify(requireNotNull(durable.read(it, ChapterInstallPolicy.MAX_FILE)) { "Saved native file missing; preserve recovery data" })
        }
    }
    fun bytes(record: ChapterInstallRecord, bundle: Boolean, prepared: Boolean): ByteArray = locked {
        verifiedBytes(record, bundle, prepared)
    }
    private fun verifiedBytes(record: ChapterInstallRecord, bundle: Boolean, prepared: Boolean): ByteArray {
        val bytes = requireNotNull(durable.read(file(record, bundle, prepared), ChapterInstallPolicy.MAX_FILE))
        val hash = if (prepared) requireNotNull(if (bundle) record.replacementBundle else record.replacementManifest) else if (bundle) record.originalBundle else record.originalManifest
        hash.verify(bytes); return bytes
    }
    fun transferFile(record: ChapterInstallRecord, bundle: Boolean, prepared: Boolean): File = locked { file(record, bundle, prepared) }
    fun export(operation: StoryPackOperation, output: OutputStream) = locked {
        check(active()?.transport == operation.transport)
        ZipOutputStream(output).use { zip ->
            fun add(name: String, bytes: ByteArray) { zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
            add(ChapterInstallPolicy.BUNDLE, verifiedBytes(operation.transport, true, false))
            add(ChapterInstallPolicy.MANIFEST, verifiedBytes(operation.transport, false, false))
            add("operation.json", StoryPackOperationJson.encode(operation))
        }
    }
}
