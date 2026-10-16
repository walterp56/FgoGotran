package com.fgogotran.chapter

import com.fgogotran.chapter.codec.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Read-only native asset browser. Asset groups are not chapter titles or account progress. */
class ChapterBrowser(private val storage: ChapterOriginalStorage) {
    data class Catalogue(val manifest: OriginalAsset, val groups: List<FgoInstalledManifestCodec.Entry>)
    data class ScriptView(val document: DecodedNativeScript, val nativeText: String)

    suspend fun scan(): Catalogue = withContext(Dispatchers.Default) {
        val manifest = read(GameAssetPath.parse(FgoJpFormatProfile.MANIFEST_NAME))
        currentCoroutineContext().ensureActive()
        val groups = FgoJpFormatProfile.manifestCodec().entries(manifest).valueOrThrow()
            .filter { it.installed && it.logicalPath.startsWith("ScriptActionEncrypt/") && it.logicalPath != "ScriptActionEncrypt/ScriptFileList" }
            .sortedBy { it.logicalPath }
        Catalogue(manifest, groups)
    }

    suspend fun load(catalogue: Catalogue, entry: FgoInstalledManifestCodec.Entry): DecodedNativeContainer = withContext(Dispatchers.Default) {
        require(entry in catalogue.groups)
        check(read(catalogue.manifest.fingerprint.path).fingerprint == catalogue.manifest.fingerprint) { "Manifest changed; scan again" }
        val original = read(entry.assetPath)
        currentCoroutineContext().ensureActive()
        FgoJpFormatProfile.manifestCodec().verifyAsset(catalogue.manifest, original).valueOrThrow()
        val container = FgoJpFormatProfile.containerCodec().decode(original).valueOrThrow()
        currentCoroutineContext().ensureActive()
        check(read(catalogue.manifest.fingerprint.path).fingerprint == catalogue.manifest.fingerprint) { "Manifest changed; scan again" }
        container
    }

    suspend fun view(source: NativeScriptSource): ScriptView = withContext(Dispatchers.Default) {
        currentCoroutineContext().ensureActive()
        val codec = FgoJpFormatProfile.scriptCodec()
        val document = codec.decode(source).valueOrThrow()
        currentCoroutineContext().ensureActive()
        ScriptView(document, codec.decodedBytes(source).valueOrThrow().toByteArray().toString(Charsets.UTF_8))
    }

    private suspend fun read(path: GameAssetPath): OriginalAsset = when (val result = storage.readOriginal(path)) {
        is OriginalAssetReadResult.Read -> result.original
        OriginalAssetReadResult.NotFound -> error("Installed asset is missing")
        is OriginalAssetReadResult.Unavailable -> error("Cannot read JP FGO assets: ${result.reason}")
    }
}
