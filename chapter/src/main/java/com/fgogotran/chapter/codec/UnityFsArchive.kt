package com.fgogotran.chapter.codec

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.tukaani.xz.LZMAInputStream
import org.tukaani.xz.LZMAOutputStream
import org.tukaani.xz.LZMA2Options
import org.apache.commons.compress.compressors.lz4.BlockLZ4CompressorOutputStream

internal const val MAX_NATIVE_BYTES = 64 * 1024 * 1024
internal class UnsupportedNative(message: String) : IllegalArgumentException(message)

/** Bounded cursor: no byte offsets are accepted without checking the enclosing payload. */
internal class Cursor(val bytes: ByteArray, var pos: Int = 0, var order: ByteOrder = ByteOrder.BIG_ENDIAN) {
    fun take(count: Int): ByteArray {
        require(count >= 0 && pos >= 0 && count <= bytes.size - pos) { "Truncated native payload" }
        return bytes.copyOfRange(pos, pos + count).also { pos += count }
    }
    fun skip(count: Int) {
        require(count >= 0 && pos >= 0 && count <= bytes.size - pos) { "Truncated native payload" }
        pos += count
    }
    fun u8(): Int = take(1)[0].toInt() and 255
    fun i32(): Int = ByteBuffer.wrap(take(4)).order(order).int
    fun i64(): Long = ByteBuffer.wrap(take(8)).order(order).long
    fun u16(): Int = ByteBuffer.wrap(take(2)).order(order).short.toInt() and 65535
    fun count(limit: Int): Int = i32().also { require(it in 0..limit) { "Native count exceeds limit" } }
    fun cstring(): String {
        val start = pos
        while (pos < bytes.size && bytes[pos] != 0.toByte() && pos - start <= 4096) pos++
        require(pos < bytes.size && pos - start <= 4096) { "Invalid native string" }
        return strictUtf8(bytes.copyOfRange(start, pos)).also { pos++ }
    }
    fun align(size: Int) { skip((size - pos % size) % size) }
}

internal fun exactInt(value: Long): Int = value.toInt().also {
    require(value in 0..MAX_NATIVE_BYTES.toLong()) { "Native size exceeds limit" }
}

internal data class UnityNode(val offset: Int, val size: Int, val flags: Int, val name: String)

/** Supports the captured UnityFS v8 layout only, not a general Unity parser. */
internal class UnityFsArchive private constructor(
    val original: ByteArray,
    val data: ByteArray,
    val nodes: List<UnityNode>,
    private val sizePosition: Int,
    private val headerEnd: Int,
    private val flags: Int,
    private val dataHash: ByteArray,
    private val blockFlags: List<Int>,
    private val lzmaHeader: ByteArray?
) {
    fun replaceNode(index: Int, replacement: ByteArray): ByteArray = replaceNodes(mapOf(index to replacement))

    /** Rewrite the data and block metadata once for every selected serialized node. */
    fun replaceNodes(replacements: Map<Int, ByteArray>, checkCancelled: () -> Unit = {}): ByteArray {
        require(replacements.isNotEmpty() && replacements.keys.all { it in nodes.indices } && replacements.values.all { it.size <= MAX_NATIVE_BYTES })
        require(dataHash.all { it == 0.toByte() }) { "Nonzero Unity block hash is not supported for patching" }
        // R1 preserves the observed single block's format, not an invented conversion
        // of unknown multi-block layouts. Decoding those layouts remains read-only.
        if (blockFlags.size != 1) throw UnsupportedNative("Only the captured single-block layout is verified for rebuilding")
        if (flags and 0x3f !in listOf(0, 2, 3)) throw UnsupportedNative("Unverified directory compression for rebuilding")
        val ordered = replacements.entries.sortedBy { nodes[it.key].offset }
        val size = data.size.toLong() + ordered.sumOf { it.value.size.toLong() - nodes[it.key].size }
        require(size in 0..MAX_NATIVE_BYTES.toLong())
        val buffer = ByteArrayOutputStream(size.toInt())
        var cursor = 0
        ordered.forEach { (index, replacement) ->
            checkCancelled()
            val node = nodes[index]
            require(node.offset >= cursor)
            buffer.write(data, cursor, node.offset - cursor); buffer.write(replacement)
            cursor = node.offset + node.size
        }
        buffer.write(data, cursor, data.size - cursor)
        val nextData = buffer.toByteArray()
        val nextNodes = nodes.toMutableList()
        var editIndex = 0
        var delta = 0
        nodes.withIndex().sortedBy { it.value.offset }.forEach { (i, node) ->
            checkCancelled()
            while (editIndex < ordered.size && nodes[ordered[editIndex].key].offset + nodes[ordered[editIndex].key].size <= node.offset) {
                val edit = ordered[editIndex++]
                delta += edit.value.size - nodes[edit.key].size
            }
            nextNodes[i] = node.copy(offset = node.offset + delta, size = replacements[i]?.size ?: node.size)
        }
        val dataFlags = blockFlags.single()
        val packedData = compress(nextData, dataFlags and 0x3f, lzmaHeader, checkCancelled)
        val info = ByteArrayOutputStream().also { buffer ->
            DataOutputStream(buffer).use { out ->
                out.write(dataHash)
                out.writeInt(1)
                out.writeInt(nextData.size)
                out.writeInt(packedData.size)
                out.writeShort(dataFlags)
                out.writeInt(nextNodes.size)
                nextNodes.forEach { node ->
                    out.writeLong(node.offset.toLong()); out.writeLong(node.size.toLong())
                    out.writeInt(node.flags); out.write(node.name.toByteArray(Charsets.UTF_8)); out.writeByte(0)
                }
            }
        }.toByteArray()
        val packedInfo = compress(info, flags and 0x3f, null, checkCancelled)
        val output = ByteArrayOutputStream()
        output.write(original.copyOfRange(0, headerEnd))
        output.write(packedInfo)
        if (flags and 0x200 != 0) repeat((16 - output.size() % 16) % 16) { output.write(0) }
        output.write(packedData)
        require(output.size() <= MAX_NATIVE_BYTES) { "Rebuilt native archive exceeds limit" }
        checkCancelled()
        return output.toByteArray().also { result ->
            ByteBuffer.wrap(result).order(ByteOrder.BIG_ENDIAN).apply {
                position(sizePosition)
                putLong(result.size.toLong()); putInt(packedInfo.size); putInt(info.size); putInt(flags)
            }
        }
    }

    companion object {
        fun decode(bytes: ByteArray): UnityFsArchive {
            require(bytes.size <= MAX_NATIVE_BYTES)
            // Release packs carry zero padding after the declared UnityFS size so the
            // ciphertext CRC can be forged; the game ignores that tail. Trim it here so
            // validation and rebuilds see the real archive.
            val declared = runCatching {
                val probe = Cursor(bytes)
                if (probe.cstring() != "UnityFS") return@runCatching bytes.size.toLong()
                probe.i32(); probe.cstring(); probe.cstring()
                probe.i64()
            }.getOrNull()
            val archiveBytes = if (declared != null && declared in 1..bytes.size.toLong() && declared < bytes.size) {
                bytes.copyOf(declared.toInt())
            } else bytes
            val input = Cursor(archiveBytes)
            if (input.cstring() != "UnityFS" || input.i32() != 8) throw UnsupportedNative("Expected UnityFS v8")
            input.cstring() // player version retained verbatim
            if (!input.cstring().startsWith("6000.0.")) throw UnsupportedNative("Unverified Unity engine version")
            val sizePosition = input.pos
            require(input.i64() == archiveBytes.size.toLong()) { "Unity bundle size mismatch" }
            val packedInfoSize = input.count(MAX_NATIVE_BYTES)
            val infoSize = input.count(MAX_NATIVE_BYTES)
            val flags = input.i32()
            if (flags and 0x3c0 != 0x240 || flags and 0x3ff.inv() != 0) {
                throw UnsupportedNative("Unverified UnityFS layout flags")
            }
            input.align(16)
            val headerEnd = input.pos
            val info = Cursor(decompress(input.take(packedInfoSize), infoSize, flags and 0x3f))
            val hash = info.take(16)
            val blocks = List(info.count(4096)) { Triple(info.count(MAX_NATIVE_BYTES), info.count(MAX_NATIVE_BYTES), info.u16()) }
            val nodes = List(info.count(4096)) { UnityNode(exactInt(info.i64()), exactInt(info.i64()), info.i32(), info.cstring()) }
            require(info.pos == info.bytes.size && nodes.map { it.name }.distinct().size == nodes.size)
            if (flags and 0x200 != 0) input.align(16)
            val data = ByteArrayOutputStream()
            var lzmaHeader: ByteArray? = null
            blocks.forEach { (rawSize, packedSize, blockFlags) ->
                require(rawSize <= MAX_NATIVE_BYTES - data.size())
                if (blockFlags and 0x3ff.inv() != 0 || blockFlags and 0x380 != 0) {
                    throw UnsupportedNative("Unverified Unity block flags")
                }
                val packed = input.take(packedSize)
                if (blocks.size == 1 && blockFlags and 0x3f == 1) lzmaHeader = packed.take(5).toByteArray()
                data.write(decompress(packed, rawSize, blockFlags and 0x3f))
            }
            require(input.pos == archiveBytes.size) { "Unexpected Unity bundle tail" }
            val payload = data.toByteArray()
            var previousEnd = 0
            nodes.sortedBy { it.offset }.forEach { node ->
                require(node.offset >= previousEnd && node.size <= payload.size - node.offset) { "Overlapping/out-of-range Unity node" }
                previousEnd = node.offset + node.size
            }
            return UnityFsArchive(archiveBytes.copyOf(), payload, nodes, sizePosition, headerEnd, flags, hash, blocks.map { it.third }, lzmaHeader)
        }

        /** Real reconstruction, even for unchanged input. Native acceptance is still a device gate. */
        internal fun compress(bytes: ByteArray, kind: Int, lzmaHeader: ByteArray? = null, checkCancelled: () -> Unit = {}): ByteArray {
            require(bytes.size <= MAX_NATIVE_BYTES)
            checkCancelled()
            if (kind == 0) return bytes.copyOf()
            val output = ByteArrayOutputStream()
            val stream = when (kind) {
                1 -> {
                    val header = requireNotNull(lzmaHeader) { "Original LZMA parameters required" }
                    require(header.size == 5)
                    val properties = header[0].toInt() and 255
                    require(properties < 9 * 5 * 5)
                    val lc = properties % 9; val lp = properties / 9 % 5; val pb = properties / 45
                    val dictionary = ByteBuffer.wrap(header, 1, 4).order(ByteOrder.LITTLE_ENDIAN).int
                    require(dictionary in 4096..MAX_NATIVE_BYTES && lc + lp <= 4)
                    val options = LZMA2Options(6).apply { setDictSize(dictionary); setLcLp(lc, lp); setPb(pb) }
                    output.write(header)
                    // Unity stores the five property/dictionary bytes, then raw LZMA
                    // with an end marker; never add the .lzma size header.
                    LZMAOutputStream(output, options, true)
                }
                2, 3 -> BlockLZ4CompressorOutputStream(output,
                    BlockLZ4CompressorOutputStream.createParameterBuilder().let {
                        if (kind == 3) it.tunedForCompressionRatio() else it
                    }.build())
                else -> throw UnsupportedNative("Unsupported Unity compression $kind")
            }
            stream.use { target ->
                var offset = 0
                while (offset < bytes.size) {
                    checkCancelled()
                    val count = minOf(64 * 1024, bytes.size - offset)
                    target.write(bytes, offset, count); offset += count
                }
            }
            checkCancelled()
            return output.toByteArray().also { require(it.size <= MAX_NATIVE_BYTES) }
        }

        // Shared with desktop layout diagnostics; the decoder and its checks are unchanged.
        internal fun decompress(bytes: ByteArray, size: Int, kind: Int): ByteArray = when (kind) {
            0 -> bytes.also { require(it.size == size) }
            1 -> {
                val header = Cursor(bytes, order = ByteOrder.LITTLE_ENDIAN)
                val properties = header.u8().toByte()
                val dictionary = header.count(MAX_NATIVE_BYTES)
                // Captured Unity blocks include an end marker; the advertised size is checked below.
                val packedStream = ByteArrayInputStream(bytes, 5, bytes.size - 5)
                LZMAInputStream(packedStream, -1L, properties, dictionary).use { stream ->
                    val result = ByteArray(size)
                    var offset = 0
                    while (offset < size) {
                        val count = stream.read(result, offset, size - offset)
                        require(count > 0) { "Truncated LZMA block" }
                        offset += count
                    }
                    require(stream.read() == -1) { "Oversized LZMA block" }
                    require(packedStream.available() == 0) { "Unexpected LZMA block tail" }
                    result
                }
            }
            2, 3 -> decodeLz4(bytes, size)
            else -> throw UnsupportedNative("Unsupported Unity compression $kind")
        }

        private fun decodeLz4(packed: ByteArray, size: Int): ByteArray {
            val input = Cursor(packed)
            val output = ByteArray(size)
            var target = 0
            fun length(base: Int): Int {
                var value = base
                if (base == 15) do {
                    val extra = input.u8()
                    require(extra <= size - value)
                    value += extra
                } while (extra == 255)
                return value
            }
            while (input.pos < packed.size) {
                val token = input.u8()
                val literals = length(token ushr 4)
                require(literals <= size - target)
                input.take(literals).copyInto(output, target)
                target += literals
                if (input.pos == packed.size) break
                val distance = input.u8() or (input.u8() shl 8)
                require(distance in 1..target)
                val matches = length(token and 15) + 4
                require(matches <= size - target)
                repeat(matches) { output[target] = output[target - distance]; target++ }
            }
            require(target == size) { "LZ4 size mismatch" }
            return output
        }
    }
}
