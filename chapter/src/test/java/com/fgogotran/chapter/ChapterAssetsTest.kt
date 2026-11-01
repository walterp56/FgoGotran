package com.fgogotran.chapter

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class ChapterAssetsTest {
    @Test
    fun `asset paths are canonical relative locations and never URIs or traversal`() {
        assertEquals("files/data/d713/a_42.bin", GameAssetPath.parse("files/data/d713/a_42.bin").value)
        assertEquals("asset.bin", GameAssetPath.parse("asset.bin").toString())
        for (path in listOf(
            "", " ", "/asset.bin", "//root/asset.bin", "a/", "a//b", ".", "..", "a/../b", "a/./b",
            "C:/asset.bin", "C:asset.bin", "a\\b", "\\\\server\\asset.bin", "https://example.test/a",
            "content://authority/a", "%2e%2e/a", "a%2fb", "a?b", "a#b", " a", "a ", "a/ b", "a\nb"
        )) {
            assertFailsWith<IllegalArgumentException>(path) { GameAssetPath.parse(path) }
        }
    }

    @Test
    fun `SHA256 validates complete hex and canonicalizes letter case`() {
        val emptyDigest = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        assertEquals(emptyDigest, Sha256Digest.compute(byteArrayOf()).hex)
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            Sha256Digest.compute("abc".toByteArray()).hex
        )
        assertEquals(Sha256Digest.parse(emptyDigest), Sha256Digest.parse(emptyDigest.uppercase()))
        for (digest in listOf("", "0".repeat(63), "0".repeat(65), "g".repeat(64), " " + "0".repeat(63))) {
            assertFailsWith<IllegalArgumentException> { Sha256Digest.parse(digest) }
        }
    }

    @Test
    fun `original bytes and digest do not change through caller arrays`() {
        val supplied = byteArrayOf(0, 1, 2, -1)
        val expected = supplied.copyOf()
        val original = OriginalAsset.capture(path("a.bin"), supplied)
        supplied[0] = 99
        original.bytes.toByteArray()[1] = 99
        assertEquals(AssetBytes.copyOf(expected), original.bytes)
        assertEquals(Sha256Digest.compute(expected), original.fingerprint.sha256)
        assertEquals(4, original.bytes.size)
        assertEquals(4L, original.fingerprint.sizeBytes)
        assertEquals(AssetBytes.copyOf(expected).hashCode(), original.bytes.hashCode())
        assertNotEquals(AssetBytes.copyOf(supplied), original.bytes)
    }

    @Test
    fun `identifiers are opaque but must not be blank padded or controlled`() {
        assertEquals("chapter:one", ChapterId.parse("chapter:one").value)
        assertEquals("script/opaque-1", ScriptId.parse("script/opaque-1").value)
        for (id in listOf("", " ", " padded", "padded ", "control\nid", "control\u0000id")) {
            assertFailsWith<IllegalArgumentException> { ChapterId.parse(id) }
            assertFailsWith<IllegalArgumentException> { ScriptId.parse(id) }
        }
    }

    @Test
    fun `baselines reject duplicate assets and impossible sizes`() {
        val fingerprint = asset("shared.bin").fingerprint
        assertFailsWith<IllegalArgumentException> { OriginalAssetBaseline(emptyList()) }
        assertFailsWith<IllegalArgumentException> { OriginalAssetBaseline(listOf(fingerprint, fingerprint)) }
        assertFailsWith<IllegalArgumentException> { fingerprint.copy(sizeBytes = -1) }
        assertNull(OriginalAssetBaseline(listOf(fingerprint)).fingerprintFor(path("absent.bin")))
    }

    @Test
    fun `plan permits shared assets and multiple exact bindings without assuming one chapter per file`() {
        val first = ScriptId.parse("one")
        val second = ScriptId.parse("two")
        val shared = asset("shared.bin")
        val extra = asset("extra.bin")
        val chapter = ChapterDefinition(ChapterId.parse("chapter"), listOf(first, second))
        val bindings = listOf(
            ScriptAssetBinding(first, shared.fingerprint.path, locator(1)),
            ScriptAssetBinding(second, shared.fingerprint.path, locator(2)),
            ScriptAssetBinding(second, extra.fingerprint.path, locator(3))
        )
        val plan = ChapterAssetPlan(chapter, bindings, OriginalAssetBaseline(listOf(shared.fingerprint, extra.fingerprint)))
        assertEquals(bindings, plan.bindings)
        assertEquals(listOf(first, second), plan.chapter.scriptIds)
    }

    @Test
    fun `plan rejects missing extra duplicate or unbaselined bindings`() {
        val script = ScriptId.parse("one")
        val chapter = ChapterDefinition(ChapterId.parse("chapter"), listOf(script))
        val original = asset("a.bin")
        val baseline = OriginalAssetBaseline(listOf(original.fingerprint))
        val binding = ScriptAssetBinding(script, original.fingerprint.path, locator(1))
        assertFailsWith<IllegalArgumentException> { ChapterAssetPlan(chapter, emptyList(), baseline) }
        assertFailsWith<IllegalArgumentException> { ChapterAssetPlan(chapter, listOf(binding, binding), baseline) }
        assertFailsWith<IllegalArgumentException> {
            ChapterAssetPlan(chapter, listOf(binding.copy(scriptId = ScriptId.parse("other"))), baseline)
        }
        assertFailsWith<IllegalArgumentException> {
            ChapterAssetPlan(chapter, listOf(binding.copy(assetPath = path("other.bin"))), baseline)
        }
        assertFailsWith<IllegalArgumentException> {
            ChapterAssetPlan(chapter, listOf(binding), OriginalAssetBaseline(listOf(original.fingerprint, asset("extra.bin").fingerprint)))
        }
        assertFailsWith<IllegalArgumentException> { ChapterDefinition(ChapterId.parse("empty"), emptyList()) }
        assertFailsWith<IllegalArgumentException> { ChapterDefinition(ChapterId.parse("duplicate"), listOf(script, script)) }
    }

    @Test
    fun `chapter plan and baseline own immutable collection snapshots`() {
        val scripts = mutableListOf(ScriptId.parse("one"))
        val chapter = ChapterDefinition(ChapterId.parse("chapter"), scripts)
        val originals = mutableListOf(asset("a.bin").fingerprint)
        val baseline = OriginalAssetBaseline(originals)
        val bindings = mutableListOf(ScriptAssetBinding(scripts.single(), originals.single().path, locator(1)))
        val plan = ChapterAssetPlan(chapter, bindings, baseline)
        scripts.clear()
        originals.clear()
        bindings.clear()
        assertEquals(1, chapter.scriptIds.size)
        assertEquals(1, baseline.assets.size)
        assertEquals(1, plan.bindings.size)
        assertFailsWith<UnsupportedOperationException> { (chapter.scriptIds as MutableList<*>).clear() }
        assertFailsWith<UnsupportedOperationException> { (baseline.assets as MutableList<*>).clear() }
        assertFailsWith<UnsupportedOperationException> { (plan.bindings as MutableList<*>).clear() }
    }

    private fun path(value: String) = GameAssetPath.parse(value)
    private fun asset(value: String) = OriginalAsset.capture(path(value), byteArrayOf(1, 2, 3))
    private fun locator(value: Int) = NativeLocator(NativeFormat("synthetic-container", 1), AssetBytes.copyOf(byteArrayOf(value.toByte())))
}
