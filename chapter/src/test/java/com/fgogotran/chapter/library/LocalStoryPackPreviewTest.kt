package com.fgogotran.chapter.library

import com.fgogotran.chapter.*
import com.fgogotran.chapter.codec.FgoJpFormatProfile
import com.fgogotran.chapter.packs.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue
import kotlin.test.*

/** Owner-local import -> read-only preview integration, never Android/FGO write-back. */
class LocalStoryPackPreviewTest {
    @Test fun `authentic published TW sample imports persists and previews current captured JP originals`(): Unit = runBlocking {
        val property = System.getProperty("chapter.localCapture", "").orEmpty()
        assumeTrue("Authentic source archive/capture are opt-in", property.isNotEmpty())
        val module = File(requireNotNull(System.getProperty("chapter.projectDir"))).canonicalFile
        val capture = LocalChapterPaths.capture(module, property)
        val record = Json.parseToJsonElement(File(capture, "capture.json").readText()).jsonObject
        val originals = record.getValue("files").jsonArray.associate { value ->
            val item = value.jsonObject
            val path = GameAssetPath.parse(item.getValue("path").jsonPrimitive.content)
            require('/' !in path.value)
            val file = File(capture, path.value).absoluteFile
            require(file.canonicalFile == file && file.length() <= StoryPackPolicy.MAX_ASSET)
            val original = OriginalAsset.capture(path, file.readBytes())
            assertEquals(item.getValue("sha256").jsonPrimitive.content, original.fingerprint.sha256.hex)
            assertEquals(item.getValue("size_bytes").jsonPrimitive.long, original.fingerprint.sizeBytes)
            path to original
        }
        val archive = File(module.parentFile, "story_assets/published/packs/intro-tw-one-line/1.fgostory.zip")
        assertEquals("333306e6fdff50648ff36387f7e62f6b186ddb6faec592a1395c8a352f2d3afd", StoryPackArchive.fingerprint(archive).first)
        val output = File(module.parentFile, "story_assets/runs/language-pack-p3-fixture-${UUID.randomUUID()}").absoluteFile
        require(output.canonicalFile == output); check(output.mkdirs())
        val privateLibrary = File(output, "private-library")
        val entry = StoryPackLibrary(privateLibrary).import({ archive.inputStream() })
        val reopened = StoryPackLibrary(privateLibrary)
        assertEquals(listOf(entry), reopened.list())
        assertEquals(StoryCoverage.SAMPLE, entry.coverage)
        val storage = MemoryOriginals(originals)
        val version = StoryGameVersion(record.getValue("version_name").jsonPrimitive.content, record.getValue("version_code").jsonPrimitive.content.toLong())
        val preview = StoryPackPreviewer(storage, { version }).preview(reopened.load(listOf(entry)))
        assertEquals(1, preview.packCount); assertEquals(1, preview.requestedFieldCount)
        assertEquals(1, preview.changedScriptCount); assertEquals(1, preview.rebuiltBundleCount)
        assertEquals(originals.getValue(GameAssetPath.parse(FgoJpFormatProfile.MANIFEST_NAME)).fingerprint.sha256.hex, preview.manifestSha256)
        assertEquals(4, storage.reads.size)
        assertEquals(archive.readBytes().toList(), File(privateLibrary, "archives/${entry.sha256}.fgostory.zip").readBytes().toList())
        File(output, "RESULT.txt").writeText("P3 local import/read-only preview passed; no Android UI/device write/API calls.\n" +
            "pack_sha256=${entry.sha256}\nmanifest_sha256=${preview.manifestSha256}\n" +
            "packs=${preview.packCount}; fields=${preview.requestedFieldCount}; changed_scripts=${preview.changedScriptCount}; rebuilt_bundles=${preview.rebuiltBundleCount}\n" +
            "Original capture/published pack unchanged. Compiled bytes discarded. Native playback NOT TESTED.\n")
        println("P3 authentic local import/preview evidence: $output")
    }
}
