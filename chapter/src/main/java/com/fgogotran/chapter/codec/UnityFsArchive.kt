package com.fgogotran.chapter.codec

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.tukaani.xz.LZMAInputStream

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
    private val dataHash: ByteArray
) {
    fun replaceNode(index: Int, replacement: ByteArray): ByteArray {
        require(index in nodes.indices && replacement.size <= MAX_NATIVE_BYTES)
        require(dataHash.all { it == 0.toByte() }) { "Nonzero Unity block hash is not supported for patching" }
        val selected = nodes[index]
        val delta = replacement.size - selected.size
        require(data.size.toLong() + delta in 0..MAX_NATIVE_BYTES.toLong())
        val nextData = data.copyOfRange(0, selected.offset) + replacement +
            data.copyOfRange(selected.offset + selected.size, data.size)
        val nextNodes = nodes.mapIndexed { i, node ->
            when {
                i == index -> node.copy(size = replacement.size)
                node.offset >= selected.offset + selected.size -> node.copy(offset = node.offset + delta)
                else -> node
            }
        }
        // A standards-valid uncompressed test rebuild. Native acceptance remains a device gate.
        val info = ByteArrayOutputStream().also { buffer ->
            DataOutputStream(buffer).use { out ->
                out.write(dataHash)
                out.writeInt(1)
                out.writeInt(nextData.size)
                out.writeInt(nextData.size)
                out.writeShort(0x40)
                out.writeInt(nextNodes.size)
                nextNodes.forEach { node ->
                    out.writeLong(node.offset.toLong()); out.writeLong(node.size.toLong())
                    out.writeInt(node.flags); out.write(node.name.toByteArray(Charsets.UTF_8)); out.writeByte(0)
                }
            }
        }.toByteArray()
        val output = ByteArrayOutputStream()
        output.write(original.copyOfRange(0, headerEnd))
        output.write(info)
        if (flags and 0x200 != 0) repeat((16 - output.size() % 16) % 16) { output.write(0) }
        output.write(nextData)
        require(output.size() <= MAX_NATIVE_BYTES) { "Rebuilt native archive exceeds limit" }
        return output.toByteArray().also { result ->
            ByteBuffer.wrap(result).order(ByteOrder.BIG_ENDIAN).apply {
                position(sizePosition)
                putLong(result.size.toLong()); putInt(info.size); putInt(info.size); putInt(flags and 0x3f.inv())
            }
        }
    }

    companion object {
        fun decode(bytes: ByteArray): UnityFsArchive {
            require(bytes.size <= MAX_NATIVE_BYTES)
            val input = Cursor(bytes)
            if (input.cstring() != "UnityFS" || input.i32() != 8) throw UnsupportedNative("Expected UnityFS v8")
            input.cstring() // player version retained verbatim
            if (!input.cstring().startsWith("6000.0.")) throw UnsupportedNative("Unverified Unity engine version")
            val sizePosition = input.pos
            require(input.i64() == bytes.size.toLong()) { "Unity bundle size mismatch" }
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
            blocks.forEach { (rawSize, packedSize, blockFlags) ->
                require(rawSize <= MAX_NATIVE_BYTES - data.size())
                if (blockFlags and 0x3ff.inv() != 0 || blockFlags and 0x380 != 0) {
                    throw UnsupportedNative("Unverified Unity block flags")
                }
                data.write(decompress(input.take(packedSize), rawSize, blockFlags and 0x3f))
            }
            require(input.pos == bytes.size) { "Unexpected Unity bundle tail" }
            val payload = data.toByteArray()
            var previousEnd = 0
            nodes.sortedBy { it.offset }.forEach { node ->
                require(node.offset >= previousEnd && node.size <= payload.size - node.offset) { "Overlapping/out-of-range Unity node" }
                previousEnd = node.offset + node.size
            }
            return UnityFsArchive(bytes.copyOf(), payload, nodes, sizePosition, headerEnd, flags, hash)
        }

        private fun decompress(bytes: ByteArray, size: Int, kind: Int): ByteArray = when (kind) {
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
