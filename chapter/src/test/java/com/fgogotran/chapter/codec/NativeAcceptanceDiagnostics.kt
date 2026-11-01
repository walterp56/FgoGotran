package com.fgogotran.chapter.codec

import com.fgogotran.chapter.*
import kotlinx.serialization.json.*
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Desktop-only descriptions, not a repairer, alternative validator or installation authority. */
internal object NativeAcceptanceDiagnostics {
    private fun hash(bytes: ByteArray) = Sha256Digest.compute(bytes).hex
    private fun hash(text: String) = hash(text.toByteArray(Charsets.UTF_8))
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }

    class Manifest internal constructor(
        val original: OriginalAsset, val gzip: ByteArray, val text: String,
        val entries: List<FgoInstalledManifestCodec.Entry>
    ) {
        val rows = text.split('\n')
        val assets = rows.drop(2).filter(String::isNotEmpty)
        val byPath = assets.associateBy { it.substringAfterLast(',') }
    }

    fun manifest(codec: FgoInstalledManifestCodec, original: OriginalAsset): Manifest {
        val layers = codec.diagnosticLayers(original).valueOrThrow()
        return Manifest(original, layers.gzip.toByteArray(), layers.decodedText, codec.entries(original).valueOrThrow())
    }

    private fun gzipSummary(bytes: ByteArray) = buildJsonObject {
        require(bytes.size >= 18 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte())
        put("header_first_10_hex", hex(bytes.copyOfRange(0, 10)))
        put("flags", bytes[3].toInt() and 255)
        put("mtime_seconds", ByteBuffer.wrap(bytes, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL)
        put("trailer_hex", hex(bytes.copyOfRange(bytes.size - 8, bytes.size)))
        // Only this fixed-header form can be compared by removing ten header bytes.
        if (bytes[3] == 0.toByte()) put("deflate_sha256", hash(bytes.copyOfRange(10, bytes.size - 8)))
    }

    fun summary(value: Manifest) = buildJsonObject {
        val raw = value.original.bytes.toByteArray()
        val outer = strictUtf8(raw)
        val cipher = decodeBase64Text(raw, 4 * 1024 * 1024)
        put("file_sha256", hash(raw)); put("file_bytes", raw.size)
        put("outer_bom", outer.startsWith('\uFEFF'))
        put("outer_cr_count", outer.count { it == '\r' }); put("outer_lf_count", outer.count { it == '\n' })
        put("cipher_sha256", hash(cipher)); put("gzip_sha256", hash(value.gzip)); put("gzip_bytes", value.gzip.size)
        put("gzip", gzipSummary(value.gzip))
        put("decoded_sha256", hash(value.text)); put("body_sha256", hash(value.rows.drop(1).joinToString("\n")))
        put("checksum_header", value.rows[0]); put("version_header", value.rows[1])
        put("trailing_lf", value.text.endsWith('\n'))
        put("empty_asset_rows", value.rows.drop(2).count(String::isEmpty))
        put("asset_rows", value.assets.size); put("installed_rows", value.entries.count { it.installed })
        put("ordered_rows_sha256", hash(value.assets.joinToString("\n")))
        put("opaque_column_sha256", hash(value.assets.joinToString("\n") { it.split(',')[1] }))
    }

    fun compare(before: Manifest, after: Manifest) = buildJsonObject {
        val paths = (before.byPath.keys + after.byPath.keys).sorted()
        val rawChanged = paths.filter { before.byPath[it] != after.byPath[it] }
        val aTyped = before.entries.associateBy { it.logicalPath }
        val bTyped = after.entries.associateBy { it.logicalPath }
        val typedChanged = paths.count { aTyped[it] != bTyped[it] }
        val columnChanges = (0..4).map { column ->
            paths.count { before.byPath[it]?.split(',')?.get(column) != after.byPath[it]?.split(',')?.get(column) }
        }
        put("file_bytes_equal", before.original.bytes == after.original.bytes)
        put("gzip_bytes_equal", before.gzip.contentEquals(after.gzip))
        put("gzip_equal_except_mtime", before.gzip.size == after.gzip.size && before.gzip.indices.all {
            it in 4..7 || before.gzip[it] == after.gzip[it]
        })
        put("decoded_bytes_equal", before.text == after.text)
        put("checksum_header_equal", before.rows[0] == after.rows[0])
        put("version_header_equal", before.rows[1] == after.rows[1])
        put("asset_path_order_equal", before.byPath.keys.toList() == after.byPath.keys.toList())
        put("raw_asset_rows_equal", before.assets == after.assets)
        put("trailing_lf_equal", before.text.endsWith('\n') == after.text.endsWith('\n'))
        put("empty_asset_rows_equal", before.rows.drop(2).count(String::isEmpty) == after.rows.drop(2).count(String::isEmpty))
        put("raw_changed_paths", rawChanged.size); put("typed_changed_paths", typedChanged)
        put("changed_rows_by_column_0_to_4", JsonArray(columnChanges.map(::JsonPrimitive)))
        put("changed_examples", buildJsonArray {
            rawChanged.take(12).forEach { path -> add(buildJsonObject {
                put("path", path)
                put("changed_columns", JsonArray((0..4).filter { column ->
                    before.byPath[path]?.split(',')?.get(column) != after.byPath[path]?.split(',')?.get(column)
                }.map(::JsonPrimitive)))
                // Digests avoid dumping whole opaque/native resource-index values into logs.
                before.byPath[path]?.let { put("before_row_sha256", hash(it)) }
                after.byPath[path]?.let { put("after_row_sha256", hash(it)) }
            }) }
        })
    }

    fun unity(bytes: ByteArray): JsonObject {
        val archive = UnityFsArchive.decode(bytes) // All ordinary bounds/format checks still apply.
        val input = Cursor(bytes)
        val signature = input.cstring(); val version = input.i32()
        val player = input.cstring(); val engine = input.cstring()
        val size = input.i64(); val packedInfoSize = input.i32(); val infoSize = input.i32(); val flags = input.i32()
        val headerEnd = input.pos; input.align(16)
        val infoStart = input.pos
        val packedInfo = input.take(packedInfoSize)
        val decodedInfo = UnityFsArchive.decompress(packedInfo, infoSize, flags and 0x3f)
        val info = Cursor(decodedInfo)
        val dataHash = info.take(16)
        val blocks = List(info.i32()) { Triple(info.i32(), info.i32(), info.u16()) }
        // Node identities/ranges were already validated by UnityFsArchive.decode above.
        if (flags and 0x200 != 0) input.align(16)
        val dataStart = input.pos
        var rawOffset = 0
        var packedOffset = dataStart
        val blockReports = blocks.map { (raw, packed, blockFlags) -> buildJsonObject {
            put("raw_bytes", raw); put("packed_bytes", packed); put("flags", blockFlags)
            put("compression", blockFlags and 0x3f); put("packed_offset", packedOffset)
            put("packed_sha256", hash(bytes.copyOfRange(packedOffset, packedOffset + packed)))
            put("raw_sha256", hash(archive.data.copyOfRange(rawOffset, rawOffset + raw)))
            packedOffset += packed; rawOffset += raw
        } }
        require(packedOffset == bytes.size && rawOffset == archive.data.size)
        return buildJsonObject {
            put("sha256", hash(bytes)); put("file_bytes", bytes.size); put("declared_bytes", size)
            put("signature", signature); put("format_version", version); put("player_version", player); put("engine_version", engine)
            put("flags", flags); put("info_compression", flags and 0x3f)
            put("info_packed_bytes", packedInfoSize); put("info_raw_bytes", infoSize)
            put("info_raw_sha256", hash(decodedInfo)); put("data_hash_hex", hex(dataHash))
            put("header_padding_bytes", infoStart - headerEnd)
            put("header_padding_zero", bytes.copyOfRange(headerEnd, infoStart).all { it == 0.toByte() })
            put("data_padding_bytes", dataStart - infoStart - packedInfoSize)
            put("data_padding_zero", bytes.copyOfRange(infoStart + packedInfoSize, dataStart).all { it == 0.toByte() })
            put("data_start", dataStart); put("data_start_mod_16", dataStart % 16)
            put("raw_data_bytes", archive.data.size); put("raw_data_sha256", hash(archive.data))
            put("blocks", JsonArray(blockReports))
            put("nodes", buildJsonArray {
                archive.nodes.forEach { node -> add(buildJsonObject {
                    val raw = archive.data.copyOfRange(node.offset, node.offset + node.size)
                    val serialized = UnityTextAssets.decode(raw)
                    val header = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN)
                    val metadataSize = header.getInt(20); val dataOffset = header.getLong(32).toInt()
                    put("name", node.name); put("offset", node.offset); put("bytes", node.size); put("flags", node.flags)
                    put("sha256", hash(raw)); put("serialized_version", header.getInt(8))
                    put("serialized_metadata_bytes", metadataSize); put("serialized_data_offset", dataOffset)
                    put("serialized_metadata_sha256", hash(raw.copyOfRange(48, 48 + metadataSize)))
                    put("serialized_padding_sha256", hash(raw.copyOfRange(48 + metadataSize, dataOffset)))
                    put("text_assets", serialized.texts.size)
                    put("script_payloads_sha256", hash(serialized.texts.sortedBy { it.pathId }.joinToString("\n") {
                        "${it.pathId}:${it.name}:${hash(it.payload)}"
                    }))
                }) }
            })
        }
    }
}
