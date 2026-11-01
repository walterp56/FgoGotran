package com.fgogotran.chapter.compile

// Developer/offline builder only. Source-pack compilation is not an APK workflow.

import com.fgogotran.chapter.*
import com.fgogotran.chapter.codec.*
import com.fgogotran.chapter.packs.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

data class CompiledStorySelection(val packId: String, val revision: Int, val chapterId: String, val language: StoryLanguage, val archiveSha256: String)
data class CompiledStoryBundle(val logicalBundle: String, val original: OriginalAssetFingerprint, val replacement: AssetBytes)

/** In-memory offline output, never an installation authority or playback approval. */
class CompiledStoryAssets internal constructor(
    val originalManifest: OriginalAssetFingerprint,
    val manifest: AssetBytes,
    bundles: Collection<CompiledStoryBundle>, selections: Collection<CompiledStorySelection>,
    val requestedFieldCount: Int, val changedScriptCount: Int
) {
    val bundles = immutableList(bundles)
    val selections = immutableList(selections)
    val rebuiltBundleCount: Int get() = bundles.count { it.original.sha256 != it.replacement.sha256 }
}

/** Pure bounded composition. No file/network/storage/installer dependency. */
class StoryPackCompiler(
    private val containers: FgoJpContainerCodec = FgoJpFormatProfile.containerCodec(),
    private val scripts: FgoNativeScriptCodec = FgoJpFormatProfile.scriptCodec(),
    private val manifests: FgoInstalledManifestCodec = FgoJpFormatProfile.manifestCodec()
) {
    companion object {
        const val MAX_PACKS = 32
        const val MAX_ASSETS = 16
        const val MAX_COMPOSED_SCRIPTS = 256
        const val MAX_FIELDS = 32768
        const val MAX_TOTAL_BYTES = 32 * 1024 * 1024
    }

    suspend fun compile(
        selectedPacks: Collection<ValidatedStoryPack>, gameVersion: StoryGameVersion,
        manifest: OriginalAsset, originalAssets: Collection<OriginalAsset>
    ): NativeCodecResult<CompiledStoryAssets> = withContext(Dispatchers.Default) {
        val context = currentCoroutineContext()
        val checkCancelled = { context.ensureActive() }
        nativeAttempt {
            checkCancelled()
            val packs = selectedPacks.toList().sortedBy { it.manifest.packId }
            val originals = originalAssets.toList()
            require(packs.size in 1..MAX_PACKS && originals.size in 1..MAX_ASSETS)
            require(packs.map { it.manifest.chapterId }.distinct().size == packs.size) { "Select one revision/language per chapter" }
            require(packs.map { it.manifest.packId }.distinct().size == packs.size)
            require(packs.sumOf { it.archiveSize.toLong() } <= MAX_TOTAL_BYTES &&
                packs.sumOf { pack -> pack.manifest.scripts.sumOf { it.patchSize.toLong() } } <= MAX_TOTAL_BYTES)
            require(packs.sumOf { pack -> pack.patches.sumOf { it.changes.size.toLong() } } <= MAX_FIELDS)
            require(packs.all { gameVersion in it.manifest.gameVersions }) { "Unsupported game version for selected pack" }
            require(manifest.fingerprint.path.value == FgoJpFormatProfile.MANIFEST_NAME && manifest.bytes.size <= StoryPackPolicy.MAX_ASSET)
            require(originals.map { it.fingerprint.path }.distinct().size == originals.size)
            require(originals.sumOf { it.bytes.size.toLong() } + manifest.bytes.size <= MAX_TOTAL_BYTES)

            data class ScriptPlan(val descriptor: StoryScriptDescriptor, val changes: List<StoryTextChange>)
            val requested = packs.flatMap { pack ->
                require(pack.patches.map { it.scriptId }.toSet() == pack.manifest.scripts.map { it.scriptId }.toSet() &&
                    pack.patches.map { it.scriptId }.distinct().size == pack.patches.size)
                pack.manifest.scripts.map { descriptor ->
                    descriptor to pack.patches.single { it.scriptId == descriptor.scriptId }.changes
                }
            }
            require(requested.map { it.first.scriptId }.distinct().size <= MAX_COMPOSED_SCRIPTS)
            val plans = requested.groupBy { it.first.assetFilename to it.first.scriptId }.map { (_, group) ->
                val first = group.first().first
                require(group.all { (d, _) -> d.logicalBundle == first.logicalBundle && d.originalAssetSha256 == first.originalAssetSha256 &&
                    d.originalAssetSize == first.originalAssetSize && d.originalPayloadSha256 == first.originalPayloadSha256 && d.originalDecodedSha256 == first.originalDecodedSha256 }) {
                    "Selected packs disagree on an original script baseline"
                }
                val changes = group.flatMap { it.second }.sortedBy { it.fieldId }
                require(changes.size <= StoryPackPolicy.MAX_CHANGES && changes.map { it.fieldId }.distinct().size == changes.size) { "Overlapping chapter text replacements" }
                ScriptPlan(first, changes)
            }
            require(plans.size <= MAX_COMPOSED_SCRIPTS)
            val byAsset = plans.groupBy { it.descriptor.assetFilename }
            require(byAsset.keys == originals.map { it.fingerprint.path.value }.toSet()) { "Capture exactly the affected original assets" }
            val originalRows = manifests.entries(manifest).valueOrThrow()
            var changedScripts = 0
            val compiled = originals.sortedBy { it.fingerprint.path.value }.map { original ->
                checkCancelled()
                val group = byAsset.getValue(original.fingerprint.path.value)
                val descriptor = group.first().descriptor
                require(original.bytes.size in 1..StoryPackPolicy.MAX_ASSET && group.all { it.descriptor.originalAssetSha256 == original.fingerprint.sha256.hex &&
                    it.descriptor.originalAssetSize == original.bytes.size && it.descriptor.logicalBundle == descriptor.logicalBundle }) { "Original story bundle fingerprint differs" }
                val row = originalRows.single { it.assetPath == original.fingerprint.path }
                require(row.logicalPath == descriptor.logicalBundle && row.installed && row.size == original.bytes.size.toLong() && row.crc32 == crc32(original.bytes.toByteArray())) {
                    "Installed index does not describe the original story bundle"
                }
                val before = containers.decode(original).valueOrThrow()
                checkCancelled()
                val edits = group.sortedBy { it.descriptor.scriptId }.map { plan ->
                    checkCancelled()
                    val source = before.scripts.single { it.binding.scriptId.value == plan.descriptor.scriptId }
                    require(source.payload.sha256.hex == plan.descriptor.originalPayloadSha256) { "Encrypted script baseline differs" }
                    val patch = StoryScriptPatch(plan.descriptor.scriptId, plan.changes)
                    val replacement = StoryTextCompiler(scripts).compile(source, patch, plan.descriptor.originalDecodedSha256, checkCancelled).valueOrThrow()
                    FgoJpContainerCodec.ScriptReplacement(source, replacement)
                }
                val changed = edits.filter { it.source.payload != it.replacement }
                changedScripts += changed.size
                val replacement = if (changed.isEmpty()) original.bytes else containers.replaceScripts(original, changed, checkCancelled).valueOrThrow()
                require(replacement.size <= StoryPackPolicy.MAX_ASSET) { "Rebuilt story bundle exceeds initial installer scope" }
                val after = containers.decode(OriginalAsset.capture(original.fingerprint.path, replacement.toByteArray())).valueOrThrow()
                require(before.scripts.map { it.binding } == after.scripts.map { it.binding }) { "Native script identities changed" }
                val expected = edits.associate { it.source.binding to it.replacement }
                before.scripts.zip(after.scripts).forEach { (a, b) ->
                    checkCancelled()
                    require(b.payload == (expected[a.binding] ?: a.payload)) { "An unrelated native script changed" }
                }
                CompiledStoryBundle(row.logicalPath, original.fingerprint, replacement)
            }
            require(compiled.sumOf { it.replacement.size.toLong() } + manifest.bytes.size <= MAX_TOTAL_BYTES)
            checkCancelled()
            val replacementManifest = manifests.replaceAssets(manifest, originals.map { original ->
                original to compiled.single { it.original.path == original.fingerprint.path }.replacement
            }, checkCancelled).valueOrThrow()
            require(replacementManifest.size <= StoryPackPolicy.MAX_ASSET && compiled.sumOf { it.replacement.size.toLong() } + replacementManifest.size <= MAX_TOTAL_BYTES)
            val afterRows = manifests.entries(OriginalAsset.capture(manifest.fingerprint.path, replacementManifest.toByteArray())).valueOrThrow()
            require(originalRows.size == afterRows.size)
            val replacements = compiled.associateBy { it.original.path }
            originalRows.zip(afterRows).forEach { (before, after) ->
                val replacement = replacements[before.assetPath]?.replacement
                require(after == if (replacement == null) before else before.copy(size = replacement.size.toLong(), crc32 = crc32(replacement.toByteArray()))) {
                    "Unrelated installed-index metadata changed"
                }
            }
            checkCancelled()
            CompiledStoryAssets(manifest.fingerprint, replacementManifest, compiled, packs.map {
                CompiledStorySelection(it.manifest.packId, it.manifest.revision, it.manifest.chapterId, it.manifest.language, it.archiveSha256)
            }, plans.sumOf { it.changes.size }, changedScripts)
        }
    }
}
