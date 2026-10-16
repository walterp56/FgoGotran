package com.fgogotran.chapter.codec

import com.fgogotran.chapter.*
import kotlinx.serialization.json.*
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import java.io.File
import java.nio.ByteBuffer
import java.util.Properties
import kotlin.test.*

/** Opt-in authentic fixture proof. No device/network calls and no install operation. */
class LocalChapterFixtureTest {
    @Test
    fun `captured native bundle round trips and one plain field rebuilds offline`() {
        val directory = System.getProperty("chapter.localCapture", "").orEmpty()
        assumeTrue("Local native fixtures are opt-in", directory.isNotEmpty())
        val module = File(requireNotNull(System.getProperty("chapter.projectDir"))).canonicalFile
        val root = File(module, "local-fixtures").canonicalFile
        val capture = File(directory).canonicalFile
        require(capture.toPath().startsWith(root.toPath()) && capture != root)
        val record = Json.parseToJsonElement(File(capture, "capture.json").readText()).jsonObject
        require(record.getValue("schema_version").jsonPrimitive.int == 1)
        require(record.getValue("package").jsonPrimitive.content == "com.aniplex.fategrandorder")
        val originals = record.getValue("files").jsonArray.associate { item ->
            val obj = item.jsonObject
            val path = GameAssetPath.parse(obj.getValue("path").jsonPrimitive.content)
            require('/' !in path.value)
            val file = File(capture, path.value).canonicalFile
            require(file.parentFile == capture && file.length() <= MAX_NATIVE_BYTES)
            val original = OriginalAsset.capture(path, file.readBytes())
            assertEquals(obj.getValue("sha256").jsonPrimitive.content, original.fingerprint.sha256.hex)
            assertEquals(obj.getValue("size_bytes").jsonPrimitive.long, original.fingerprint.sizeBytes)
            path.value to original
        }
        val profile = Properties().apply { File(root, "jp-envelope.properties").inputStream().use { load(it) } }
        fun envelope(prefix: String): RijndaelEnvelope {
            fun hex(name: String): AssetBytes {
                val value = requireNotNull(profile.getProperty(name))
                require(value.matches(Regex("[0-9a-f]{64}")))
                return AssetBytes.copyOf(value.chunked(2).map { it.toInt(16).toByte() }.toByteArray())
            }
            return RijndaelEnvelope(hex("${prefix}_key"), hex("${prefix}_iv"))
        }
        val manifestCodec = FgoInstalledManifestCodec(envelope("stage"))
        val containerCodec = FgoJpContainerCodec(envelope("asset"))
        val scriptCodec = FgoNativeScriptCodec(envelope("stage"))
        val manifest = originals.getValue("cfb1d36393fd67385e046b084b7cf7ed")
        assertEquals(record.getValue("manifest_sha256").jsonPrimitive.content, manifest.fingerprint.sha256.hex)
        val decodedManifest = manifestCodec.decode(manifest).valueOrThrow()
        assertEquals(manifest.bytes, manifestCodec.encodeUnchanged(decodedManifest).valueOrThrow())
        val entry = manifestCodec.entries(manifest).valueOrThrow().single { it.logicalPath == "ScriptActionEncrypt/01" }
        val asset = originals.getValue(entry.assetPath.value)
        manifestCodec.verifyAsset(manifest, asset).valueOrThrow()
        val container = containerCodec.decode(asset).valueOrThrow()
        // The Android path uses its packaged public profile, not this desktop properties file.
        assertEquals(manifestCodec.entries(manifest).valueOrThrow(), FgoJpFormatProfile.manifestCodec().entries(manifest).valueOrThrow())
        val reads = mutableListOf<GameAssetPath>()
        val browser = ChapterBrowser(object : ChapterOriginalStorage {
            override suspend fun capability() = ChapterStorageCapability.ReadOnly("test-capture")
            override suspend fun readOriginal(path: GameAssetPath): OriginalAssetReadResult {
                reads += path
                return originals[path.value]?.let { OriginalAssetReadResult.Read(it) } ?: OriginalAssetReadResult.NotFound
            }
        })
        runBlocking {
            val catalogue = browser.scan()
            assertTrue(catalogue.groups.all { it.installed && it.logicalPath != "ScriptActionEncrypt/ScriptFileList" })
            val browsed = browser.load(catalogue, catalogue.groups.single { it.logicalPath == entry.logicalPath })
            assertEquals(container.scripts.map { it.binding to it.payload }, browsed.scripts.map { it.binding to it.payload })
            val selected = browsed.scripts.first()
            val viewed = browser.view(selected)
            assertEquals(scriptCodec.decode(selected).valueOrThrow().fields.map { it.sourceText }, viewed.document.fields.map { it.sourceText })
            assertEquals(strictUtf8(scriptCodec.decodedBytes(selected).valueOrThrow().toByteArray()), viewed.nativeText)
        }
        assertEquals(listOf(manifest.fingerprint.path, manifest.fingerprint.path, asset.fingerprint.path, manifest.fingerprint.path), reads)
        assertTrue(container.scripts.isNotEmpty())
        assertEquals(asset.bytes, containerCodec.encodeUnchanged(container).valueOrThrow())
        var fields = 0; var ruby = 0; var choices = 0
        container.scripts.forEach { source ->
            val decoded = scriptCodec.decode(source).valueOrThrow()
            assertEquals(source.payload, scriptCodec.encodeUnchanged(decoded).valueOrThrow())
            fields += decoded.fields.size
            ruby += decoded.fields.sumOf { it.ruby.size }
            choices += decoded.fields.count { it.kind == NativeTextKind.CHOICE }
        }
        // Early story text is a reproducible candidate, not a claim about account unlock state.
        val source = container.scripts.single { it.binding.scriptId.value == "0100000011" }
        // Exercise actual native reconstruction even without a text edit (not just copying an asset).
        val noOpAsset = containerCodec.replaceScript(asset, source, source.payload).valueOrThrow()
        val noOpOriginal = OriginalAsset.capture(asset.fingerprint.path, noOpAsset.toByteArray())
        val noOpDecoded = containerCodec.decode(noOpOriginal).valueOrThrow()
        assertEquals(container.scripts.map { it.binding to it.payload }, noOpDecoded.scripts.map { it.binding to it.payload })
        val noOpManifest = manifestCodec.replaceAsset(manifest, asset, noOpAsset).valueOrThrow()
        manifestCodec.verifyAsset(OriginalAsset.capture(manifest.fingerprint.path, noOpManifest.toByteArray()), noOpOriginal).valueOrThrow()
        val document = scriptCodec.decode(source).valueOrThrow()
        val field = document.fields.first { it.kind == NativeTextKind.DIALOGUE && it.sourceText.isNotBlank() && it.sourceText.none { char -> char in "[]＠＄？" } }
        val patch = NativeTextPatch(field.id, Sha256Digest.compute(field.sourceText.toByteArray(Charsets.UTF_8)), "テスト表示")
        val patchedScript = scriptCodec.patchPlainDialogue(source, patch).valueOrThrow()
        val patchedAsset = containerCodec.replaceScript(asset, source, patchedScript).valueOrThrow()
        val updatedOriginal = OriginalAsset.capture(asset.fingerprint.path, patchedAsset.toByteArray())
        val updatedContainer = containerCodec.decode(updatedOriginal).valueOrThrow()
        assertEquals(container.scripts.map { it.binding }, updatedContainer.scripts.map { it.binding })
        container.scripts.zip(updatedContainer.scripts).forEach { (before, after) ->
            assertEquals(if (before.binding == source.binding) patchedScript else before.payload, after.payload)
        }
        val afterSource = updatedContainer.scripts.single { it.binding == source.binding }
        val beforeText = strictUtf8(scriptCodec.decodedBytes(source).valueOrThrow().toByteArray())
        val afterText = strictUtf8(scriptCodec.decodedBytes(afterSource).valueOrThrow().toByteArray())
        val range = ByteBuffer.wrap(field.locator.payload.toByteArray())
        val start = range.int; val end = range.int
        assertEquals(beforeText.substring(0, start) + patch.replacement + beforeText.substring(end), afterText)
        fun fieldContent(decoded: DecodedNativeScript) = decoded.fields.map {
            listOf(it.kind, it.sourceText, it.ruby.map { annotation -> listOf(annotation.baseRange, annotation.reading) }, it.placeholders.map { annotation -> annotation.range })
        }
        val expectedFields = fieldContent(document).toMutableList()
        val selectedIndex = document.fields.indexOf(field)
        expectedFields[selectedIndex] = listOf(field.kind, patch.replacement, emptyList<Any>(), emptyList<Any>())
        assertEquals(expectedFields, fieldContent(scriptCodec.decode(afterSource).valueOrThrow()))
        val patchedManifest = manifestCodec.replaceAsset(manifest, asset, patchedAsset).valueOrThrow()
        val newManifest = OriginalAsset.capture(manifest.fingerprint.path, patchedManifest.toByteArray())
        manifestCodec.verifyAsset(newManifest, updatedOriginal).valueOrThrow()
        val oldEntries = manifestCodec.entries(manifest).valueOrThrow()
        val newEntries = manifestCodec.entries(newManifest).valueOrThrow()
        assertEquals(oldEntries.size, newEntries.size)
        oldEntries.zip(newEntries).forEach { (old, new) ->
            assertEquals(if (old.assetPath == asset.fingerprint.path) old.copy(size = patchedAsset.size.toLong(), crc32 = crc32(patchedAsset.toByteArray())) else old, new)
        }
        val output = File(module, "local-output/${capture.name}/proof").apply { mkdirs() }
        File(output, asset.fingerprint.path.value).writeBytes(patchedAsset.toByteArray())
        File(output, manifest.fingerprint.path.value).writeBytes(patchedManifest.toByteArray())
        File(output, "original.unity").writeBytes(containerCodec.decodeUnity(asset.bytes).valueOrThrow().toByteArray())
        File(output, "patched.unity").writeBytes(containerCodec.decodeUnity(patchedAsset).valueOrThrow().toByteArray())
        File(output, "no-op.unity").writeBytes(containerCodec.decodeUnity(noOpAsset).valueOrThrow().toByteArray())
        val noOpOutput = File(output, "no-op").apply { mkdirs() }
        File(noOpOutput, asset.fingerprint.path.value).writeBytes(noOpAsset.toByteArray())
        File(noOpOutput, manifest.fingerprint.path.value).writeBytes(noOpManifest.toByteArray())
        File(output, "original.script.txt").writeText(beforeText)
        File(output, "patched.script.txt").writeText(afterText)
        File(output, "proof.txt").writeText("""
            OFFLINE ONLY. NOT APPROVED FOR INSTALLATION.
            FGO version: ${record.getValue("version_name").jsonPrimitive.content}
            Manifest SHA256: ${manifest.fingerprint.sha256}
            Original asset SHA256: ${asset.fingerprint.sha256}
            Patched asset SHA256: ${patchedAsset.sha256}
            Patched manifest SHA256: ${patchedManifest.sha256}
            Scripts unchanged-round-tripped: ${container.scripts.size}
            Unchanged native bundle also reconstructed through the writer; every script payload identical.
            Parsed fields: $fields; native ruby spans: $ruby; choices: $choices
            Logical asset: ${entry.logicalPath}
            Script: ${source.binding.scriptId.value}; field: ${field.id.value}
            Replacement: ${patch.replacement}
            Exactly one script field changed; all other script payloads identical.
            Manifest size/CRC and body CRC validated.
            Changed Unity bundle uses uncompressed blocks for proof, not production optimisation.
            Android non-root bridge, native playback and restore: NOT TESTED.
        """.trimIndent() + "\n")
    }
}
