package com.fgogotran.chapter.library

import com.fgogotran.chapter.*
import com.fgogotran.chapter.compile.SyntheticStoryAssets
import com.fgogotran.chapter.packs.*
import java.io.File

internal class StoryLibraryFixture(val folder: File) {
    val native = SyntheticStoryAssets()
    val original = native.asset(mapOf("0100000011" to native.encoded("＄synthetic\n＠Speaker\nFirst\n[k]\n")))
    val index = native.manifest(listOf("ScriptActionEncrypt/01" to original))
    fun pack(chapter: String = "intro", language: StoryLanguage = StoryLanguage.TRADITIONAL_CHINESE, revision: Int = 1, replacement: String = "Translated"): ValidatedStoryPack =
        native.pack(folder, listOf("ScriptActionEncrypt/01" to original), mapOf("0100000011" to listOf(
            native.change(native.source(original), "First", StoryTextToken.Text(replacement))
        )), chapter, language, revision, packId = "$chapter-${language.region.lowercase()}")
    fun archive(pack: ValidatedStoryPack) = folder.listFiles().orEmpty().single {
        it.extension == "zip" && StoryPackArchive.fingerprint(it).first == pack.archiveSha256
    }
    fun library() = StoryPackLibrary(File(folder, "library"))
    fun import(library: StoryPackLibrary, pack: ValidatedStoryPack = pack()) = library.import({ archive(pack).inputStream() })
    fun storage() = MemoryOriginals(mapOf(index.fingerprint.path to index, original.fingerprint.path to original))
    fun previewer(storage: ChapterOriginalStorage, version: suspend () -> StoryGameVersion = { native.version }) =
        StoryPackPreviewer(storage, version, native.compiler(), native.manifests)
}

internal class MemoryOriginals(val files: Map<GameAssetPath, OriginalAsset>) : ChapterOriginalStorage {
    val reads = mutableListOf<GameAssetPath>()
    var capability: ChapterStorageCapability = ChapterStorageCapability.ReadOnly("synthetic-read-only")
    var beforeRead: (GameAssetPath, Int) -> Unit = { _, _ -> }
    var transform: (GameAssetPath, OriginalAsset) -> OriginalAsset = { _, original -> original }
    override suspend fun capability() = capability
    override suspend fun readOriginal(path: GameAssetPath): OriginalAssetReadResult {
        reads += path; beforeRead(path, reads.size)
        return files[path]?.let { OriginalAssetReadResult.Read(transform(path, it)) } ?: OriginalAssetReadResult.NotFound
    }
}
