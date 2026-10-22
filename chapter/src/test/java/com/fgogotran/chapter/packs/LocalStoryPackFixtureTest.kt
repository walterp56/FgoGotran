package com.fgogotran.chapter.packs

import com.fgogotran.chapter.*
import com.fgogotran.chapter.codec.*
import com.fgogotran.chapter.packs.tools.LocalStoryPackPublisher
import java.io.File
import java.util.UUID
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue
import kotlin.test.*

/** Explicit local opt-in only: publishes one reviewed source field, never a rebuilt/game asset. */
class LocalStoryPackFixtureTest {
    @Test fun `reviewed opening TW source field packages against authentic JP script identity`() {
        val captureProperty = System.getProperty("chapter.localCapture", "").orEmpty()
        val sourcesProperty = System.getProperty("chapter.localStorySources", "").orEmpty()
        assumeTrue("Authentic story sources and capture are opt-in", captureProperty.isNotEmpty() && sourcesProperty.isNotEmpty())
        val module = File(requireNotNull(System.getProperty("chapter.projectDir"))).canonicalFile
        val repository = requireNotNull(module.parentFile)
        val capture = LocalChapterPaths.capture(module, captureProperty)
        val atlasRoot = File(repository, "story_assets/atlas").canonicalFile
        val sources = File(sourcesProperty).absoluteFile.normalize()
        require(sources.canonicalFile == sources && sources.toPath().startsWith(atlasRoot.toPath()) && sources != atlasRoot)
        val sourceIndex = Json.parseToJsonElement(File(sources, "source-index.json").readText()).jsonObject
        require(sourceIndex.getValue("schema_version").jsonPrimitive.int == 1 && sourceIndex.getValue("status").jsonPrimitive.content == "verified")
        fun source(region: String, expectedSha: String): Pair<ByteArray, StorySource> {
            val path = "$region/0100000011.txt"
            val item = sourceIndex.getValue("files").jsonArray.single { it.jsonObject.getValue("path").jsonPrimitive.content == path }.jsonObject
            val file = File(sources, path)
            require(file.canonicalFile == file.absoluteFile && file.length() in 1..4 * 1024 * 1024L)
            val bytes = file.readBytes()
            assertEquals(expectedSha, Sha256Digest.compute(bytes).hex)
            assertEquals(expectedSha, item.getValue("sha256").jsonPrimitive.content)
            assertEquals(bytes.size, item.getValue("size_bytes").jsonPrimitive.int)
            return bytes to StorySource(region, "0100000011", expectedSha, bytes.size, item.getValue("url").jsonPrimitive.content)
        }
        val jp = source("JP", "faa41b781c7697fb137111f895b37d7ed3b309b9759bbe63bbdf12adfc7feaf5")
        val tw = source("TW", "8593f5d3c436acf8b9c691441f6769336f97f0ee55486a67fccd1250ff7cd485")
        fun reviewedLine(bytes: ByteArray, line: Int, hash: String): String {
            val value = bytes.toString(Charsets.UTF_8).split(Regex("\\r?\\n"))[line]
            assertEquals(hash, Sha256Digest.compute(value.toByteArray(Charsets.UTF_8)).hex)
            return value
        }
        // Explicitly reviewed corresponding greeting in the same speaker/scene, not runtime line alignment.
        // Hashes pin the exact archived texts; story literals are not committed as test fixtures.
        val jpLine = reviewedLine(jp.first, 221, "bb99189327a5101460bdbaa73e067c920fa7b42b8540a73ae64fc81d943ab12c")
        val twLine = reviewedLine(tw.first, 205, "7892db988bce32b4f2382097b287404dfa647ab2e29c4a2ba449bfe96e3144f1")
        val captureIndex = Json.parseToJsonElement(File(capture, "capture.json").readText()).jsonObject
        assertEquals(StoryPackPolicy.GAME_PACKAGE, captureIndex.getValue("package").jsonPrimitive.content)
        assertEquals("2.139.5", captureIndex.getValue("version_name").jsonPrimitive.content)
        val bundleName = "3b6e743e6f41fb01a1037cb15169096d6a63198c.bin"
        val bytes = File(capture, bundleName).readBytes()
        assertEquals("a0af45a5439b555dd08535f724b19f3d00f45d93df380d1518438ffcd0104229", Sha256Digest.compute(bytes).hex)
        val original = OriginalAsset.capture(GameAssetPath.parse(bundleName), bytes)
        val source = FgoJpFormatProfile.containerCodec().decode(original).valueOrThrow().scripts.single { it.binding.scriptId.value == "0100000011" }
        val scriptCodec = FgoJpFormatProfile.scriptCodec()
        val decoded = scriptCodec.decodedBytes(source).valueOrThrow()
        assertContentEquals(jp.first, decoded.toByteArray())
        val field = scriptCodec.decode(source).valueOrThrow().fields.single { it.kind == NativeTextKind.DIALOGUE && it.sourceText == jpLine }
        assertTrue(field.ruby.isEmpty() && field.placeholders.isEmpty())
        val patch = StoryScriptPatch("0100000011", listOf(StoryTextChange(field.id.value, StoryFieldKind.DIALOGUE,
            Sha256Digest.compute(jpLine.toByteArray()).hex, listOf(StoryTextToken.Text(twLine)))))
        val patchBytes = StoryPackJson.encodePatch(patch)
        val manifest = StoryPackManifest("intro-tw-one-line", 1, "intro-opening-sample", mapOf(
            "en" to "Opening scene — one-line TW sample", "zh-Hans" to "开场剧情：单句繁中样本", "zh-Hant" to "開場劇情：單句繁中樣本"
        ), StoryLanguage.TRADITIONAL_CHINESE, StoryCoverage.SAMPLE, listOf(1000000), listOf(StoryGameVersion("2.139.5", 502)), listOf(
            StoryScriptDescriptor("0100000011", "ScriptActionEncrypt/01", bundleName, original.fingerprint.sha256.hex, original.bytes.size,
                source.payload.sha256.hex, decoded.sha256.hex, Sha256Digest.compute(patchBytes).hex, patchBytes.size)
        ), listOf(jp.second, tw.second))
        val draft = File(repository, "story_assets/mappings/intro-tw-one-line-${UUID.randomUUID()}")
        check(draft.mkdirs()); val patches = File(draft, "patches"); check(patches.mkdir())
        File(draft, "manifest.json").writeBytes(StoryPackJson.encodeManifest(manifest))
        File(draft, StoryPackPolicy.entry(patch.scriptId)).writeBytes(patchBytes)
        val publication = LocalStoryPackPublisher.publish(repository, draft)
        val entry = StoryPackJson.decodeCatalogue(publication.catalogue.readBytes()).entries.single { it.packId == manifest.packId && it.revision == 1 }
        val validated = StoryPackArchive.read(publication.archive, entry)
        assertEquals(StoryCoverage.SAMPLE, validated.manifest.coverage)
        assertEquals(1, validated.patches.single().changes.size)
        println("Reviewed data-only draft: $draft")
        println("Source-only TW sample: ${publication.archive}")
        println("Field identity: ${field.id.value}; payload SHA-256: ${source.payload.sha256}")
        println("NOT compiled, installed or playback-approved. No FGO file or API accessed.")
    }
}
