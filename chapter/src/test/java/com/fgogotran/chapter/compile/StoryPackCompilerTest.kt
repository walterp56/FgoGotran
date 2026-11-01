package com.fgogotran.chapter.compile

import com.fgogotran.chapter.*
import com.fgogotran.chapter.codec.*
import com.fgogotran.chapter.packs.*
import kotlinx.coroutines.*
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.*

class StoryPackCompilerTest {
    @get:Rule val temporary = TemporaryFolder()
    private val fixture = SyntheticStoryAssets()
    private val raw = "＄synthetic\n＠Speaker\nFirst\n[k]\n＠Speaker\nSecond\n[k]\n？1：Choice\n？！\n[end]\n"
    private val group = "ScriptActionEncrypt/01"
    private fun asset() = fixture.asset(linkedMapOf("0100000011" to fixture.encoded(raw), "0100000010" to fixture.encoded(raw.replace("First", "Other first")),
        "Unrelated" to fixture.encoded(raw.replace("First", "Do not touch"))))

    @Test fun `two chapters sharing a script compose once without erasing each other`(): Unit = runBlocking {
        val original = asset(); val originals = listOf(group to original); val manifest = fixture.manifest(originals)
        val source = fixture.source(original)
        val intro = fixture.pack(temporary.root, originals, mapOf("0100000011" to listOf(fixture.change(source, "First", StoryTextToken.Text("Intro translation")))), "intro")
        val fuyuki = fixture.pack(temporary.root, originals, mapOf("0100000011" to listOf(fixture.change(source, "Second", StoryTextToken.Text("Fuyuki translation")))), "fuyuki")
        val result = fixture.compiler().compile(listOf(intro, fuyuki), fixture.version, manifest, listOf(original)).valueOrThrow()
        assertEquals(1, result.rebuiltBundleCount); assertEquals(1, result.changedScriptCount); assertEquals(2, result.requestedFieldCount)
        assertEquals(setOf(intro.archiveSha256, fuyuki.archiveSha256), result.selections.map { it.archiveSha256 }.toSet())
        val changed = OriginalAsset.capture(original.fingerprint.path, result.bundles.single().replacement.toByteArray())
        assertEquals(raw.replace("First", "Intro translation").replace("Second", "Fuyuki translation"), fixture.plain(fixture.source(changed)))
        assertEquals(fixture.source(original, "Unrelated").payload, fixture.source(changed, "Unrelated").payload)
        val reversed = fixture.compiler().compile(listOf(fuyuki, intro), fixture.version, manifest, listOf(original)).valueOrThrow()
        assertEquals(result.manifest, reversed.manifest); assertEquals(result.bundles.single().replacement, reversed.bundles.single().replacement)
        val removedIntro = fixture.compiler().compile(listOf(fuyuki), fixture.version, manifest, listOf(original)).valueOrThrow()
        assertEquals(raw.replace("Second", "Fuyuki translation"), fixture.plain(fixture.source(OriginalAsset.capture(original.fingerprint.path, removedIntro.bundles.single().replacement.toByteArray()))))
    }

    @Test fun `multiple edited scripts share one native rebuild and untouched scripts remain exact`(): Unit = runBlocking {
        val original = asset(); val originals = listOf(group to original)
        val pack = fixture.pack(temporary.root, originals, mapOf(
            "0100000011" to listOf(fixture.change(fixture.source(original), "First", StoryTextToken.Text("A much longer synthetic translation"))),
            "0100000010" to listOf(fixture.change(fixture.source(original, "0100000010"), "Other first", StoryTextToken.Text("短")))
        ))
        val result = fixture.compiler().compile(listOf(pack), fixture.version, fixture.manifest(originals), listOf(original)).valueOrThrow()
        assertEquals(1, result.rebuiltBundleCount); assertEquals(2, result.changedScriptCount)
        val changed = OriginalAsset.capture(original.fingerprint.path, result.bundles.single().replacement.toByteArray())
        assertEquals(raw.replace("First", "A much longer synthetic translation"), fixture.plain(fixture.source(changed)))
        assertEquals(raw.replace("First", "短"), fixture.plain(fixture.source(changed, "0100000010")))
        assertEquals(fixture.source(original, "Unrelated").payload, fixture.source(changed, "Unrelated").payload)
    }

    @Test fun `multiple bundles update only their own index metadata`(): Unit = runBlocking {
        val original = asset()
        val second = fixture.asset(mapOf("0200000011" to fixture.encoded(raw)), "ScriptActionEncrypt/02")
        val originals = listOf(group to original, "ScriptActionEncrypt/02" to second)
        val manifest = fixture.manifest(originals)
        val pack = fixture.pack(temporary.root, originals, mapOf(
            "0100000011" to listOf(fixture.change(fixture.source(original), "First", StoryTextToken.Text("甲"))),
            "0200000011" to listOf(fixture.change(fixture.source(second, "0200000011"), "Second", StoryTextToken.Text("乙")))
        ))
        val result = fixture.compiler().compile(listOf(pack), fixture.version, manifest, listOf(second, original)).valueOrThrow()
        assertEquals(2, result.rebuiltBundleCount); assertEquals(2, result.changedScriptCount)
        val before = fixture.manifests.entries(manifest).valueOrThrow()
        val afterManifest = OriginalAsset.capture(manifest.fingerprint.path, result.manifest.toByteArray())
        val after = fixture.manifests.entries(afterManifest).valueOrThrow()
        assertEquals(before.last(), after.last())
        result.bundles.forEach { fixture.manifests.verifyAsset(afterManifest, OriginalAsset.capture(it.original.path, it.replacement.toByteArray())).valueOrThrow() }
        assertEquals(before.map { it.logicalPath to it.installed }, after.map { it.logicalPath to it.installed })
    }

    @Test fun `no-op packs retain exact bundle and index bytes`(): Unit = runBlocking {
        val original = asset(); val originals = listOf(group to original); val manifest = fixture.manifest(originals)
        val pack = fixture.pack(temporary.root, originals, mapOf("0100000011" to listOf(fixture.change(fixture.source(original), "First", StoryTextToken.Text("First")))))
        val result = fixture.compiler().compile(listOf(pack), fixture.version, manifest, listOf(original)).valueOrThrow()
        assertEquals(0, result.rebuiltBundleCount); assertEquals(0, result.changedScriptCount)
        assertEquals(original.bytes, result.bundles.single().replacement); assertEquals(manifest.bytes, result.manifest)
        assertFailsWith<UnsupportedOperationException> { (result.bundles as MutableList).clear() }
    }

    @Test fun `conflicts duplicate chapters wrong game version and incomplete original sets fail closed`(): Unit = runBlocking {
        val original = asset(); val originals = listOf(group to original); val manifest = fixture.manifest(originals)
        val edits = mapOf("0100000011" to listOf(fixture.change(fixture.source(original), "First", StoryTextToken.Text("New"))))
        val intro = fixture.pack(temporary.root, originals, edits, "intro")
        val overlap = fixture.pack(temporary.root, originals, edits, "other")
        val anotherLanguage = fixture.pack(temporary.root, originals, edits, "intro", StoryLanguage.ENGLISH)
        assertIs<NativeCodecResult.Invalid>(fixture.compiler().compile(listOf(intro, overlap), fixture.version, manifest, listOf(original)))
        assertIs<NativeCodecResult.Invalid>(fixture.compiler().compile(listOf(intro, anotherLanguage), fixture.version, manifest, listOf(original)))
        assertIs<NativeCodecResult.Invalid>(fixture.compiler().compile(listOf(intro), StoryGameVersion("2.139.6", 503), manifest, listOf(original)))
        assertIs<NativeCodecResult.Invalid>(fixture.compiler().compile(listOf(intro), fixture.version, manifest, emptyList()))
        assertIs<NativeCodecResult.Invalid>(fixture.compiler().compile(List(33) { intro }, fixture.version, manifest, listOf(original)))
        assertIs<NativeCodecResult.Invalid>(fixture.compiler().compile(listOf(intro), fixture.version, manifest, List(17) { original }))
        val changedBytes = original.bytes.toByteArray().also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertIs<NativeCodecResult.Invalid>(fixture.compiler().compile(listOf(intro), fixture.version, manifest, listOf(OriginalAsset.capture(original.fingerprint.path, changedBytes))))
        val wrongManifest = fixture.manifest(listOf(group to fixture.asset(mapOf("0100000011" to fixture.encoded(raw.replace("First", "Different original"))))))
        assertIs<NativeCodecResult.Invalid>(fixture.compiler().compile(listOf(intro), fixture.version, wrongManifest, listOf(original)))
    }

    @Test fun `cancelled compilation never returns a trusted result`(): Unit = runBlocking {
        val original = asset(); val originals = listOf(group to original)
        val pack = fixture.pack(temporary.root, originals, mapOf("0100000011" to listOf(fixture.change(fixture.source(original), "First", StoryTextToken.Text("New")))))
        val cancelled = Job().apply { cancel() }
        assertFailsWith<CancellationException> {
            withContext(cancelled) { fixture.compiler().compile(listOf(pack), fixture.version, fixture.manifest(originals), listOf(original)) }
        }
        assertEquals(raw, fixture.plain(fixture.source(original)))
    }

    @Test fun `valid source packages with wrong native script or field baselines cannot compile`(): Unit = runBlocking {
        val original = asset(); val originals = listOf(group to original); val manifest = fixture.manifest(originals)
        val source = fixture.source(original)
        val change = fixture.change(source, "First", StoryTextToken.Text("New"))
        val edits = mapOf("0100000011" to listOf(change))
        val wrongPayload = fixture.pack(temporary.root, originals, edits, descriptorTransform = { it.copy(originalPayloadSha256 = "0".repeat(64)) })
        val wrongDecoded = fixture.pack(temporary.root, originals, edits, descriptorTransform = { it.copy(originalDecodedSha256 = "0".repeat(64)) })
        val wrongField = fixture.pack(temporary.root, originals, mapOf("0100000011" to listOf(
            StoryTextChange(change.fieldId, change.kind, "0".repeat(64), change.tokens)
        )))
        listOf(wrongPayload, wrongDecoded, wrongField).forEach { pack ->
            assertIs<NativeCodecResult.Invalid>(fixture.compiler().compile(listOf(pack), fixture.version, manifest, listOf(original)))
        }
        assertEquals(raw, fixture.plain(fixture.source(original)))
    }
}
