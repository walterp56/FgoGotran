package com.fgogotran.chapter.packs

// Source packages belong to developer tooling; legacy recovery reads journals only.

import com.fgogotran.chapter.Sha256Digest
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipFile

/** Reads bounded regular files only, with no archive extraction, network or installation port. */
object StoryPackArchive {
    fun read(file: File, expected: StoryCatalogueEntry? = null): ValidatedStoryPack {
        require(Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) && file.length() in 1..StoryPackPolicy.MAX_ARCHIVE.toLong()) { "Invalid story archive size/type" }
        val before = fingerprint(file)
        expected?.let { require(before == (it.sha256 to it.size)) { "Catalogue/archive digest mismatch" } }
        preflightDirectory(file)
        val contents = linkedMapOf<String, ByteArray>()
        var expanded = 0L
        ZipFile(file).use { zip ->
            val names = mutableSetOf<String>()
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                require(names.size < StoryPackPolicy.MAX_ENTRIES) { "Too many story archive entries" }
                require(!entry.isDirectory && (entry.name == "manifest.json" || entry.name.matches(Regex("patches/[A-Za-z0-9_]{1,64}\\.json")))) {
                    "Unexpected or unsafe story archive path"
                }
                require(names.add(entry.name.lowercase())) { "Duplicate story archive entry" }
                require(entry.method in listOf(0, 8) && entry.size in 1..StoryPackPolicy.MAX_JSON.toLong()) { "Invalid story entry size/method" }
                require(expanded + entry.size <= StoryPackPolicy.MAX_EXPANDED)
                val bytes = zip.getInputStream(entry).use { readBounded(it, StoryPackPolicy.MAX_JSON) }
                require(bytes.size.toLong() == entry.size && CRC32().apply { update(bytes) }.value == entry.crc) { "Story ZIP read-back/CRC mismatch" }
                expanded += bytes.size
                contents[entry.name] = bytes
            }
        }
        val manifest = StoryPackJson.decodeManifest(requireNotNull(contents["manifest.json"]) { "Story manifest missing" })
        require(contents.keys == setOf("manifest.json") + manifest.scripts.map { it.path }) { "Missing or undeclared story files" }
        val patches = manifest.scripts.map { script ->
            val bytes = contents.getValue(script.path)
            require(bytes.size == script.patchSize && Sha256Digest.compute(bytes).hex == script.patchSha256) { "Story patch digest mismatch" }
            StoryPackJson.decodePatch(bytes).also { require(it.scriptId == script.scriptId) { "Story patch identity mismatch" } }
        }
        expected?.let {
            require(it.matches(manifest) && it.summary.changeCount == patches.sumOf { patch -> patch.changes.size }) { "Catalogue/archive identity or coverage mismatch" }
        }
        require(fingerprint(file) == before) { "Story archive changed while reading" }
        return ValidatedStoryPack(manifest, patches, before.first, before.second)
    }

    /** Bound the central directory before ZipFile allocates its per-entry metadata. No ZIP64/spanning. */
    private fun preflightDirectory(file: File) = RandomAccessFile(file, "r").use { input ->
        val length = input.length()
        require(length in 22..StoryPackPolicy.MAX_ARCHIVE.toLong())
        val tail = ByteArray(minOf(length, 65557).toInt())
        val tailStart = length - tail.size
        input.seek(tailStart); input.readFully(tail)
        fun ByteArray.u16(at: Int) = (this[at].toInt() and 255) or ((this[at + 1].toInt() and 255) shl 8)
        fun ByteArray.u32(at: Int): Long = u16(at).toLong() or (u16(at + 2).toLong() shl 16)
        val end = (tail.size - 22 downTo 0).firstOrNull {
            tail.u32(it) == 0x06054b50L && it + 22 + tail.u16(it + 20) == tail.size
        } ?: error("Story ZIP end record missing")
        require(tail.u16(end + 4) == 0 && tail.u16(end + 6) == 0) { "Multi-disk story ZIP unsupported" }
        val count = tail.u16(end + 10)
        require(count in 1..StoryPackPolicy.MAX_ENTRIES && tail.u16(end + 8) == count) { "Invalid story ZIP entry count" }
        val start = tail.u32(end + 16)
        val size = tail.u32(end + 12)
        require(start + size == tailStart + end && size >= 46L * count) { "Invalid story ZIP directory bounds" }
        input.seek(start)
        var expanded = 0L
        repeat(count) {
            require(input.filePointer + 46 <= start + size)
            val header = ByteArray(46); input.readFully(header)
            require(header.u32(0) == 0x02014b50L && header.u16(34) == 0)
            require(header.u16(8) and 1 == 0 && header.u16(10) in listOf(0, 8)) { "Encrypted/unsupported story ZIP" }
            val expandedSize = header.u32(24)
            require(expandedSize in 1..StoryPackPolicy.MAX_JSON.toLong() && header.u32(20) <= StoryPackPolicy.MAX_ARCHIVE)
            expanded += expandedSize
            require(expanded <= StoryPackPolicy.MAX_EXPANDED)
            require(header.u32(42) < start) { "Invalid story ZIP local header" }
            val nameLength = header.u16(28)
            require(nameLength in 1..128)
            val next = input.filePointer + nameLength + header.u16(30) + header.u16(32)
            require(next <= start + size)
            input.seek(next)
        }
        require(input.filePointer == start + size) { "Story ZIP directory count differs" }
    }

    fun fingerprint(file: File): Pair<String, Int> {
        val digest = MessageDigest.getInstance("SHA-256")
        var count = 0
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val length = input.read(buffer); if (length < 0) break
                require(count.toLong() + length <= StoryPackPolicy.MAX_ARCHIVE) { "Story archive exceeds limit" }
                digest.update(buffer, 0, length); count += length
            }
        }
        require(count > 0)
        return digest.digest().joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') } to count
    }

    internal fun readBounded(input: InputStream, limit: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val length = input.read(buffer); if (length < 0) break
            require(output.size().toLong() + length <= limit) { "Story input exceeds limit" }
            output.write(buffer, 0, length)
        }
        return output.toByteArray()
    }
}
