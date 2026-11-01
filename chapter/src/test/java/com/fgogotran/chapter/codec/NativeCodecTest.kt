package com.fgogotran.chapter.codec

import com.fgogotran.chapter.*
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import kotlin.test.*

/** All data here is synthetic; the separate opt-in test supplies native-format evidence. */
class NativeCodecTest {
    private val envelope = RijndaelEnvelope(AssetBytes.copyOf(ByteArray(32) { it.toByte() }), AssetBytes.copyOf(ByteArray(32) { (it + 32).toByte() }))
    private val scriptCodec = FgoNativeScriptCodec(envelope)
    private val containerCodec = FgoJpContainerCodec(envelope)
    private val manifestCodec = FgoInstalledManifestCodec(envelope)
    private val assetPath = GameAssetPath.parse("3b6e743e6f41fb01a1037cb15169096d6a63198c.bin")
    private val nativeText = "＄01-00-00-00-1-1\r\n[unknown keep 7]\r\n＠テスト\r\nそのままの台詞。\r\n[k]\r\n＠テスト\r\n[#架空用語:かくう]と[%1]と[&彼:彼女]。\r\n[k]\r\n？1：一つ目\r\n＠話者\r\n分岐の台詞。\r\n[k]\r\n？2：二つ目\r\n？！\r\n[end]\r\n"

    private fun encodedScript(text: String = nativeText, compression: TextCompression = TextCompression.BZIP2): AssetBytes =
        AssetBytes.copyOf(Base64.getEncoder().encode(envelope.encrypt(packText(text.toByteArray(Charsets.UTF_8), compression))))
    private fun source(payload: AssetBytes = encodedScript()): NativeScriptSource {
        val asset = OriginalAsset.capture(assetPath, byteArrayOf(1))
        return NativeScriptSource(ScriptAssetBinding(ScriptId.parse("0100000011"), assetPath,
            NativeLocator(NativeFormat("synthetic-container", 1), AssetBytes.copyOf(byteArrayOf(0)))), asset.fingerprint, payload)
    }
    private fun proofPatch(field: NativeTextField, replacement: String = "テスト表示") =
        NativeTextPatch(field.id, Sha256Digest.compute(field.sourceText.toByteArray(Charsets.UTF_8)), replacement)
    private fun manifest(body: String): OriginalAsset {
        val text = "~${crc32(body.toByteArray(Charsets.UTF_8))}\n$body"
        val cipher = envelope.encrypt(packText(text.toByteArray(Charsets.UTF_8), TextCompression.GZIP))
        return OriginalAsset.capture(GameAssetPath.parse("cfb1d36393fd67385e046b084b7cf7ed"),
            ("\uFEFF" + Base64.getEncoder().encodeToString(cipher)).toByteArray(Charsets.UTF_8))
    }

    @Test fun `both observed text compression envelopes round trip`() {
        TextCompression.entries.forEach { compression ->
            val source = source(encodedScript(compression = compression))
            assertEquals(source.payload, scriptCodec.encodeUnchanged(scriptCodec.decode(source).valueOrThrow()).valueOrThrow())
        }
    }
    @Test fun `native ruby placeholders choices and command bytes are preserved`() {
        val source = source()
        val decoded = scriptCodec.decode(source).valueOrThrow()
        assertEquals(2, decoded.fields.count { it.kind == NativeTextKind.CHOICE })
        assertEquals(2, decoded.branches.size)
        val rubyField = decoded.fields.single { it.ruby.isNotEmpty() }
        val ruby = rubyField.ruby.single()
        assertEquals("架空用語", rubyField.sourceText.substring(ruby.baseRange.start, ruby.baseRange.endExclusive))
        assertEquals("かくう", ruby.reading)
        assertEquals(2, rubyField.placeholders.size)
        val field = decoded.fields.first { it.kind == NativeTextKind.DIALOGUE }
        val output = scriptCodec.patchPlainDialogue(source, proofPatch(field)).valueOrThrow()
        assertEquals(nativeText.replace("そのままの台詞。", "テスト表示"), strictUtf8(scriptCodec.decodedBytes(source(output)).valueOrThrow().toByteArray()))
    }
    @Test fun `wrong field hash markup and choice patches are refused`() {
        val source = source()
        val decoded = scriptCodec.decode(source).valueOrThrow()
        val plain = decoded.fields.first { it.kind == NativeTextKind.DIALOGUE }
        assertIs<NativeCodecResult.Invalid>(scriptCodec.patchPlainDialogue(source, NativeTextPatch(plain.id, Sha256Digest.compute(byteArrayOf()), "テスト")))
        decoded.fields.filter { it.ruby.isNotEmpty() || it.kind != NativeTextKind.DIALOGUE }.forEach {
            assertIs<NativeCodecResult.Invalid>(scriptCodec.patchPlainDialogue(source, proofPatch(it)))
        }
    }
    @Test fun `proof replacement cannot introduce script syntax or malformed Unicode`() {
        listOf("[k]", "abc\n", "＠話者", "？1：", "\uD800").forEach {
            assertFailsWith<IllegalArgumentException> { NativeTextPatch(NativeElementId.parse("test"), Sha256Digest.compute(byteArrayOf()), it) }
        }
    }
    @Test fun `invalid script envelope and unverified opening fail closed`() {
        assertIs<NativeCodecResult.Invalid>(scriptCodec.decode(source(AssetBytes.copyOf(byteArrayOf(0)))))
        assertIs<NativeCodecResult.Unsupported>(scriptCodec.decode(source(encodedScript("not a native script"))))
        assertFailsWith<IllegalArgumentException> { envelope.decrypt(ByteArray(31), 100) }
        assertFailsWith<IllegalArgumentException> { gunzipBounded(gzip(ByteArray(100)), 5) }
    }
    @Test fun `manifest maps a logical story asset and updates only verified metadata`() {
        val asset = OriginalAsset.capture(assetPath, ByteArray(80) { it.toByte() })
        val body = "@FgoDataVersion0066,synthetic,baseline\n1,DATA0,80,${crc32(asset.bytes.toByteArray())},ScriptActionEncrypt/01\n0,DATA0,4,5,Other/Fake\n"
        val original = manifest(body)
        assertEquals(assetPath, manifestCodec.entries(original).valueOrThrow().first().assetPath)
        manifestCodec.verifyAsset(original, asset).valueOrThrow()
        val replacement = AssetBytes.copyOf(ByteArray(96) { 3 })
        val patched = OriginalAsset.capture(original.fingerprint.path, manifestCodec.replaceAsset(original, asset, replacement).valueOrThrow().toByteArray())
        manifestCodec.verifyAsset(patched, OriginalAsset.capture(assetPath, replacement.toByteArray())).valueOrThrow()
        assertEquals(manifestCodec.entries(original).valueOrThrow()[1], manifestCodec.entries(patched).valueOrThrow()[1])
        assertEquals(original.bytes, manifestCodec.encodeUnchanged(manifestCodec.decode(original).valueOrThrow()).valueOrThrow())
    }
    @Test fun `manifest rejects incorrect asset baseline and server-style rows`() {
        val original = manifest("@FgoDataVersion0066,x,y\n1,DATA0,1,9,ScriptActionEncrypt/01\n")
        assertIs<NativeCodecResult.Invalid>(manifestCodec.verifyAsset(original, OriginalAsset.capture(assetPath, byteArrayOf(1))))
        assertIs<NativeCodecResult.Unsupported>(manifestCodec.decode(manifest("@FgoDataVersion0066,x,y\n1,DATA0,1,9,ScriptActionEncrypt/01,extra-key\n")))
    }
    @Test fun `index-only reencoding retains complete decoded rows including opaque fields`() {
        val original = manifest("@FgoDataVersion0066,synthetic,baseline\n1,opaque-token,80,17,ScriptActionEncrypt/01\n0,another-token,4,5,Other/Fake\n")
        val changed = OriginalAsset.capture(original.fingerprint.path, manifestCodec.reencodeEquivalentForIndexControl(original).valueOrThrow().toByteArray())
        assertEquals(manifestCodec.diagnosticLayers(original).valueOrThrow().decodedText,
            manifestCodec.diagnosticLayers(changed).valueOrThrow().decodedText)
        assertEquals(manifestCodec.entries(original).valueOrThrow(), manifestCodec.entries(changed).valueOrThrow())
    }
    @Test fun `unknown native payload is not reported as a decoded container`() {
        val original = OriginalAsset.capture(assetPath, ByteArray(32))
        assertIs<NativeCodecResult.Invalid>(containerCodec.decode(original))
        val invalidHeader = ByteArray(100)
        assertFails { UnityFsArchive.decode(invalidHeader) }
    }

    private fun binary(block: DataOutputStream.() -> Unit): ByteArray = ByteArrayOutputStream().also { DataOutputStream(it).use(block) }.toByteArray()
    private fun leInt(value: Int): ByteArray = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
    private fun leLong(value: Long): ByteArray = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array()

    /** Minimal v22 file with one TextAsset and one opaque object that must survive resizing. */
    private fun syntheticSerialized(payload: ByteArray, scriptName: String = "0100000011"): ByteArray {
        val name = scriptName.toByteArray(Charsets.UTF_8)
        val text = ByteArrayOutputStream().apply {
            write(leInt(name.size)); write(name); while (size() % 4 != 0) write(0)
            write(leInt(payload.size)); write(payload); while (size() % 4 != 0) write(0)
        }.toByteArray()
        val secondStart = (text.size + 15) and 15.inv()
        val opaque = byteArrayOf(4, 9, 3, 7)
        val metadata = ByteArrayOutputStream().apply {
            write("6000.0.68f1\u0000".toByteArray()); write(leInt(13)); write(0); write(leInt(2))
            for (type in listOf(49, 1234)) { write(leInt(type)); write(byteArrayOf(0, -1, -1)); write(ByteArray(16)) }
            write(leInt(2)); while (size() % 4 != 0) write(0)
            write(leLong(17)); write(leLong(0)); write(leInt(text.size)); write(leInt(0))
            write(leLong(42)); write(leLong(secondStart.toLong())); write(leInt(opaque.size)); write(leInt(1))
            repeat(3) { write(leInt(0)) }; write(0)
        }.toByteArray()
        val dataOffset = (48 + metadata.size + 15) and 15.inv()
        val size = dataOffset + secondStart + opaque.size
        return binary {
            writeInt(0); writeInt(0); writeInt(22); writeInt(0); writeInt(0)
            writeInt(metadata.size); writeLong(size.toLong()); writeLong(dataOffset.toLong()); writeLong(0)
            write(metadata); write(ByteArray(dataOffset - 48 - metadata.size))
            write(text); write(ByteArray(secondStart - text.size)); write(opaque)
        }
    }

    private fun syntheticArchive(serialized: ByteArray, infoKind: Int = 0, dataKind: Int = 0, blockCount: Int = 1): ByteArray {
        val parts = if (blockCount == 1) listOf(serialized) else listOf(serialized.copyOfRange(0, serialized.size / 2), serialized.copyOfRange(serialized.size / 2, serialized.size))
        val packed = parts.map { UnityFsArchive.compress(it, dataKind, byteArrayOf(0x5d, 0, 0, 8, 0)) }
        val info = binary {
            write(ByteArray(16)); writeInt(parts.size)
            parts.indices.forEach { writeInt(parts[it].size); writeInt(packed[it].size); writeShort(0x40 or dataKind) }
            writeInt(1); writeLong(0); writeLong(serialized.size.toLong()); writeInt(4); write("CAB-synthetic\u0000".toByteArray())
        }
        val packedInfo = UnityFsArchive.compress(info, infoKind)
        val output = ByteArrayOutputStream().apply {
            write(binary { write("UnityFS\u0000".toByteArray()); writeInt(8); write("5.x.x\u00006000.0.68f1\u0000".toByteArray()); writeLong(0); writeInt(packedInfo.size); writeInt(info.size); writeInt(0x240 or infoKind) })
            while (size() % 16 != 0) write(0)
            write(packedInfo); while (size() % 16 != 0) write(0); packed.forEach { write(it) }
        }.toByteArray()
        ByteBuffer.wrap(output).putLong(30, output.size.toLong())
        return output
    }
    @Test fun `rebuild retains compression flags LZMA properties and exact unchanged raw data`() {
        val serialized = syntheticSerialized(encodedScript().toByteArray())
        for ((infoKind, dataKind) in listOf(0 to 0, 3 to 1, 2 to 2, 3 to 3)) {
            val original = syntheticArchive(serialized, infoKind, dataKind)
            val archive = UnityFsArchive.decode(original)
            for (replacement in listOf(serialized, serialized + byteArrayOf(1, 2, 3))) {
                val rebuilt = archive.replaceNode(0, replacement)
                val next = UnityFsArchive.decode(rebuilt)
                assertContentEquals(replacement, next.data)
                val input = Cursor(rebuilt)
                input.skip(38)
                val packedInfoSize = input.i32(); val infoSize = input.i32()
                assertEquals(0x240 or infoKind, input.i32())
                input.align(16)
                val info = Cursor(UnityFsArchive.decompress(input.take(packedInfoSize), infoSize, infoKind))
                info.skip(16); assertEquals(1, info.i32())
                assertEquals(replacement.size, info.i32()); val packedSize = info.i32()
                assertEquals(0x40 or dataKind, info.u16())
                input.align(16)
                if (dataKind == 1) assertContentEquals(byteArrayOf(0x5d, 0, 0, 8, 0), input.bytes.copyOfRange(input.pos, input.pos + 5))
                assertEquals(rebuilt.size - input.pos, packedSize)
            }
        }
    }
    @Test fun `compression validates parameters and observes cancellation between batches`() {
        assertFails { UnityFsArchive.compress(byteArrayOf(1), 1) }
        assertFails { UnityFsArchive.compress(byteArrayOf(1), 1, ByteArray(5)) }
        assertFails { UnityFsArchive.compress(byteArrayOf(1), 4) }
        var checks = 0
        assertFailsWith<java.util.concurrent.CancellationException> {
            UnityFsArchive.compress(ByteArray(200000), 1, byteArrayOf(0x5d, 0, 0, 8, 0)) {
                if (++checks == 3) throw java.util.concurrent.CancellationException()
            }
        }
        assertEquals(3, checks)
    }
    @Test fun `multi-block archives remain readable but cannot silently change packing on rebuild`() {
        val serialized = syntheticSerialized(encodedScript().toByteArray())
        val archive = UnityFsArchive.decode(syntheticArchive(serialized, 3, 1, 2))
        assertContentEquals(serialized, archive.data)
        assertFailsWith<UnsupportedNative> { archive.replaceNode(0, serialized) }
    }
    @Test fun `serialized and archive resizing preserve other object bytes and identities`() {
        val payload = encodedScript().toByteArray()
        val original = syntheticSerialized(payload)
        val parsed = UnityTextAssets.decode(original)
        val replacement = ByteArray(payload.size + 79) { 65 }
        val changed = parsed.replaceText(17, payload, replacement)
        assertContentEquals(replacement, UnityTextAssets.decode(changed).texts.single().payload)
        assertContentEquals(original.takeLast(4).toByteArray(), changed.takeLast(4).toByteArray())
        val archive = UnityFsArchive.decode(syntheticArchive(original))
        val rebuilt = UnityFsArchive.decode(archive.replaceNode(0, changed))
        assertContentEquals(changed, rebuilt.data)
        assertEquals(archive.nodes.map { it.name }, rebuilt.nodes.map { it.name })
        assertFails { parsed.replaceText(17, byteArrayOf(1), replacement) }
    }
    @Test fun `nonnumeric native script identities are retained`() {
        val unity = syntheticArchive(syntheticSerialized(encodedScript().toByteArray(), "WarEpilogue108"))
        val swapped = unity.copyOf()
        for (index in 0 until swapped.size - 1 step 2) {
            val first = swapped[index]
            swapped[index] = (swapped[index + 1].toInt() xor 0xce).toByte()
            swapped[index + 1] = (first.toInt() xor 0xd2).toByte()
        }
        val original = OriginalAsset.capture(assetPath, envelope.encrypt(swapped))
        val decoded = containerCodec.decode(original).valueOrThrow()
        assertEquals("WarEpilogue108", decoded.scripts.single().binding.scriptId.value)
        assertEquals(original.bytes, containerCodec.encodeUnchanged(decoded).valueOrThrow())
    }
    @Test fun `truncated unity archives and serialized objects are rejected`() {
        val serialized = syntheticSerialized(encodedScript().toByteArray())
        val archive = syntheticArchive(serialized)
        listOf(0, 7, 31, 64, archive.size - 1).forEach { size -> assertFails { UnityFsArchive.decode(archive.copyOf(size)) } }
        listOf(0, 16, 47, serialized.size - 1).forEach { size -> assertFails { UnityTextAssets.decode(serialized.copyOf(size)) } }
    }
}
