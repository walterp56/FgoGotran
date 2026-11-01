package com.fgogotran.chapter.prepare

import com.fgogotran.chapter.Sha256Digest
import com.fgogotran.chapter.codec.strictUtf8
import com.fgogotran.chapter.packs.StoryPackJson
import kotlinx.serialization.json.*
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption

/** Developer tools only. No device, app-private storage, install service or translation API. */
internal object PreparationFiles {
    const val MAX_JSON = 1024 * 1024
    const val MAX_SCRIPT = 4 * 1024 * 1024
    const val MAX_SNAPSHOT = 64 * 1024 * 1024
    const val MAX_FILES = 512

    fun confined(root: File, candidate: File, allowRoot: Boolean = false): File {
        val base = root.absoluteFile.normalize().toPath()
        val target = candidate.absoluteFile.normalize().toPath()
        require(target.startsWith(base) && (allowRoot || target != base)) { "Preparation path escapes its root" }
        var path: java.nio.file.Path? = target
        while (path != null) {
            require(!Files.isSymbolicLink(path)) { "Linked preparation path refused" }
            if (Files.exists(path)) require(path.toRealPath() == path) { "Redirected preparation path refused" }
            path = path.parent
        }
        return target.toFile()
    }

    fun read(file: File, limit: Int): ByteArray {
        require(Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) && file.length() in 1..limit.toLong())
        return file.inputStream().use { bounded(it, limit) }
    }

    fun bounded(input: InputStream, limit: Int, deadlineNanos: Long = Long.MAX_VALUE): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16384)
        while (true) {
            check(System.nanoTime() < deadlineNanos) { "Preparation response deadline exceeded" }
            val count = input.read(buffer)
            check(System.nanoTime() < deadlineNanos) { "Preparation response deadline exceeded" }
            if (count < 0) break
            require(output.size().toLong() + count <= limit) { "Preparation input exceeds limit" }
            output.write(buffer, 0, count)
        }
        require(output.size() > 0)
        return output.toByteArray()
    }

    fun writeNew(file: File, bytes: ByteArray) {
        Files.createDirectories(requireNotNull(file.parentFile).toPath())
        FileChannel.open(file.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use {
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) it.write(buffer)
            it.force(true)
        }
        require(read(file, bytes.size).contentEquals(bytes)) { "Preparation write read-back differs" }
    }

    fun hash(bytes: ByteArray) = Sha256Digest.compute(bytes).hex
    fun json(bytes: ByteArray): JsonObject = StoryPackJson.metadata(bytes, MAX_JSON)
    fun text(bytes: ByteArray) = strictUtf8(bytes).removePrefix("\uFEFF").replace("\r\n", "\n").also {
        require('\r' !in it && '\u0000' !in it)
    }
    fun canonical(element: JsonElement): ByteArray {
        fun sorted(value: JsonElement): JsonElement = when (value) {
            is JsonObject -> JsonObject(value.toSortedMap().mapValues { sorted(it.value) })
            is JsonArray -> JsonArray(value.map(::sorted))
            else -> value
        }
        return sorted(element).toString().toByteArray(Charsets.UTF_8).also { require(it.size in 1..MAX_JSON) }
    }
}

internal fun JsonObject.string(key: String) = getValue(key).jsonPrimitive.let { require(it.isString); it.content }
internal fun JsonObject.int(key: String) = getValue(key).jsonPrimitive.let { require(!it.isString); it.int }
internal fun JsonObject.exact(vararg keys: String) = also { require(it.keys == keys.toSet()) { "Missing/unknown preparation metadata" } }
