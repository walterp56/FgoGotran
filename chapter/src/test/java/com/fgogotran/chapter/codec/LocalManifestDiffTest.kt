package com.fgogotran.chapter.codec

import com.fgogotran.chapter.*
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue
import java.io.File
import kotlin.test.Test

/** Owner-local diagnostics only: no device calls, writes or acceptance-policy changes. */
class LocalManifestDiffTest {
    @Test fun `compare preserved game rewritten manifest with pretest capture`() {
        val otherPath = System.getenv("CHAPTER_COMPARE_CAPTURE").orEmpty()
        assumeTrue("Post-playback manifest comparison is opt-in", otherPath.isNotEmpty())
        val module = File(requireNotNull(System.getProperty("chapter.projectDir"))).canonicalFile
        val root = File(module, "local-fixtures").canonicalFile
        fun manifest(path: String): OriginalAsset {
            val directory = File(path).canonicalFile
            require(directory.toPath().startsWith(root.toPath()) && directory != root)
            val capture = Json.parseToJsonElement(File(directory, "capture.json").readText()).jsonObject
            val name = FgoJpFormatProfile.MANIFEST_NAME
            val bytes = File(directory, name).readBytes()
            require(Sha256Digest.compute(bytes).hex == capture.getValue("manifest_sha256").jsonPrimitive.content)
            return OriginalAsset.capture(GameAssetPath.parse(name), bytes)
        }
        val codec = FgoJpFormatProfile.manifestCodec()
        val before = codec.entries(manifest(requireNotNull(System.getProperty("chapter.localCapture")))).valueOrThrow().associateBy { it.logicalPath }
        val after = codec.entries(manifest(otherPath)).valueOrThrow().associateBy { it.logicalPath }
        val changed = (before.keys + after.keys).filter { before[it] != after[it] }
        println(buildJsonObject {
            put("before_rows", before.size); put("after_rows", after.size)
            put("before_installed", before.values.count { it.installed }); put("after_installed", after.values.count { it.installed })
            put("changed_rows", changed.size)
            put("changed_examples", buildJsonArray {
                changed.take(20).forEach { path -> add(buildJsonObject {
                    put("path", path)
                    fun FgoInstalledManifestCodec.Entry?.row() = this?.let { buildJsonObject {
                        put("installed", it.installed); put("size", it.size); put("crc32", it.crc32)
                    } } ?: JsonNull
                    put("before", before[path].row()); put("after", after[path].row())
                }) }
            })
        })
    }
}
