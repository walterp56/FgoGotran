package com.fgogotran.chapter.library

// Offline source-package compatibility experiment, not native playback evidence.

import com.fgogotran.chapter.*
import com.fgogotran.chapter.codec.*
import com.fgogotran.chapter.compile.StoryPackCompiler
import com.fgogotran.chapter.packs.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Point-in-time report only: contains no writable assets or installation authority. */
data class StoryPackPreview(
    val gameVersion: StoryGameVersion, val manifestSha256: String,
    val packCount: Int, val changedScriptCount: Int, val requestedFieldCount: Int,
    val rebuiltBundleCount: Int, val outputBytes: Long
)

fun interface StoryPackPreviewPort {
    suspend fun preview(packs: List<ValidatedStoryPack>): StoryPackPreview
}

/** Read-only capture + offline compiler. No installer, backup or transaction dependency. */
class StoryPackPreviewer(
    private val storage: ChapterOriginalStorage,
    private val gameVersion: suspend () -> StoryGameVersion,
    private val compiler: StoryPackCompiler = StoryPackCompiler(),
    private val manifests: FgoInstalledManifestCodec = FgoJpFormatProfile.manifestCodec()
) : StoryPackPreviewPort {
    override suspend fun preview(packs: List<ValidatedStoryPack>): StoryPackPreview = withContext(Dispatchers.Default) {
        ensureActive()
        require(packs.size in 1..StoryPackCompiler.MAX_PACKS && packs.map { it.manifest.chapterId }.distinct().size == packs.size)
        require(packs.map { it.manifest.language }.distinct().size == 1) { "Select one target language for this preview" }
        require(packs.sumOf { it.archiveSize.toLong() } <= StoryPackCompiler.MAX_TOTAL_BYTES)
        val descriptors = packs.flatMap { it.manifest.scripts }.groupBy { it.assetFilename }
        require(descriptors.size in 1..StoryPackCompiler.MAX_ASSETS)
        require(descriptors.values.sumOf { it.first().originalAssetSize.toLong() } <= StoryPackCompiler.MAX_TOTAL_BYTES)
        val version = gameVersion()
        require(packs.all { version in it.manifest.gameVersions }) { "Imported pack does not support this JP FGO version" }
        check(storage.capability() is ChapterStorageCapability.ReadOnly) { "Connect the read-only Shizuku service first" }
        val manifest = read(GameAssetPath.parse(FgoJpFormatProfile.MANIFEST_NAME))
        require(manifest.bytes.size <= StoryPackPolicy.MAX_ASSET)
        // Check the live story mapping before a package can request any binary file.
        val rows = manifests.entries(manifest).valueOrThrow()
        descriptors.forEach { (filename, group) ->
            val descriptor = group.first()
            require(group.all { it.logicalBundle == descriptor.logicalBundle && it.originalAssetSha256 == descriptor.originalAssetSha256 && it.originalAssetSize == descriptor.originalAssetSize })
            val row = rows.single { it.assetPath.value == filename }
            require(row.installed && row.logicalPath == descriptor.logicalBundle && row.size == descriptor.originalAssetSize.toLong()) { "Required JP story bundle is missing or does not match the pack mapping" }
        }
        val originals = descriptors.keys.sorted().map { filename ->
            ensureActive()
            read(GameAssetPath.parse(filename)).also { require(it.bytes.size <= StoryPackPolicy.MAX_ASSET) }
        }
        val compiled = compiler.compile(packs, version, manifest, originals).valueOrThrow()
        ensureActive()
        // Nothing is installed, but do not report a passed preview of files changed during compilation.
        originals.forEach { original ->
            ensureActive()
            check(read(original.fingerprint.path).fingerprint == original.fingerprint) { "JP story assets changed; preview again" }
        }
        check(read(manifest.fingerprint.path).fingerprint == manifest.fingerprint) { "JP resource index changed; preview again" }
        check(gameVersion() == version) { "FGO version changed; preview again" }
        check(storage.capability() is ChapterStorageCapability.ReadOnly) { "Read-only service disconnected" }
        currentCoroutineContext().ensureActive()
        StoryPackPreview(version, manifest.fingerprint.sha256.hex, packs.size, compiled.changedScriptCount,
            compiled.requestedFieldCount, compiled.rebuiltBundleCount,
            compiled.manifest.size.toLong() + compiled.bundles.sumOf { it.replacement.size.toLong() })
        // The compiled byte arrays are deliberately not retained by P3.
    }

    private suspend fun read(path: GameAssetPath): OriginalAsset {
        currentCoroutineContext().ensureActive()
        return when (val result = storage.readOriginal(path)) {
            is OriginalAssetReadResult.Read -> result.original.also { require(it.fingerprint.path == path) }
            OriginalAssetReadResult.NotFound -> error("Required JP story asset is not downloaded")
            is OriginalAssetReadResult.Unavailable -> error("Cannot read JP story assets: ${result.reason}")
        }
    }
}
