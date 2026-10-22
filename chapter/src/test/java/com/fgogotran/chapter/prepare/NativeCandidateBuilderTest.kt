package com.fgogotran.chapter.prepare

import com.fgogotran.chapter.*
import com.fgogotran.chapter.codec.*
import com.fgogotran.chapter.compile.SyntheticStoryAssets
import com.fgogotran.chapter.packs.*
import kotlinx.serialization.json.*
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.KeyPairGenerator
import java.util.concurrent.CancellationException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.*

class NativeCandidateBuilderTest {
    @get:Rule val temporary = TemporaryFolder()
    private val fixture = SyntheticStoryAssets()
    private val ids = listOf("0100000010", "0100000011")
    private fun builder(): NativeCandidateBuilder {
        val original = original()
        return NativeCandidateBuilder(fixture.containers, fixture.scripts, fixture.manifests,
            ReviewedNativeBaseline("test-only-baseline", fixture.version, mapOf(original.fingerprint.path.value to
                ReviewedNativeBaseline.Asset("ScriptActionEncrypt/01", original.fingerprint.sha256.hex, original.bytes.size))))
    }
    private fun original() = fixture.asset(linkedMapOf(ids[0] to fixture.encoded(preparationJp), ids[1] to fixture.encoded(preparationJp),
        "UncoveredScript" to fixture.encoded(preparationJp)))
    private fun metadata(candidate: BuiltNativeCandidate) = PreparationFiles.json(candidate.metadata)
    private fun candidate(repository: File): BuiltNativeCandidate {
        val original = original()
        return builder().build(StoryLanguage.ENGLISH, preparationSnapshot(repository, ids), fixture.version,
            fixture.manifest(listOf("ScriptActionEncrypt/01" to original)), listOf(original))
    }

    @Test fun `whole accepted scripts share exactly one rebuilt bundle and every fallback payload is exact`() {
        val repository = temporary.newFolder()
        val original = original()
        val snapshot = preparationSnapshot(repository, ids)
        val manifest = fixture.manifest(listOf("ScriptActionEncrypt/01" to original))
        val candidate = builder().build(StoryLanguage.TRADITIONAL_CHINESE, snapshot, fixture.version, manifest, listOf(original))
        assertEquals(1, candidate.payloads.size)
        assertEquals(2, metadata(candidate).int("translated_scripts"))
        assertEquals(ids, candidate.proofs.single().changedScripts)
        val output = OriginalAsset.capture(original.fingerprint.path, candidate.payloads.values.single().toByteArray())
        val before = fixture.containers.decode(original).valueOrThrow(); val after = fixture.containers.decode(output).valueOrThrow()
        before.scripts.zip(after.scripts).forEach { (a, b) ->
            assertEquals(a.binding, b.binding)
            if (a.binding.scriptId.value == "UncoveredScript") assertEquals(a.payload, b.payload)
            else assertEquals(localized(preparationJp), fixture.plain(b))
        }
        val repeated = builder().build(StoryLanguage.TRADITIONAL_CHINESE, snapshot, fixture.version, manifest, listOf(original))
        assertContentEquals(candidate.metadata, repeated.metadata)
        assertEquals(candidate.payloads, repeated.payloads)
        val destination = NativeCandidateStore.write(repository, candidate)
        assertEquals(destination, NativeCandidateStore.write(repository, repeated))
        NativeCandidateStore.verify(destination)
        assertFalse(File(destination, FgoJpFormatProfile.MANIFEST_NAME).exists())
        assertFalse(metadata(candidate).getValue("installable").jsonPrimitive.boolean)
        File(destination, metadata(candidate).getValue("payloads").jsonArray.single().jsonObject.string("path")).appendText("corrupt")
        assertFails { NativeCandidateStore.verify(destination) }
        assertFails { NativeCandidateStore.write(repository, candidate) } // no repairing corrupt immutable history
    }

    @Test fun `missing regional text produces explicit JP fallback not a partial translation or false coverage`() {
        val repository = temporary.newFolder()
        val ordinary = preparationFetch(ids)
        val snapshot = AtlasSnapshot.download(repository, setOf(100), fetch = AtlasFetch { url, limit ->
            if ("/TW/Script/" in url) null else ordinary.get(url, limit)
        })
        val original = original()
        val candidate = builder().build(StoryLanguage.TRADITIONAL_CHINESE, snapshot, fixture.version,
            fixture.manifest(listOf("ScriptActionEncrypt/01" to original)), listOf(original))
        assertTrue(candidate.payloads.isEmpty()); assertTrue(candidate.proofs.isEmpty())
        assertEquals(0, metadata(candidate).int("translated_scripts"))
        assertEquals(2, metadata(candidate).getValue("coverage").jsonArray.count { it.jsonObject.string("result") == AlignmentReason.REGIONAL_MISSING.name })
        assertEquals(setOf("candidate.json"), NativeCandidateStore.write(repository, candidate).listFiles()!!.map { it.name }.toSet())
    }

    @Test fun `cancellation wrong original and undeclared review do not yield a candidate`() {
        val repository = temporary.newFolder()
        val original = original(); val snapshot = preparationSnapshot(repository, ids)
        val manifest = fixture.manifest(listOf("ScriptActionEncrypt/01" to original))
        assertFailsWith<CancellationException> { builder().build(StoryLanguage.ENGLISH, snapshot, fixture.version, manifest, listOf(original)) { throw CancellationException() } }
        val corrupt = OriginalAsset.capture(original.fingerprint.path, original.bytes.toByteArray() + 0.toByte())
        assertFails { builder().build(StoryLanguage.ENGLISH, snapshot, fixture.version, manifest, listOf(corrupt)) }
        // Even a matching, self-consistent index is not authority to use modified bytes as JP.
        val translated = candidate(repository).payloads.values.single()
        val alreadyChanged = OriginalAsset.capture(original.fingerprint.path, translated.toByteArray())
        val changedIndex = fixture.manifest(listOf("ScriptActionEncrypt/01" to alreadyChanged))
        fixture.manifests.verifyAsset(changedIndex, alreadyChanged).valueOrThrow()
        assertFails { builder().build(StoryLanguage.ENGLISH, snapshot, fixture.version, changedIndex, listOf(alreadyChanged)) }
        assertFails { builder().build(StoryLanguage.ENGLISH, snapshot, fixture.version, manifest, listOf(original), mapOf("Unused" to ReviewedAlignment("Unused",
            "a".repeat(64), "b".repeat(64), "c".repeat(64), listOf("x" to "y"), "d".repeat(64)))) }
    }

    @Test fun `detached signature binds exact canonical metadata but never confers installation approval`() {
        val candidate = candidate(temporary.newFolder())
        val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val signature = NativeCandidateSignature.sign(candidate.metadata, "test-only", pair.private)
        NativeCandidateSignature.verify(candidate.metadata, signature, mapOf("test-only" to pair.public))
        assertFails { NativeCandidateSignature.verify(candidate.metadata, signature, emptyMap()) }
        assertFails { NativeCandidateSignature.verify(candidate.metadata, signature, mapOf("test-only" to KeyPairGenerator.getInstance("Ed25519").generateKeyPair().public)) }
        val changed = JsonObject(metadata(candidate) + ("captured_index_sha256" to JsonPrimitive("f".repeat(64))))
        assertFails { NativeCandidateSignature.verify(PreparationFiles.canonical(changed), signature, mapOf("test-only" to pair.public)) }
        assertFails { NativeCandidateSignature.sign(PreparationFiles.canonical(JsonObject(metadata(candidate) + ("installable" to JsonPrimitive(true)))), "test-only", pair.private) }
        assertFalse(metadata(candidate).getValue("native_playback_verified").jsonPrimitive.boolean)
        assertFails { StoryPackJson.decodeManifest(candidate.metadata) }
        val archive = temporary.newFile("not-a-source-pack.zip")
        ZipOutputStream(archive.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json")); zip.write(candidate.metadata); zip.closeEntry()
        }
        assertFails { StoryPackArchive.read(archive) }
    }

    @Test fun `candidate refuses extra files destinations and unsupported metadata`() {
        val repository = temporary.newFolder(); val candidate = candidate(repository)
        val destination = NativeCandidateStore.write(repository, candidate)
        File(destination, "extra.txt").writeText("not a payload")
        assertFails { NativeCandidateStore.verify(destination) }
        val rows = metadata(candidate).getValue("payloads").jsonArray
        val unsafe = JsonObject(rows[0].jsonObject + ("path" to JsonPrimitive("../outside.bin")))
        assertFails { NativeCandidateStore.validateMetadata(PreparationFiles.canonical(JsonObject(metadata(candidate) + ("payloads" to JsonArray(listOf(unsafe)))))) }
        val unknown = JsonObject(metadata(candidate) + ("run" to JsonPrimitive("anything")))
        assertFails { NativeCandidateStore.validateMetadata(PreparationFiles.canonical(unknown)) }
        val missingFallback = metadata(candidate).getValue("coverage").jsonArray.filter { it.jsonObject.string("script_id") != "UncoveredScript" }
        assertFails { NativeCandidateStore.validateMetadata(PreparationFiles.canonical(JsonObject(metadata(candidate) + ("coverage" to JsonArray(missingFallback))))) }
    }
}
