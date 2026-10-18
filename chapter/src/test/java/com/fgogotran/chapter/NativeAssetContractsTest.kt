package com.fgogotran.chapter

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame

class NativeAssetContractsTest {
    private val format = NativeFormat("synthetic-script", 1)
    private val original = OriginalAsset.capture(GameAssetPath.parse("source.bin"), byteArrayOf(4, 5, 6))
    private val binding = ScriptAssetBinding(ScriptId.parse("script"), original.fingerprint.path, locator(1))
    private val source = NativeScriptSource(binding, original.fingerprint, AssetBytes.copyOf(byteArrayOf(7, 8)))

    @Test
    fun `locator schema is explicit versioned and owns opaque bytes`() {
        val payload = byteArrayOf(1, 2)
        val locator = NativeLocator(format, AssetBytes.copyOf(payload))
        payload[0] = 3
        locator.payload.toByteArray()[0] = 4
        assertEquals(AssetBytes.copyOf(byteArrayOf(1, 2)), locator.payload)
        assertEquals(0, NativeLocator(format, AssetBytes.copyOf(byteArrayOf())).payload.size)
        assertFailsWith<IllegalArgumentException> { NativeFormat("", 1) }
        assertFailsWith<IllegalArgumentException> { NativeFormat("synthetic", 0) }
        assertFailsWith<IllegalArgumentException> { NativeFormat("synthetic", -1) }
        assertFailsWith<IllegalArgumentException> { NativeElementId.parse(" id ") }
    }

    @Test
    fun `decoded text retains exact source and explicit ruby placeholder identities`() {
        val text = "  力ルデア\n大令呪 {player}  "
        val baseStart = text.indexOf("大令呪")
        val placeholderStart = text.indexOf("{player}")
        val ruby = NativeRubySpan(id("ruby"), Utf16TextRange(baseStart, baseStart + 3), "シリウスライト", locator(2))
        val placeholder = NativePlaceholderSpan(id("player"), Utf16TextRange(placeholderStart, placeholderStart + 8), locator(3))
        val fields = mutableListOf(NativeTextField(id("dialogue"), NativeTextKind.DIALOGUE, locator(4), text, listOf(ruby), listOf(placeholder)))
        val targets = mutableListOf(locator(6))
        val branch = NativeBranchIdentity(id("branch"), locator(5), targets)
        val document = DecodedNativeScript(source, format, fields, listOf(branch))
        fields.clear()
        targets.clear()
        assertEquals(text, document.fields.single().sourceText)
        assertEquals("大令呪", text.substring(ruby.baseRange.start, ruby.baseRange.endExclusive))
        assertEquals("シリウスライト", document.fields.single().ruby.single().reading)
        assertEquals(id("player"), document.fields.single().placeholders.single().id)
        assertEquals(1, document.branches.single().targets.size)
        assertSame(source, document.source)
        assertEquals(AssetBytes.copyOf(byteArrayOf(7, 8)), document.source.payload)
        assertFailsWith<UnsupportedOperationException> { (document.fields as MutableList<*>).clear() }
        assertFailsWith<UnsupportedOperationException> { (document.fields.single().ruby as MutableList<*>).clear() }
        assertFailsWith<UnsupportedOperationException> { (branch.targets as MutableList<*>).clear() }
    }

    @Test
    fun `text ranges cannot be invalid out of bounds or split surrogate pairs`() {
        assertFailsWith<IllegalArgumentException> { Utf16TextRange(-1, 1) }
        assertFailsWith<IllegalArgumentException> { Utf16TextRange(2, 1) }
        assertFailsWith<IllegalArgumentException> { ruby(Utf16TextRange(0, 0)) }
        assertFailsWith<IllegalArgumentException> { field("一", listOf(ruby(Utf16TextRange(0, 2)))) }
        val emoji = "\uD83D\uDE00"
        assertFailsWith<IllegalArgumentException> { field(emoji, listOf(ruby(Utf16TextRange(0, 1)))) }
        assertFailsWith<IllegalArgumentException> { field(emoji, listOf(ruby(Utf16TextRange(1, 2)))) }
        assertEquals(emoji, field(emoji, listOf(ruby(Utf16TextRange(0, 2)))).sourceText)
        assertEquals("", field("", placeholders = listOf(NativePlaceholderSpan(id("anchor"), Utf16TextRange(0, 0), locator(3)))).sourceText)
    }

    @Test
    fun `decoded structures reject duplicate identity and wrong locator provenance`() {
        val ruby = ruby(Utf16TextRange(0, 1))
        assertFailsWith<IllegalArgumentException> { field("一", listOf(ruby, ruby)) }
        assertFailsWith<IllegalArgumentException> {
            field("一", listOf(ruby), listOf(NativePlaceholderSpan(ruby.id, Utf16TextRange(0, 1), locator(3))))
        }
        val wrong = NativeLocator(NativeFormat("other", 1), AssetBytes.copyOf(byteArrayOf(1)))
        assertFailsWith<IllegalArgumentException> { field("一", listOf(ruby.copy(locator = wrong))) }
        assertFailsWith<IllegalArgumentException> { NativeBranchIdentity(id("branch"), locator(1), listOf(wrong)) }
        val field = field("一")
        assertFailsWith<IllegalArgumentException> { DecodedNativeScript(source, format, listOf(field, field)) }
        assertFailsWith<IllegalArgumentException> { DecodedNativeScript(source, wrong.format, listOf(field)) }
        val branch = NativeBranchIdentity(id("branch"), locator(1), emptyList())
        assertFailsWith<IllegalArgumentException> { DecodedNativeScript(source, format, listOf(field), listOf(branch, branch)) }
        assertFailsWith<IllegalArgumentException> {
            NativeScriptSource(binding, original.fingerprint.copy(path = GameAssetPath.parse("other.bin")), source.payload)
        }
    }

    @Test
    fun `container and manifest retain originals rather than assuming successful reconstruction`() {
        val scripts = mutableListOf(source)
        val container = DecodedNativeContainer(original, format, scripts)
        scripts.clear()
        assertSame(original, container.original)
        assertEquals(1, container.scripts.size)
        assertFailsWith<IllegalArgumentException> { DecodedNativeContainer(original, format, listOf(source, source)) }
        val otherFingerprint = original.fingerprint.copy(sha256 = Sha256Digest.compute(byteArrayOf(9)))
        assertFailsWith<IllegalArgumentException> {
            DecodedNativeContainer(original, format, listOf(NativeScriptSource(binding, otherFingerprint, source.payload)))
        }
        val entry = NativeManifestEntry(id("entry"), original.fingerprint.path, locator(2))
        val entries = mutableListOf(entry)
        val manifest = DecodedNativeManifest(original, format, entries)
        entries.clear()
        assertSame(original, manifest.original)
        assertEquals(listOf(entry), manifest.entries)
        assertFailsWith<IllegalArgumentException> { DecodedNativeManifest(original, format, listOf(entry, entry)) }
        assertFailsWith<IllegalArgumentException> { DecodedNativeManifest(original, NativeFormat("other", 1), listOf(entry)) }
        assertFailsWith<UnsupportedOperationException> { (container.scripts as MutableList<*>).clear() }
        assertFailsWith<UnsupportedOperationException> { (manifest.entries as MutableList<*>).clear() }
    }

    @Test
    fun `codec contracts can explicitly reject unknown data without returning copied bytes as success`() {
        val unsupported = NativeCodecResult.Unsupported("No verified codec for this format")
        val codec = object : NativeScriptCodec {
            override val format = this@NativeAssetContractsTest.format
            override fun decode(source: NativeScriptSource): NativeCodecResult<DecodedNativeScript> = unsupported
            override fun encodeUnchanged(document: DecodedNativeScript): NativeCodecResult<AssetBytes> = unsupported
        }
        assertIs<NativeCodecResult.Unsupported>(codec.decode(source))
        assertIs<NativeCodecResult.Unsupported>(codec.encodeUnchanged(DecodedNativeScript(source, format, emptyList())))
        assertFailsWith<IllegalArgumentException> { NativeCodecResult.Unsupported("") }
        assertFailsWith<IllegalArgumentException> { NativeCodecResult.Invalid(" ") }
    }

    private fun id(value: String) = NativeElementId.parse(value)
    private fun locator(value: Int) = NativeLocator(format, AssetBytes.copyOf(byteArrayOf(value.toByte())))
    private fun ruby(range: Utf16TextRange) = NativeRubySpan(id("ruby"), range, "reading", locator(2))
    private fun field(text: String, ruby: Collection<NativeRubySpan> = emptyList(), placeholders: Collection<NativePlaceholderSpan> = emptyList()) =
        NativeTextField(id("field"), NativeTextKind.DIALOGUE, locator(1), text, ruby, placeholders)
}
