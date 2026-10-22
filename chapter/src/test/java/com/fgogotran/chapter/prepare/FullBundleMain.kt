package com.fgogotran.chapter.prepare

import kotlinx.serialization.json.*
import java.io.File

/** Developer entry point: rebuild one captured bundle from the full regional cloud store. */
object FullBundleMain {
    @JvmStatic fun main(args: Array<String>) {
        require(args.size in 5..6) { "usage: <repo> <captureDir> <region> <cloudRoot> <outDir> [bundleFile]" }
        val repository = File(args[0]).absoluteFile
        val capture = File(repository, args[1]).absoluteFile
        val region = args[2]
        val cloud = File(repository, args[3]).absoluteFile
        val out = File(repository, args[4]).absoluteFile
        require(capture.isDirectory) { "Not a capture directory: $capture" }
        require(region in listOf("CN", "TW", "NA")) { "Unsupported region" }
        out.mkdirs()

        val bundleFile = if (args.size == 6 && args[5].isNotBlank()) File(capture, args[5]) else File(capture, "3b6e743e6f41fb01a1037cb15169096d6a63198c.bin")
        require(bundleFile.isFile) { "Bundle not found: $bundleFile" }
        val keysFile = File(repository, "story_assets/keys/2.139.5.json")
        val extraKeys = if (keysFile.isFile) {
            val root = Json.parseToJsonElement(keysFile.readText()).jsonObject
            root["keys"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap()
        } else emptyMap()
        val result = FullBundleBuilder(extraKeys = extraKeys).build(capture, region, cloud, bundleFile)
        val coverage = buildJsonObject {
            put("schema_version", 1)
            put("region", region)
            put("capture", capture.name)
            put("translated", result.translated.size)
            put("reasons", buildJsonObject { result.reasons.toSortedMap().forEach { (k, v) -> put(k, v) } })
            put("translated_scripts", JsonArray(result.translated.sorted().map(::JsonPrimitive)))
            put("rejected", JsonArray(result.rejected.sortedBy { it.first }.map { row -> buildJsonObject { put("id", row.first); put("reason", row.second) } }))
        }
        File(out, "coverage.json").writeText(coverage.toString())
        val output = result.output
        if (output == null) {
            println("$region: 0 translated scripts (nothing rebuilt)")
        } else {
            File(out, "bundle.bin").writeBytes(output)
            println("$region: ${result.translated.size} translated scripts, bundle ${output.size} bytes")
        }
        println("reasons: ${result.reasons.toSortedMap()}")
        println("output: $out")
    }
}
