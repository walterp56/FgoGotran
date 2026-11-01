package com.fgogotran.chapter.prepare

import com.fgogotran.chapter.*
import com.fgogotran.chapter.codec.*
import com.fgogotran.chapter.install.ChapterInstallPolicy
import java.io.File

internal data class FullBundleResult(
    val output: ByteArray?,
    val translated: List<String>,
    val reasons: Map<String, Int>,
    val rejected: List<Pair<String, String>> = emptyList(),
)

/**
 * Aligns every script inside one captured JP bundle against the regional cloud
 * scripts with the same script ID. No war/quest snapshot scoping: coverage is
 * limited only by the regional store and the alignment rules.
 */
internal class FullBundleBuilder(
    private val containers: FgoJpContainerCodec = FgoJpFormatProfile.containerCodec(),
    private val scripts: FgoNativeScriptCodec = FgoJpFormatProfile.scriptCodec(),
    private val extraKeys: Map<String, String> = emptyMap(),
) {
    fun build(capture: File, region: String, cloudRoot: File, bundleFile: File = File(capture, ChapterInstallPolicy.BUNDLE), checkCancelled: () -> Unit = {}): FullBundleResult {
        val bundle = bundleFile.readBytes()
        val original = OriginalAsset.capture(GameAssetPath.parse(bundleFile.name), bundle)
        var codec = containers
        val container = runCatching { containers.decode(original).valueOrThrow() }.getOrElse { failure ->
            val key = extraKeys[bundleFile.name] ?: extraKeys[bundleFile.nameWithoutExtension]
                ?: throw IllegalStateException("Missing extra key for ${bundleFile.name}; add it to story_assets/keys/<game-version>.json", failure)
            codec = FgoJpFormatProfile.containerCodec(key)
            codec.decode(original).valueOrThrow()
        }
        val alignment = CompleteScriptAlignment(scripts, strict = false)
        val regional = loadRegional(File(cloudRoot, "$region/script"))
        val reasons = linkedMapOf<String, Int>()
        val edits = mutableListOf<FgoJpContainerCodec.ScriptReplacement>()
        val translated = mutableListOf<String>()
        val rejected = mutableListOf<Pair<String, String>>()

        container.scripts.sortedBy { it.binding.scriptId.value }.forEach { source ->
            checkCancelled()
            val id = source.binding.scriptId.value
            val target = regional[id]
            if (target == null) {
                reasons.merge("REGIONAL_MISSING", 1, Int::plus)
                return@forEach
            }
            val native = runCatching { scripts.decodedBytes(source).valueOrThrow().toByteArray() }.getOrElse {
                reasons.merge("UNREADABLE_SCRIPT", 1, Int::plus)
                return@forEach
            }
            val jp = AtlasReference("JP", id, id.take(2), emptyList())
            val local = AtlasReference(region, id, id.take(2), emptyList())
            val result = alignment.align(source, jp, native, local, target)
            reasons.merge(result.reason.name, 1, Int::plus)
            if (result.reason != AlignmentReason.TRANSLATED) rejected += id to result.reason.name
            if (result.reason == AlignmentReason.TRANSLATED) {
                translated += id
                edits += FgoJpContainerCodec.ScriptReplacement(source, requireNotNull(result.replacement))
            }
        }

        if (edits.isEmpty()) return FullBundleResult(null, translated, reasons, rejected)
        val output = codec.replaceScripts(original, edits, checkCancelled).valueOrThrow().toByteArray()
        return FullBundleResult(output, translated, reasons, rejected)
    }

    private fun loadRegional(root: File): Map<String, ByteArray> {
        if (!root.isDirectory) return emptyMap()
        return root.walkTopDown()
            .filter { it.isFile && it.extension == "txt" }
            .associate { it.nameWithoutExtension to it.readBytes() }
    }
}
