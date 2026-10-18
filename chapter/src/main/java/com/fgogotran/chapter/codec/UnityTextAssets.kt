package com.fgogotran.chapter.codec

import java.nio.ByteBuffer
import java.nio.ByteOrder

internal data class UnityTextAsset(val pathId: Long, val name: String, val payload: ByteArray)
private data class ObjectEntry(val id: Long, val start: Int, val size: Int, val classId: Int, val tablePosition: Int)

/** Version 22 metadata is retained verbatim; only verified TextAsset fields are interpreted. */
internal class UnityTextAssets private constructor(
    private val original: ByteArray,
    private val dataOffset: Int,
    private val objects: List<ObjectEntry>,
    val texts: List<UnityTextAsset>
) {
    fun replaceText(pathId: Long, expected: ByteArray, replacement: ByteArray): ByteArray {
        require(replacement.size <= MAX_NATIVE_BYTES)
        val entry = objects.single { it.id == pathId && it.classId == 49 }
        val text = texts.single { it.pathId == pathId }
        require(text.payload.contentEquals(expected)) { "TextAsset baseline mismatch" }
        val oldObject = original.copyOfRange(dataOffset + entry.start, dataOffset + entry.start + entry.size)
        val cursor = Cursor(oldObject, order = ByteOrder.LITTLE_ENDIAN)
        cursor.skip(cursor.count(4096)); cursor.align(4)
        val lengthPosition = cursor.pos
        val oldLength = cursor.count(MAX_NATIVE_BYTES)
        cursor.skip(oldLength)
        // Tail is alignment only for the observed TextAsset schema. Never drop unknown fields.
        require(oldObject.size - cursor.pos in 0..3 && oldObject.copyOfRange(cursor.pos, oldObject.size).all { it == 0.toByte() })
        val newObjectSize = (lengthPosition + 4 + replacement.size + 3) and 3.inv()
        val newObject = ByteArray(newObjectSize)
        oldObject.copyInto(newObject, endIndex = lengthPosition)
        ByteBuffer.wrap(newObject).order(ByteOrder.LITTLE_ENDIAN).putInt(lengthPosition, replacement.size)
        replacement.copyInto(newObject, lengthPosition + 4)
        // Keep every following object's original alignment and preserve all intervening bytes.
        val difference = newObject.size - entry.size
        val alignmentPadding = ((16 - difference % 16) % 16)
        val delta = difference + alignmentPadding
        require(original.size.toLong() + delta in 0..MAX_NATIVE_BYTES.toLong())
        val start = dataOffset + entry.start
        val result = original.copyOfRange(0, start) + newObject + ByteArray(alignmentPadding) +
            original.copyOfRange(start + entry.size, original.size)
        ByteBuffer.wrap(result).order(ByteOrder.BIG_ENDIAN).putLong(24, result.size.toLong())
        val metadata = ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN)
        objects.forEach { obj ->
            when {
                obj.id == pathId -> metadata.putInt(obj.tablePosition + 8, newObject.size)
                obj.start >= entry.start + entry.size -> metadata.putLong(obj.tablePosition, (obj.start + delta).toLong())
            }
        }
        return result
    }

    companion object {
        fun decode(bytes: ByteArray): UnityTextAssets {
            require(bytes.size <= MAX_NATIVE_BYTES)
            val input = Cursor(bytes)
            input.skip(8)
            if (input.i32() != 22) throw UnsupportedNative("Expected Unity serialized-file version 22")
            input.skip(4)
            if (input.u8() != 0) throw UnsupportedNative("Expected little-endian Unity metadata")
            input.skip(3)
            val metadataSize = input.count(MAX_NATIVE_BYTES)
            require(input.i64() == bytes.size.toLong()) { "Serialized file size mismatch" }
            val dataOffset = exactInt(input.i64())
            input.skip(8)
            require(metadataSize <= dataOffset - input.pos && dataOffset <= bytes.size)
            input.order = ByteOrder.LITTLE_ENDIAN
            if (!input.cstring().startsWith("6000.0.")) throw UnsupportedNative("Unverified serialized Unity version")
            input.i32() // target platform, retained verbatim
            val hasTree = when (input.u8()) { 0 -> false; 1 -> true; else -> throw IllegalArgumentException("Invalid tree flag") }
            val types = List(input.count(4096)) {
                val classId = input.i32()
                input.skip(3) // stripped flag + script type index
                if (classId == 114) input.skip(16)
                input.skip(16) // old type hash
                if (hasTree) {
                    val nodes = input.count(65536)
                    val strings = input.count(MAX_NATIVE_BYTES)
                    input.skip(Math.multiplyExact(nodes, 32))
                    input.skip(strings)
                    input.skip(Math.multiplyExact(input.count(65536), 4))
                }
                classId
            }
            val objects = List(input.count(100000)) {
                input.align(4)
                val id = input.i64()
                val tablePosition = input.pos
                val start = exactInt(input.i64())
                val size = input.count(MAX_NATIVE_BYTES)
                val typeIndex = input.i32()
                require(typeIndex in types.indices && start <= bytes.size - dataOffset && size <= bytes.size - dataOffset - start)
                ObjectEntry(id, start, size, types[typeIndex], tablePosition)
            }
            require(input.pos <= 48 + metadataSize && input.pos <= dataOffset)
            require(objects.map { it.id }.distinct().size == objects.size)
            var end = 0
            objects.sortedBy { it.start }.forEach {
                require(it.start >= end) { "Overlapping serialized objects" }; end = it.start + it.size
            }
            val texts = objects.filter { it.classId == 49 }.map { obj ->
                val body = Cursor(bytes.copyOfRange(dataOffset + obj.start, dataOffset + obj.start + obj.size), order = ByteOrder.LITTLE_ENDIAN)
                val name = strictUtf8(body.take(body.count(4096)))
                body.align(4)
                val payload = body.take(body.count(MAX_NATIVE_BYTES))
                require(body.bytes.size - body.pos in 0..3 && body.take(body.bytes.size - body.pos).all { it == 0.toByte() }) { "Unverified TextAsset tail" }
                UnityTextAsset(obj.id, name, payload)
            }
            require(texts.map { it.name }.distinct().size == texts.size)
            return UnityTextAssets(bytes.copyOf(), dataOffset, objects, texts)
        }
    }
}
