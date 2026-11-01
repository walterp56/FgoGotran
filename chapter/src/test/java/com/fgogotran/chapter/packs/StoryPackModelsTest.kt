package com.fgogotran.chapter.packs

import kotlin.test.*

class StoryPackModelsTest {
    private val hash = StoryPackTestData.digest

    @Test fun `round trip typed ruby placeholders and line breaks without raw native commands`() {
        val patch = StoryScriptPatch("0100000011", listOf(StoryTextChange("dialogue:10", StoryFieldKind.DIALOGUE, hash,
            listOf(StoryTextToken.Text("Synthetic "), StoryTextToken.Ruby("星見台", "カルデア"),
                StoryTextToken.LineBreak, StoryTextToken.Placeholder("%1"), StoryTextToken.Conditional("first", "second")))))
        val encoded = StoryPackJson.encodePatch(patch)
        assertContentEquals(encoded, StoryPackJson.encodePatch(StoryPackJson.decodePatch(encoded)))
        val manifest = StoryPackTestData.manifest(patch)
        assertContentEquals(StoryPackJson.encodeManifest(manifest), StoryPackJson.encodeManifest(StoryPackJson.decodeManifest(StoryPackJson.encodeManifest(manifest))))
    }

    @Test fun `models own their mutable input collections`() {
        val tokens = mutableListOf<StoryTextToken>(StoryTextToken.Text("Synthetic"))
        val change = StoryTextChange("dialogue:1", StoryFieldKind.DIALOGUE, hash, tokens)
        val changes = mutableListOf(change); val patch = StoryScriptPatch("1", changes)
        tokens.clear(); changes.clear()
        assertEquals(1, change.tokens.size); assertEquals(1, patch.changes.size)
        assertFailsWith<UnsupportedOperationException> { (change.tokens as MutableList).clear() }
        assertFailsWith<UnsupportedOperationException> { (patch.changes as MutableList).clear() }
    }

    @Test fun `identity language Unicode and raw command mistakes are rejected`() {
        listOf("../escape", "Absolute", "a/b", " a", "a\\b", "a:1", "").forEach { assertFails { StoryPackPolicy.id(it) } }
        listOf("ja", "TW", "zh", "ZH-HANT").forEach { assertFails { StoryLanguage.parse(it) } }
        listOf("[scene 1]", "text\nnext", "\uD800", "\uDC00", "").forEach { assertFails { StoryTextToken.Text(it) } }
        assertEquals("？", StoryTextToken.Text("？").value) // Punctuation is not an OCR/command heuristic.
        assertEquals("😀", StoryTextToken.Text("😀").value)
        assertFails { StoryTextToken.Ruby("base:other", "reading") }
        assertFails { StoryTextToken.Placeholder("[&player]") }
        assertFails { StoryTextToken.Conditional("", "second") }
        assertFails { StoryTextToken.Conditional("first:extra", "second") }
        assertFails { StoryPackPolicy.digest("A".repeat(64)) }
        assertFails { StoryTextChange("choice:1", StoryFieldKind.DIALOGUE, hash, listOf(StoryTextToken.Text("x"))) }
        assertFails { StoryTextChange("dialogue:1", StoryFieldKind.DIALOGUE, hash, listOf(StoryTextToken.LineBreak)) }
        assertFails { StoryTextChange("dialogue:1", StoryFieldKind.DIALOGUE, hash, List(129) { StoryTextToken.Text("x") }) }
        assertFails { StoryScriptPatch("1", listOf(StoryPackTestData.patch().changes.single(), StoryPackTestData.patch().changes.single())) }
    }

    @Test fun `unknown properties schemas tokens malformed UTF8 and duplicate JSON keys fail closed`() {
        val manifest = StoryPackJson.encodeManifest(StoryPackTestData.manifest()).toString(Charsets.UTF_8)
        listOf(
            manifest.replace("\"schema_version\":1", "\"schema_version\":2"),
            manifest.replace("\"schema_version\":1", "\"schema_version\":\"1\""),
            manifest.replace("\"schema_version\":1", "\"schema_version\":1,\"schema_version\":1"),
            manifest.replace("\"schema_version\":1", "\"schema_version\":1,\"schema\\u005fversion\":1"),
            manifest.replace("\"schema_version\":1", "\"schema_version\":1,\"extra\":true"),
            manifest.replace("\"revision\":1", "\"revision\":2147483648"),
            manifest.replace("com.aniplex.fategrandorder", "other.game"),
            manifest.replace(StoryPackPolicy.CODEC_PROFILE, "unknown"),
            manifest.replace("patches/0100000011.json", "../0100000011.json"),
            "\uFEFF$manifest", "$manifest trailing", "[".repeat(26) + "0" + "]".repeat(26)
        ).forEach { invalid -> assertFails { StoryPackJson.decodeManifest(invalid.toByteArray(Charsets.UTF_8)) } }
        assertFails { StoryPackJson.decodeManifest(byteArrayOf(0xc3.toByte(), 0x28)) }
        assertFails { StoryPackJson.decodeManifest(ByteArray(StoryPackPolicy.MAX_JSON + 1)) }
        val patch = StoryPackJson.encodePatch(StoryPackTestData.patch()).toString(Charsets.UTF_8)
        assertFails { StoryPackJson.decodePatch(patch.replace("\"type\":\"text\"", "\"type\":\"command\"").toByteArray()) }
        assertFails { StoryPackJson.decodePatch(patch.replace("\"value\":", "\"native\":true,\"value\":").toByteArray()) }
    }

    @Test fun `manifest provenance and shared bundle baselines must agree`() {
        val manifest = StoryPackTestData.manifest()
        fun copy(scripts: List<StoryScriptDescriptor> = manifest.scripts, sources: List<StorySource> = manifest.sources) =
            StoryPackManifest(manifest.packId, manifest.revision, manifest.chapterId, manifest.titles, manifest.language,
                manifest.coverage, manifest.questIds, manifest.gameVersions, scripts, sources)
        assertFails { copy(sources = manifest.sources.take(1)) }
        assertFails { copy(sources = manifest.sources + manifest.sources.first()) }
        assertFails { copy(sources = manifest.sources.map { if (it.region == "TW") it.copy(region = "NA", url = it.url.replace("/TW/", "/NA/")) else it }) }
        val other = manifest.scripts.single().copy(scriptId = "0100000010", originalAssetSha256 = "1".repeat(64))
        assertFails { copy(scripts = manifest.scripts + other, sources = manifest.sources + manifest.sources.map {
            it.copy(scriptId = "0100000010", url = it.url.replace("0100000011", "0100000010"))
        }) }
        assertFails { StorySource("TW", "1", hash, 1, "https://static.atlasacademy.io.evil.test/TW/Script/01/1.txt") }
    }

    @Test fun `catalogue supports revisions while preventing ambiguous chapter-language identity`() {
        fun entry(revision: Int, pack: String = "intro-hant", chapter: String = "intro") = StoryCatalogueEntry(pack, revision, chapter,
            StoryLanguage.TRADITIONAL_CHINESE, StoryCoverage.SAMPLE,
            StoryCatalogueSummary(mapOf("en" to "Opening sample"), listOf(StoryGameVersion("2.139.5", 502)), 1, 1), hash, 100)
        val catalogue = StoryCatalogue(listOf(entry(1), entry(2)))
        assertEquals(2, catalogue.latest().single().revision)
        assertContentEquals(StoryPackJson.encodeCatalogue(catalogue), StoryPackJson.encodeCatalogue(StoryPackJson.decodeCatalogue(StoryPackJson.encodeCatalogue(catalogue))))
        assertFails { StoryCatalogue(listOf(entry(1), entry(1))) }
        assertFails { StoryCatalogue(listOf(entry(1), entry(2, chapter = "fuyuki"))) }
        assertFails { StoryCatalogue(listOf(entry(1), entry(2, pack = "another-hant"))) }
        val encoded = StoryPackJson.encodeCatalogue(catalogue).toString(Charsets.UTF_8)
        assertFails { StoryPackJson.decodeCatalogue(encoded.replace("packs/intro-hant/1.fgostory.zip", "../evil.zip").toByteArray()) }
    }
}
