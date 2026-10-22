package com.fgogotran.chapter.codec

import com.fgogotran.chapter.*
import com.fgogotran.chapter.compile.SyntheticStoryAssets
import kotlinx.serialization.json.*
import java.util.Base64
import kotlin.test.*

class NativeAcceptanceDiagnosticsTest {
    private val fixture = SyntheticStoryAssets()
    private val body = "@FgoDataVersion0066,synthetic,baseline\n1,OPAQUE_A,80,7,ScriptActionEncrypt/01\n0,OPAQUE_B,4,5,Other/Fake\n"
    private fun manifest(text: String = body, mutateGzip: (ByteArray) -> Unit = {}): NativeAcceptanceDiagnostics.Manifest {
        val raw = "~${crc32(text.toByteArray(Charsets.UTF_8))}\n$text"
        val gzip = packText(raw.toByteArray(Charsets.UTF_8), TextCompression.GZIP).also(mutateGzip)
        val original = OriginalAsset.capture(GameAssetPath.parse(FgoJpFormatProfile.MANIFEST_NAME),
            Base64.getEncoder().encode(fixture.envelope.encrypt(gzip)))
        return NativeAcceptanceDiagnostics.manifest(fixture.manifests, original)
    }
    private fun JsonObject.bool(key: String) = getValue(key).jsonPrimitive.boolean
    private fun JsonObject.number(key: String) = getValue(key).jsonPrimitive.int

    @Test fun `identical validated files compare exactly equal`() {
        val a = manifest()
        val report = NativeAcceptanceDiagnostics.compare(a, manifest())
        assertTrue(report.bool("file_bytes_equal")); assertTrue(report.bool("decoded_bytes_equal"))
        assertTrue(report.bool("raw_asset_rows_equal")); assertEquals(0, report.number("raw_changed_paths"))
    }
    @Test fun `opaque-only changes cannot hide behind equal parsed entries`() {
        val report = NativeAcceptanceDiagnostics.compare(manifest(), manifest(body.replace("OPAQUE_A", "OPAQUE_NEW")))
        assertEquals(0, report.number("typed_changed_paths")); assertEquals(1, report.number("raw_changed_paths"))
        assertFalse(report.bool("decoded_bytes_equal"))
        assertEquals(listOf(0, 1, 0, 0, 0), report.getValue("changed_rows_by_column_0_to_4").jsonArray.map { it.jsonPrimitive.int })
    }
    @Test fun `row order is checked even when all five columns agree by path`() {
        val lines = body.lines()
        val changed = listOf(lines[0], lines[2], lines[1], "").joinToString("\n")
        val report = NativeAcceptanceDiagnostics.compare(manifest(), manifest(changed))
        assertEquals(0, report.number("raw_changed_paths")); assertFalse(report.bool("asset_path_order_equal"))
        assertFalse(report.bool("raw_asset_rows_equal"))
    }
    @Test fun `version header and terminal representation are compared separately`() {
        val version = NativeAcceptanceDiagnostics.compare(manifest(), manifest(body.replace("baseline", "new-baseline")))
        assertFalse(version.bool("version_header_equal")); assertEquals(0, version.number("raw_changed_paths"))
        val ending = NativeAcceptanceDiagnostics.compare(manifest(), manifest(body.removeSuffix("\n")))
        assertFalse(ending.bool("trailing_lf_equal")); assertFalse(ending.bool("empty_asset_rows_equal"))
        assertEquals(0, ending.number("raw_changed_paths"))
    }
    @Test fun `gzip metadata difference is not mistaken for decoded metadata change`() {
        val a = manifest()
        val b = manifest(mutateGzip = { it[4] = (it[4].toInt() xor 1).toByte() })
        val report = NativeAcceptanceDiagnostics.compare(a, b)
        assertFalse(report.bool("file_bytes_equal")); assertFalse(report.bool("gzip_bytes_equal"))
        assertTrue(report.bool("decoded_bytes_equal"))
        assertTrue(report.bool("gzip_equal_except_mtime"))
        val aGzip = NativeAcceptanceDiagnostics.summary(a).getValue("gzip").jsonObject
        val bGzip = NativeAcceptanceDiagnostics.summary(b).getValue("gzip").jsonObject
        assertEquals(aGzip.getValue("deflate_sha256"), bGzip.getValue("deflate_sha256"))
        assertNotEquals(aGzip.getValue("mtime_seconds"), bGzip.getValue("mtime_seconds"))
    }
    @Test fun `each typed metadata column is also counted`() {
        val report = NativeAcceptanceDiagnostics.compare(manifest(), manifest(body.replace("1,OPAQUE_A,80,7", "0,OPAQUE_A,81,8")))
        assertEquals(1, report.number("typed_changed_paths"))
        assertEquals(listOf(1, 0, 1, 1, 0), report.getValue("changed_rows_by_column_0_to_4").jsonArray.map { it.jsonPrimitive.int })
    }
    @Test fun `invalid manifest never becomes a diagnostic success`() {
        val invalid = OriginalAsset.capture(GameAssetPath.parse(FgoJpFormatProfile.MANIFEST_NAME), ByteArray(32))
        assertFails { NativeAcceptanceDiagnostics.manifest(fixture.manifests, invalid) }
    }
    @Test fun `path changes appear as removed and added metadata rather than equal rows`() {
        val report = NativeAcceptanceDiagnostics.compare(manifest(), manifest(body.replace("Other/Fake", "Other/Renamed")))
        assertEquals(2, report.number("raw_changed_paths")); assertEquals(2, report.number("typed_changed_paths"))
        assertFalse(report.bool("asset_path_order_equal")); assertFalse(report.bool("raw_asset_rows_equal"))
        assertEquals(2, report.getValue("changed_rows_by_column_0_to_4").jsonArray[4].jsonPrimitive.int)
    }
    @Test fun `Unity layout report preserves inputs and describes actual metadata`() {
        val serialized = fixture.serialized(mapOf("0100000011" to fixture.encoded("＄test\n＠speaker\nHello\n[k]\n")))
        val bytes = fixture.unity(listOf(serialized)); val original = bytes.copyOf()
        val report = NativeAcceptanceDiagnostics.unity(bytes)
        assertContentEquals(original, bytes)
        assertEquals(0x240, report.number("flags")); assertEquals(0, report.number("info_compression"))
        assertEquals(1, report.getValue("blocks").jsonArray.size)
        val node = report.getValue("nodes").jsonArray.single().jsonObject
        assertEquals(22, node.number("serialized_version")); assertEquals(1, node.number("text_assets"))
        assertEquals(Sha256Digest.compute(serialized).hex, node.getValue("sha256").jsonPrimitive.content)
        assertFails { NativeAcceptanceDiagnostics.unity(bytes.copyOf(bytes.size - 1)) }
    }
}
