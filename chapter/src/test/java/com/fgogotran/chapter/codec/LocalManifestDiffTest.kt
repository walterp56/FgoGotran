package com.fgogotran.chapter.codec

import com.fgogotran.chapter.*
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/** Owner-local diagnostics only: no device calls, writes or acceptance-policy changes. */
class LocalManifestDiffTest {
    private val module get() = File(requireNotNull(System.getProperty("chapter.projectDir"))).canonicalFile
    private val codec get() = FgoJpFormatProfile.manifestCodec()
    private val containers get() = FgoJpFormatProfile.containerCodec()
    private val beforePath get() = System.getProperty("chapter.localCapture", "").orEmpty()

    private fun capture(path: String): Map<String, OriginalAsset> {
        val directory = LocalChapterPaths.capture(module, path)
        val record = Json.parseToJsonElement(File(directory, "capture.json").readText()).jsonObject
        require(record.getValue("schema_version").jsonPrimitive.int == 1)
        require(record.getValue("package").jsonPrimitive.content == FgoJpFormatProfile.GAME_PACKAGE)
        val originals = record.getValue("files").jsonArray.map { item ->
            val entry = item.jsonObject
            val name = entry.getValue("path").jsonPrimitive.content
            require(name == FgoJpFormatProfile.MANIFEST_NAME || name.matches(Regex("[0-9a-f]{40}\\.bin")))
            val file = confined(directory, File(directory, name))
            val original = OriginalAsset.capture(GameAssetPath.parse(name), bounded(file))
            require(original.fingerprint.sha256.hex == entry.getValue("sha256").jsonPrimitive.content)
            require(original.bytes.size.toLong() == entry.getValue("size_bytes").jsonPrimitive.long)
            name to original
        }
        require(originals.map { it.first }.distinct().size == originals.size)
        return originals.toMap().also {
            require(it.getValue(FgoJpFormatProfile.MANIFEST_NAME).fingerprint.sha256.hex == record.getValue("manifest_sha256").jsonPrimitive.content)
        }
    }

    private fun bounded(file: File): ByteArray {
        require(file.isFile && file.length() in 1..MAX_NATIVE_BYTES.toLong())
        return file.readBytes().also { require(it.size <= MAX_NATIVE_BYTES) }
    }

    private fun confined(root: File, file: File): File {
        val lexical = file.absoluteFile.toPath().normalize()
        require(lexical != root.toPath() && lexical.startsWith(root.toPath()))
        var current = lexical
        while (current != null) {
            require(!Files.isSymbolicLink(current) && current.toFile().canonicalFile.toPath() == current) { "Linked diagnostic input refused" }
            current = current.parent
        }
        return file.canonicalFile
    }

    private fun printReport(report: JsonObject) = println(Json { prettyPrint = true }.encodeToString(JsonElement.serializer(), report))

    @Test fun `compare preserved game rewritten manifest with pretest capture`() {
        val otherPath = System.getenv("CHAPTER_COMPARE_CAPTURE").orEmpty()
        assumeTrue("Post-playback manifest comparison is opt-in", otherPath.isNotEmpty())
        val before = NativeAcceptanceDiagnostics.manifest(codec, capture(beforePath).getValue(FgoJpFormatProfile.MANIFEST_NAME))
        val after = NativeAcceptanceDiagnostics.manifest(codec, capture(otherPath).getValue(FgoJpFormatProfile.MANIFEST_NAME))
        printReport(buildJsonObject {
            put("scope", "Offline preserved captures only; no device calls or acceptance-policy changes")
            put("before", NativeAcceptanceDiagnostics.summary(before)); put("after", NativeAcceptanceDiagnostics.summary(after))
            put("comparison", NativeAcceptanceDiagnostics.compare(before, after))
        })
    }

    @Test fun `inspect preserved offline unchanged reconstruction alongside official captures`() {
        val proofPath = System.getenv("CHAPTER_COMPARE_PROOF").orEmpty()
        assumeTrue("Preserved native reconstruction comparison is opt-in", proofPath.isNotEmpty())
        val proof = File(proofPath).absoluteFile.normalize()
        val roots = listOf(File(module.parentFile, "story_assets/builds"), File(module, "local-output"))
        require(roots.any { proof.toPath().startsWith(it.absoluteFile.toPath().normalize()) && proof != it.absoluteFile })
        require(proof.name == "proof" && proof.isDirectory)
        confined(requireNotNull(proof.parentFile), proof)
        val before = capture(beforePath)
        val manifest = before.getValue(FgoJpFormatProfile.MANIFEST_NAME)
        val target = codec.entries(manifest).valueOrThrow().single { it.logicalPath == "ScriptActionEncrypt/01" }
        val bundle = before.getValue(target.assetPath.value)
        codec.verifyAsset(manifest, bundle).valueOrThrow()
        val candidateBundle = OriginalAsset.capture(target.assetPath, bounded(confined(proof, File(proof, "no-op/${target.assetPath.value}"))))
        val candidateManifest = OriginalAsset.capture(manifest.fingerprint.path,
            bounded(confined(proof, File(proof, "no-op/${FgoJpFormatProfile.MANIFEST_NAME}"))))
        codec.verifyAsset(candidateManifest, candidateBundle).valueOrThrow()
        val originalUnity = containers.decodeUnity(bundle.bytes).valueOrThrow().toByteArray()
        val candidateUnity = containers.decodeUnity(candidateBundle.bytes).valueOrThrow().toByteArray()
        // Bind the independent-verifier files to these actual encrypted bundle inputs.
        assertContentEquals(originalUnity, bounded(confined(proof, File(proof, "original.unity"))))
        assertContentEquals(candidateUnity, bounded(confined(proof, File(proof, "no-op.unity"))))
        val originalScripts = containers.decode(bundle).valueOrThrow().scripts.map { it.binding to it.payload }
        val candidateScripts = containers.decode(candidateBundle).valueOrThrow().scripts.map { it.binding to it.payload }
        assertEquals(originalScripts, candidateScripts)
        val a = NativeAcceptanceDiagnostics.manifest(codec, manifest)
        val b = NativeAcceptanceDiagnostics.manifest(codec, candidateManifest)
        printReport(buildJsonObject {
            put("scope", "Preserved OFFLINE reconstruction, not proof it is the exact live installed index or that FGO accepts it")
            put("original_bundle_sha256", bundle.fingerprint.sha256.hex)
            put("candidate_bundle_sha256", candidateBundle.fingerprint.sha256.hex)
            put("original_index", NativeAcceptanceDiagnostics.summary(a))
            put("candidate_index", NativeAcceptanceDiagnostics.summary(b))
            put("index_comparison", NativeAcceptanceDiagnostics.compare(a, b))
            put("all_script_identities_and_payloads_equal", originalScripts == candidateScripts)
            put("script_count", originalScripts.size)
            put("original_unity", NativeAcceptanceDiagnostics.unity(originalUnity))
            put("candidate_unity", NativeAcceptanceDiagnostics.unity(candidateUnity))
            val afterPath = System.getenv("CHAPTER_COMPARE_CAPTURE").orEmpty()
            if (afterPath.isNotEmpty()) {
                val after = capture(afterPath)
                val afterManifest = after.getValue(FgoJpFormatProfile.MANIFEST_NAME)
                val afterBundle = after.getValue(target.assetPath.value)
                codec.verifyAsset(afterManifest, afterBundle).valueOrThrow()
                put("post_playback_bundle_equals_original", bundle.bytes == afterBundle.bytes)
                put("post_playback_unity", NativeAcceptanceDiagnostics.unity(containers.decodeUnity(afterBundle.bytes).valueOrThrow().toByteArray()))
            }
            put("native_playback", "NOT TESTED by this offline run")
        })
    }
}
