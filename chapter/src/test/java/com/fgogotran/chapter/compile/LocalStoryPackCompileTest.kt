package com.fgogotran.chapter.compile

import com.fgogotran.chapter.*
import com.fgogotran.chapter.codec.*
import com.fgogotran.chapter.packs.*
import java.io.File
import java.nio.ByteBuffer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue
import kotlin.test.*

/** Explicit local opt-in only. Rebuilds local copies; no device, installer or API access. */
class LocalStoryPackCompileTest {
    @Test fun `published reviewed TW field compiles into authentic JP bundle without unrelated edits`(): Unit = runBlocking {
        val directory = System.getProperty("chapter.localCapture", "").orEmpty()
        assumeTrue("Authentic native fixture is opt-in", directory.isNotEmpty())
        val module = File(requireNotNull(System.getProperty("chapter.projectDir"))).canonicalFile
        val capture = LocalChapterPaths.capture(module, directory)
        val record = Json.parseToJsonElement(File(capture, "capture.json").readText()).jsonObject
        assertEquals(1, record.getValue("schema_version").jsonPrimitive.int)
        assertEquals(StoryPackPolicy.GAME_PACKAGE, record.getValue("package").jsonPrimitive.content)
        assertEquals("2.139.5", record.getValue("version_name").jsonPrimitive.content)
        assertEquals("502", record.getValue("version_code").jsonPrimitive.content)
        val originals = record.getValue("files").jsonArray.associate { item ->
            val entry = item.jsonObject
            val path = GameAssetPath.parse(entry.getValue("path").jsonPrimitive.content)
            require('/' !in path.value)
            val file = File(capture, path.value).absoluteFile
            require(file.canonicalFile == file && file.parentFile == capture && file.length() in 1..StoryPackPolicy.MAX_ASSET.toLong())
            val original = OriginalAsset.capture(path, file.readBytes())
            assertEquals(entry.getValue("size_bytes").jsonPrimitive.long, original.fingerprint.sizeBytes)
            assertEquals(entry.getValue("sha256").jsonPrimitive.content, original.fingerprint.sha256.hex)
            path.value to original
        }
        val manifest = originals.getValue(FgoJpFormatProfile.MANIFEST_NAME)
        assertEquals("c11564768c547e2bfe9f85b841ae297b9b144e1b4ade88e8a5ef99cb28a9f55f", manifest.fingerprint.sha256.hex)
        val asset = originals.getValue("3b6e743e6f41fb01a1037cb15169096d6a63198c.bin")
        assertEquals("a0af45a5439b555dd08535f724b19f3d00f45d93df380d1518438ffcd0104229", asset.fingerprint.sha256.hex)
        val published = File(module.parentFile, "story_assets/published").canonicalFile
        val entry = StoryPackJson.decodeCatalogue(File(published, "catalog.json").readBytes()).entries.single {
            it.packId == "intro-tw-one-line" && it.revision == 1
        }
        assertEquals("333306e6fdff50648ff36387f7e62f6b186ddb6faec592a1395c8a352f2d3afd", entry.sha256)
        val archive = File(published, entry.path).absoluteFile
        require(archive.canonicalFile == archive && archive.toPath().startsWith(published.toPath()))
        val pack = StoryPackArchive.read(archive, entry)
        assertEquals(StoryCoverage.SAMPLE, pack.manifest.coverage)
        val containers = FgoJpFormatProfile.containerCodec()
        val scripts = FgoJpFormatProfile.scriptCodec()
        val manifests = FgoJpFormatProfile.manifestCodec()
        val before = containers.decode(asset).valueOrThrow()
        assertEquals(717, before.scripts.size)
        val source = before.scripts.single { it.binding.scriptId.value == "0100000011" }
        assertEquals("32e9bfc496926dd939986cd0537771eee883c6cc56521ad28c5c116db8c1246a", source.payload.sha256.hex)
        val field = scripts.decode(source).valueOrThrow().fields.single { it.id.value == "dialogue:2999" }
        assertEquals("bb99189327a5101460bdbaa73e067c920fa7b42b8540a73ae64fc81d943ab12c", Sha256Digest.compute(field.sourceText.toByteArray()).hex)
        val replacementText = (pack.patches.single().changes.single().tokens.single() as StoryTextToken.Text).value
        assertEquals("7892db988bce32b4f2382097b287404dfa647ab2e29c4a2ba449bfe96e3144f1", Sha256Digest.compute(replacementText.toByteArray()).hex)
        val compiled = StoryPackCompiler().compile(listOf(pack), StoryGameVersion("2.139.5", 502), manifest, listOf(asset)).valueOrThrow()
        assertEquals(1, compiled.requestedFieldCount)
        assertEquals(1, compiled.changedScriptCount)
        assertEquals(1, compiled.rebuiltBundleCount)
        assertEquals(pack.archiveSha256, compiled.selections.single().archiveSha256)
        val rebuilt = compiled.bundles.single().replacement
        val rebuiltOriginal = OriginalAsset.capture(asset.fingerprint.path, rebuilt.toByteArray())
        val after = containers.decode(rebuiltOriginal).valueOrThrow()
        assertEquals(before.scripts.map { it.binding }, after.scripts.map { it.binding })
        assertEquals(716, before.scripts.zip(after.scripts).count { (a, b) -> a.payload == b.payload })
        before.scripts.zip(after.scripts).filter { (a, _) -> a.binding != source.binding }.forEach { (a, b) -> assertEquals(a.payload, b.payload) }
        val changed = after.scripts.single { it.binding == source.binding }
        val beforeText = strictUtf8(scripts.decodedBytes(source).valueOrThrow().toByteArray())
        val afterText = strictUtf8(scripts.decodedBytes(changed).valueOrThrow().toByteArray())
        val locator = ByteBuffer.wrap(field.locator.payload.toByteArray())
        val start = locator.int; val end = locator.int
        assertEquals(beforeText.substring(0, start) + replacementText + beforeText.substring(end), afterText)
        val rebuiltManifest = OriginalAsset.capture(manifest.fingerprint.path, compiled.manifest.toByteArray())
        manifests.verifyAsset(rebuiltManifest, rebuiltOriginal).valueOrThrow()
        val oldRows = manifests.entries(manifest).valueOrThrow()
        val newRows = manifests.entries(rebuiltManifest).valueOrThrow()
        assertEquals(oldRows.size, newRows.size)
        oldRows.zip(newRows).forEach { (a, b) ->
            assertEquals(if (a.assetPath == asset.fingerprint.path) a.copy(size = rebuilt.size.toLong(), crc32 = crc32(rebuilt.toByteArray())) else a, b)
        }
        // Actual native no-op reconstruction for the independent reader, not an original-file copy.
        val noOp = containers.replaceScripts(asset, listOf(FgoJpContainerCodec.ScriptReplacement(source, source.payload))).valueOrThrow()
        val output = LocalChapterPaths.proofOutput(module, capture)
        File(output, asset.fingerprint.path.value).writeBytes(rebuilt.toByteArray())
        File(output, manifest.fingerprint.path.value).writeBytes(compiled.manifest.toByteArray())
        File(output, "original.unity").writeBytes(containers.decodeUnity(asset.bytes).valueOrThrow().toByteArray())
        File(output, "patched.unity").writeBytes(containers.decodeUnity(rebuilt).valueOrThrow().toByteArray())
        File(output, "no-op.unity").writeBytes(containers.decodeUnity(noOp).valueOrThrow().toByteArray())
        File(output, "original.script.txt").writeText(beforeText)
        File(output, "patched.script.txt").writeText(afterText)
        File(output, "proof.txt").writeText(listOf(
            "P2 language-pack compiler OFFLINE ONLY. Not installable/playback-approved. No device/API access.",
            "pack=${pack.manifest.packId}@${pack.manifest.revision}; archive_sha256=${pack.archiveSha256}",
            "source_capture=${capture.name}",
            "original_bundle_sha256=${asset.fingerprint.sha256}; original_index_sha256=${manifest.fingerprint.sha256}",
            "rebuilt_bundle_sha256=${rebuilt.sha256}; bytes=${rebuilt.size}",
            "rebuilt_index_sha256=${compiled.manifest.sha256}; bytes=${compiled.manifest.size}",
            "scripts=717; unchanged_script_payloads=716; changed_scripts=1; requested_fields=1; rebuilt_bundles=1",
            "edited_script=0100000011; original_field=dialogue:2999; non-field native bytes identical",
            "all_non_target_index_rows_identical=true; actual_no_op_reconstruction=true",
            "Native playback, fonts, layout, resource-index compatibility and device write/restore NOT TESTED."
        ).joinToString("\n", postfix = "\n"))
        println("P2 authentic TW compiler proof: $output")
        println("Offline only: one reviewed field; other 716 script payloads and non-target index rows identical.")
    }
}
