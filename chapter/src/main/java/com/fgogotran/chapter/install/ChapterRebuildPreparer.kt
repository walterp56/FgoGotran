package com.fgogotran.chapter.install

import com.fgogotran.chapter.*
import com.fgogotran.chapter.codec.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Uses the existing native writers; does not have an installation or network port. */
class ChapterRebuildPreparer(private val store: ChapterBackupStore) {
    data class Preview(val fieldId: String, val original: String, val replacement: String)
    data class Prepared(val record: ChapterInstallRecord, val preview: Preview?)
    suspend fun prepare(record: ChapterInstallRecord, mode: ChapterRebuildMode): Prepared {
        require(mode != ChapterRebuildMode.LANGUAGE_PACK) { "Asset tests cannot prepare language-pack operations" }
        require(mode != ChapterRebuildMode.MARKER || record.unchangedVerified)
        val original = OriginalAsset.capture(GameAssetPath.parse(ChapterInstallPolicy.BUNDLE), store.original(record, true))
        val manifest = OriginalAsset.capture(GameAssetPath.parse(ChapterInstallPolicy.MANIFEST), store.original(record, false))
        val containers = FgoJpFormatProfile.containerCodec()
        val scripts = FgoJpFormatProfile.scriptCodec()
        val manifests = FgoJpFormatProfile.manifestCodec()
        check(manifests.verifyAsset(manifest, original).valueOrThrow().logicalPath == ChapterInstallPolicy.GROUP)
        currentCoroutineContext().ensureActive()
        if (mode == ChapterRebuildMode.INDEX_CONTROL) {
            val reencoded = manifests.reencodeEquivalentForIndexControl(manifest).valueOrThrow()
            check(reencoded != manifest.bytes) { "Index control must exercise a changed encoding" }
            manifests.verifyAsset(OriginalAsset.capture(manifest.fingerprint.path, reencoded.toByteArray()), original).valueOrThrow()
            currentCoroutineContext().ensureActive()
            return Prepared(store.savePrepared(record, mode, original.bytes.toByteArray(), reencoded.toByteArray()), null)
        }
        val before = containers.decode(original).valueOrThrow()
        check(before.scripts.size == 717) { "The observed test bundle changed" }
        val source = before.scripts.single { it.binding.scriptId.value == ChapterInstallPolicy.SCRIPT }
        var preview: Preview? = null
        val payload = if (mode == ChapterRebuildMode.UNCHANGED) source.payload else {
            val field = scripts.decode(source).valueOrThrow().fields.single { it.id.value == "dialogue:691" }
            preview = Preview(field.id.value, field.sourceText, "テスト表示")
            scripts.patchPlainDialogue(source, NativeTextPatch(field.id, Sha256Digest.compute(field.sourceText.toByteArray(Charsets.UTF_8)), "テスト表示")).valueOrThrow()
        }
        currentCoroutineContext().ensureActive()
        val rebuilt = containers.replaceScript(original, source, payload).valueOrThrow()
        val rebuiltOriginal = OriginalAsset.capture(original.fingerprint.path, rebuilt.toByteArray())
        currentCoroutineContext().ensureActive()
        val after = containers.decode(rebuiltOriginal).valueOrThrow()
        check(before.scripts.map { it.binding } == after.scripts.map { it.binding })
        before.scripts.zip(after.scripts).forEach { (a, b) ->
            check(b.payload == if (a.binding == source.binding) payload else a.payload) { "Unexpected script change" }
        }
        val rebuiltManifest = manifest.bytes
        val originalRows = manifests.entries(manifest).valueOrThrow()
        val targetRow = originalRows.single { it.assetPath == original.fingerprint.path }
        // FGO reconciles installed index rows against its own official metadata at
        // startup. A row rewritten to describe the rebuilt bundle (size/CRC) is
        // repaired and the asset marked uninstalled, so the captured index must be
        // installed byte-for-byte. The rebuilt file may differ in size and CRC; see
        check(targetRow.installed && targetRow.size == original.fingerprint.sizeBytes &&
            targetRow.crc32 == crc32(original.bytes.toByteArray())) { "Captured index does not describe the original bundle" }
        currentCoroutineContext().ensureActive()
        return Prepared(store.savePrepared(record, mode, rebuilt.toByteArray(), rebuiltManifest.toByteArray()), preview)
    }
}
