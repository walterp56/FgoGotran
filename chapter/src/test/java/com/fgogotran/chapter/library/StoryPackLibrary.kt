package com.fgogotran.chapter.library

// Developer source-archive library. Never opened by the native installer UI.

import com.fgogotran.chapter.immutableList
import com.fgogotran.chapter.packs.*
import com.fgogotran.chapter.compile.StoryPackCompiler
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.util.UUID

/** Private local source archives only. Never extracts files or touches originals/backups/FGO. */
class StoryPackLibrary(private val root: File) {
    companion object {
        const val MAX_PACKS = 32
        const val MAX_STORED_BYTES = 64L * 1024 * 1024
    }

    private fun directory(name: String): File {
        require(root.absoluteFile == root.canonicalFile) { "Linked library root is unsupported" }
        check(root.isDirectory || root.mkdirs()) { "Cannot create private pack library" }
        val directory = File(root, name)
        require(directory.canonicalFile == directory.absoluteFile && !Files.isSymbolicLink(directory.toPath()))
        check(directory.isDirectory || directory.mkdir())
        return directory
    }

    private fun inventory(checkCancelled: () -> Unit): List<StoryCatalogueEntry> {
        val files = directory("archives").listFiles() ?: error("Cannot read private pack library")
        require(files.size <= MAX_PACKS) { "Too many imported packs" }
        var bytes = 0L
        val entries = files.sortedBy { it.name }.map { file ->
            checkCancelled()
            require(file.name.matches(Regex("[0-9a-f]{64}\\.fgostory\\.zip")) &&
                file.canonicalFile == file.absoluteFile && Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) { "Unexpected private library entry" }
            bytes += file.length()
            require(bytes <= MAX_STORED_BYTES) { "Imported library exceeds local storage budget" }
            val pack = StoryPackArchive.read(file)
            require(file.name == "${pack.archiveSha256}.fgostory.zip") { "Imported pack fingerprint changed" }
            summary(pack)
        }
        StoryCatalogue(entries) // Revisions and chapter/language identities must remain unambiguous.
        checkCancelled()
        return immutableList(entries)
    }

    private fun <T> locked(block: () -> T): T {
        directory("archives")
        return FileChannel.open(File(root, ".library.lock").toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { channel ->
            val lock = try { channel.tryLock() } catch (_: OverlappingFileLockException) { null }
            checkNotNull(lock) { "Private library is busy; retry" }
            lock.use { block() }
        }
    }

    @Synchronized fun list(checkCancelled: () -> Unit = {}): List<StoryCatalogueEntry> = locked { inventory(checkCancelled) }

    /** Copy a user-selected stream under bounds, close it, validate, then publish without overwrite. */
    @Synchronized fun import(open: () -> InputStream, checkCancelled: () -> Unit = {}): StoryCatalogueEntry = locked {
        val entries = inventory(checkCancelled)
        val incoming = directory("incoming")
        val remnants = incoming.listFiles() ?: error("Cannot inspect incomplete imports")
        require(remnants.size < MAX_PACKS && remnants.all { Files.isRegularFile(it.toPath(), LinkOption.NOFOLLOW_LINKS) && it.length() <= StoryPackPolicy.MAX_ARCHIVE }) { "Incomplete import storage needs inspection" }
        val storedBytes = entries.sumOf { it.size.toLong() } + remnants.sumOf { it.length() }
        require(storedBytes <= MAX_STORED_BYTES)
        val temporary = File(incoming, "${UUID.randomUUID()}.part")
        check(temporary.createNewFile())
        try {
            open().use { input ->
                FileOutputStream(temporary).use { output ->
                    val buffer = ByteArray(16384)
                    var total = 0
                    while (true) {
                        checkCancelled()
                        val count = input.read(buffer)
                        if (count < 0) break
                        require(count > 0) { "Document provider returned no data" }
                        require(total.toLong() + count <= StoryPackPolicy.MAX_ARCHIVE && storedBytes + total + count <= MAX_STORED_BYTES) { "Imported pack exceeds size budget" }
                        output.write(buffer, 0, count); total += count
                    }
                    output.fd.sync()
                }
            }
            checkCancelled()
            val pack = StoryPackArchive.read(temporary)
            val entry = summary(pack)
            val previous = entries.singleOrNull { it.packId == entry.packId && it.revision == entry.revision }
            if (previous != null) {
                require(previous == entry) { "Imported revision conflicts with the existing archive; use a new revision" }
                return@locked previous
            }
            require(entries.size < MAX_PACKS) { "Local library is full" }
            StoryCatalogue(entries + entry)
            checkCancelled()
            val target = File(directory("archives"), "${entry.sha256}.fgostory.zip")
            require(!Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS))
            // App-private publication only. This is never the live game-file write protocol.
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            entry
        } finally {
            // Only this invocation's unique temporary file; never sweep stored packs or backups.
            if (temporary.canonicalFile == temporary.absoluteFile && Files.isRegularFile(temporary.toPath(), LinkOption.NOFOLLOW_LINKS))
                Files.deleteIfExists(temporary.toPath())
        }
    }

    @Synchronized fun load(entries: Collection<StoryCatalogueEntry>, checkCancelled: () -> Unit = {}): List<ValidatedStoryPack> = locked {
        require(entries.size in 1..32 && entries.map { it.sha256 }.distinct().size == entries.size)
        require(entries.sumOf { it.size.toLong() } <= 32L * 1024 * 1024) { "Selected packs exceed composition budget" }
        val inventory = inventory(checkCancelled)
        var patchBytes = 0L
        var fields = 0L
        immutableList(entries.map { entry ->
            checkCancelled()
            require(entry in inventory) { "Imported selection changed; reload the library" }
            StoryPackArchive.read(File(directory("archives"), "${entry.sha256}.fgostory.zip"), entry).also { pack ->
                patchBytes += pack.manifest.scripts.sumOf { it.patchSize.toLong() }
                fields += pack.patches.sumOf { it.changes.size.toLong() }
                require(patchBytes <= StoryPackCompiler.MAX_TOTAL_BYTES && fields <= StoryPackCompiler.MAX_FIELDS) { "Selected text patches exceed composition budget" }
            }
        })
    }

    private fun summary(pack: ValidatedStoryPack) = with(pack.manifest) {
        StoryCatalogueEntry(packId, revision, chapterId, language, coverage,
            StoryCatalogueSummary(titles, gameVersions, scripts.size, pack.patches.sumOf { it.changes.size }), pack.archiveSha256, pack.archiveSize)
    }
}
