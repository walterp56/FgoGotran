package com.fgogotran.chapter.packs.tools

import com.fgogotran.chapter.packs.*
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Desktop-only publisher. Deliberately in test/tool sources, never packaged into the APK. */
object LocalStoryPackPublisher {
    data class Publication(val archive: File, val catalogue: File, val reused: Boolean)

    fun publish(repository: File, draft: File): Publication {
        val repositoryRoot = repository.absoluteFile.normalize()
        require(File(repositoryRoot, "chapter").isDirectory) { "Chapter repository required" }
        val mappings = confined(repositoryRoot, File(repositoryRoot, "story_assets/mappings"))
        val source = confined(mappings, draft.absoluteFile.normalize(), allowRoot = false)
        require(source.isDirectory) { "Reviewed story draft directory required" }
        val manifestFile = confined(source, File(source, "manifest.json"), false)
        val manifestBytes = bounded(manifestFile)
        val manifest = StoryPackJson.decodeManifest(manifestBytes)
        val members = linkedMapOf("manifest.json" to StoryPackJson.encodeManifest(manifest))
        manifest.scripts.sortedBy { it.scriptId }.forEach { script ->
            val file = confined(source, File(source, script.path), false)
            members[script.path] = bounded(file)
        }
        // Refuse accidental extra source/backups as well as linked draft files/directories.
        val rootFiles = requireNotNull(source.listFiles())
        require(rootFiles.size == 2 && rootFiles.map { it.name }.toSet() == setOf("manifest.json", "patches"))
        val patchFolder = confined(source, File(source, "patches"), false)
        require(patchFolder.isDirectory)
        val patchFiles = requireNotNull(patchFolder.listFiles())
        require(patchFiles.size in 1..StoryPackPolicy.MAX_SCRIPTS)
        val actual = setOf("manifest.json") + patchFiles.map {
            confined(source, it, false); require(it.isFile); "patches/${it.name}"
        }
        require(actual == members.keys) { "Draft has missing or undeclared files" }
        val published = confined(repositoryRoot, File(repositoryRoot, "story_assets/published"))
        Files.createDirectories(published.toPath())
        val lockFile = confined(published, File(published, ".publish.lock"), false)
        FileChannel.open(lockFile.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
            channel.lock().use {
                val destination = confined(published, File(published, StoryPackPolicy.archivePath(manifest.packId, manifest.revision)), false)
                val catalogue = confined(published, File(published, "catalog.json"), false)
                val old = if (catalogue.exists()) StoryPackJson.decodeCatalogue(bounded(catalogue)) else StoryCatalogue(emptyList())
                // Existing published files must remain valid; never repair or rehash corrupt history.
                old.entries.forEach { entry -> StoryPackArchive.read(confined(published, File(published, entry.path), false), entry) }
                val pending = confined(published, File(published, ".partial-${UUID.randomUUID()}.fgostory.zip"), false)
                FileChannel.open(pending.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { output ->
                    ZipOutputStream(java.nio.channels.Channels.newOutputStream(output)).use { zip ->
                        members.forEach { (name, bytes) ->
                            zip.putNextEntry(ZipEntry(name).apply { time = 315532800000L })
                            zip.write(bytes); zip.closeEntry()
                        }
                        zip.finish(); output.force(true)
                    }
                }
                val verified = StoryPackArchive.read(pending)
                val fingerprint = StoryPackArchive.fingerprint(pending)
                val entry = StoryCatalogueEntry(manifest.packId, manifest.revision, manifest.chapterId, manifest.language,
                    manifest.coverage, StoryCatalogueSummary(manifest.titles, manifest.gameVersions, manifest.scripts.size,
                        verified.patches.sumOf { it.changes.size }), fingerprint.first, fingerprint.second)
                require(entry.matches(verified.manifest))
                val previous = old.entries.singleOrNull { it.packId == entry.packId && it.revision == entry.revision }
                require(previous == null || previous == entry) { "Published revision is immutable; increment revision" }
                val updated = StoryCatalogue(if (previous == null) old.entries + entry else old.entries)
                val reused = destination.exists()
                if (reused) {
                    require(StoryPackArchive.fingerprint(destination) == fingerprint) { "Published revision differs; never overwrite" }
                    StoryPackArchive.read(destination, entry)
                    // This invocation's redundant, validated temporary archive only.
                    Files.delete(pending.toPath())
                } else {
                    Files.createDirectories(requireNotNull(destination.parentFile).toPath())
                    require(!destination.exists())
                    Files.move(pending.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
                }
                val encoded = StoryPackJson.encodeCatalogue(updated)
                if (!catalogue.exists() || !bounded(catalogue).contentEquals(encoded)) {
                    val pendingIndex = confined(published, File(published, ".partial-${UUID.randomUUID()}.catalog.json"), false)
                    FileChannel.open(pendingIndex.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { output ->
                        val buffer = java.nio.ByteBuffer.wrap(encoded)
                        while (buffer.hasRemaining()) output.write(buffer)
                        output.force(true)
                    }
                    StoryPackJson.decodeCatalogue(bounded(pendingIndex))
                    Files.move(pendingIndex.toPath(), catalogue.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                }
                return Publication(destination, catalogue, reused)
            }
        }
    }

    private fun bounded(file: File): ByteArray {
        require(file.isFile && file.length() in 1..StoryPackPolicy.MAX_JSON.toLong())
        return file.inputStream().use { StoryPackArchive.readBounded(it, StoryPackPolicy.MAX_JSON) }
    }

    private fun confined(root: File, candidate: File, allowRoot: Boolean = true): File {
        val base = root.absoluteFile.normalize().toPath()
        val target = candidate.absoluteFile.normalize().toPath()
        require(target.startsWith(base) && (allowRoot || target != base)) { "Story publishing path escapes its root" }
        var check: java.nio.file.Path? = target
        while (check != null) {
            require(!Files.isSymbolicLink(check)) { "Linked publishing paths refused" }
            if (Files.exists(check)) require(check.toRealPath() == check) { "Redirected publishing paths refused" }
            check = check.parent
        }
        return target.toFile()
    }
}

object StoryPackPublisherMain {
    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 2) { "Repository and reviewed draft directory required" }
        val result = LocalStoryPackPublisher.publish(File(args[0]), File(args[1]))
        println("SOURCE PACKAGE ONLY - no compilation, APK install, FGO write, network or translation API.")
        println("Archive: ${result.archive}")
        println("Catalogue: ${result.catalogue}")
        println("Reused identical revision: ${result.reused}")
    }
}
