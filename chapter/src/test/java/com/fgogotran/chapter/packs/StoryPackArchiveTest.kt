package com.fgogotran.chapter.packs

import java.io.File
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.*

class StoryPackArchiveTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun archive(members: Map<String, ByteArray> = StoryPackTestData.members()): File =
        StoryPackTestData.archive(temporary.newFile(), members)

    @Test fun `archive verifies catalogue digest contents identity and typed patch data`() {
        val file = archive()
        val entry = StoryPackTestData.catalogue(file)
        val pack = StoryPackArchive.read(file, entry)
        assertEquals("intro-sample", pack.manifest.chapterId)
        assertEquals(StoryLanguage.TRADITIONAL_CHINESE, pack.manifest.language)
        assertEquals("dialogue:10", pack.patches.single().changes.single().fieldId)
        assertFails { StoryPackArchive.read(file, entry.copy(sha256 = "f".repeat(64))) }
        assertFails { StoryPackArchive.read(file, entry.copy(size = entry.size + 1)) }
        assertFails { StoryPackArchive.read(file, entry.copy(chapterId = "fuyuki")) }
        assertFails { StoryPackArchive.read(file, entry.copy(language = StoryLanguage.ENGLISH)) }
        assertFails { StoryPackArchive.read(file, entry.copy(coverage = StoryCoverage.CHAPTER)) }
        assertFails { StoryPackArchive.read(file, entry.copy(summary = StoryCatalogueSummary(entry.summary.titles, entry.summary.gameVersions, 1, 2))) }
    }

    @Test fun `missing undeclared traversal absolute directory and foreign archives are refused`() {
        val members = StoryPackTestData.members()
        listOf(
            members - "manifest.json",
            members - StoryPackPolicy.entry(StoryPackTestData.SCRIPT),
            members + ("unexpected.json" to byteArrayOf(1)),
            members + ("../escape.json" to byteArrayOf(1)),
            members + ("/absolute.json" to byteArrayOf(1)),
            members + ("patches\\evil.json" to byteArrayOf(1)),
            members + ("patches/" to byteArrayOf()),
            mapOf("record.json" to "{}".toByteArray())
        ).forEach { invalid -> assertFails { StoryPackArchive.read(archive(invalid)) } }
        assertEquals(0, temporary.root.listFiles().orEmpty().count { it.name == "escape.json" })
    }

    @Test fun `metadata and patch hashes cannot hide modified or swapped content`() {
        val members = StoryPackTestData.members()
        val path = StoryPackPolicy.entry(StoryPackTestData.SCRIPT)
        assertFails { StoryPackArchive.read(archive(members + (path to "{}".toByteArray()))) }
        val otherPatch = StoryScriptPatch("other", StoryPackTestData.patch().changes)
        val bytes = StoryPackJson.encodePatch(otherPatch)
        val old = StoryPackTestData.manifest()
        val descriptor = old.scripts.single().copy(patchSha256 = com.fgogotran.chapter.Sha256Digest.compute(bytes).hex, patchSize = bytes.size)
        val manifest = StoryPackManifest(old.packId, old.revision, old.chapterId, old.titles, old.language, old.coverage,
            old.questIds, old.gameVersions, listOf(descriptor), old.sources)
        assertFails { StoryPackArchive.read(archive(mapOf("manifest.json" to StoryPackJson.encodeManifest(manifest), path to bytes))) }
    }

    @Test fun `compression bombs oversized archives corrupt and truncated ZIPs fail`() {
        assertFails { StoryPackArchive.read(archive(StoryPackTestData.members() + ("patches/huge.json" to ByteArray(StoryPackPolicy.MAX_JSON + 1)))) }
        val large = temporary.newFile().apply { java.io.RandomAccessFile(this, "rw").use { it.setLength(StoryPackPolicy.MAX_ARCHIVE.toLong() + 1) } }
        assertFails { StoryPackArchive.read(large) }
        val many = (0..StoryPackPolicy.MAX_ENTRIES).associate { "patches/s$it.json" to byteArrayOf(1) }
        assertFails { StoryPackArchive.read(archive(many)) }
        val complete = archive().readBytes()
        val truncated = temporary.newFile().apply { writeBytes(complete.copyOf(complete.size - 22)) }
        assertFails { StoryPackArchive.read(truncated) }
        val garbage = temporary.newFile().apply { writeText("not a story archive") }
        assertFails { StoryPackArchive.read(garbage) }
    }

    @Test fun `ZIP directory claims cannot bypass preallocation entry bounds`() {
        val original = archive().readBytes()
        fun forged(change: (ByteArray, Int) -> Unit): File = temporary.newFile().apply {
            val bytes = original.copyOf(); change(bytes, bytes.size - 22); writeBytes(bytes)
        }
        assertFails { StoryPackArchive.read(forged { bytes, end -> bytes[end + 8] = 0; bytes[end + 10] = 0 }) }
        assertFails { StoryPackArchive.read(forged { bytes, end -> bytes[end + 4] = 1 }) }
        assertFails { StoryPackArchive.read(forged { bytes, end -> bytes[end + 8] = 1; bytes[end + 10] = 1 }) }
        assertFails { StoryPackArchive.read(forged { bytes, end -> bytes[end + 8] = 0xff.toByte(); bytes[end + 10] = 0xff.toByte() }) }
    }
}
