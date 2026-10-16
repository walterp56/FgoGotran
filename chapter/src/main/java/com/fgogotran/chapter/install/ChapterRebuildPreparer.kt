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
        require(mode != ChapterRebuildMode.MARKER || record.unchangedVerified)
        val original = OriginalAsset.capture(GameAssetPath.parse(ChapterInstallPolicy.BUNDLE), store.original(record, true))
        val manifest = OriginalAsset.capture(GameAssetPath.parse(ChapterInstallPolicy.MANIFEST), store.original(record, false))
        val containers = FgoJpFormatProfile.containerCodec()
        val scripts = FgoJpFormatProfile.scriptCodec()
        val manifests = FgoJpFormatProfile.manifestCodec()
        check(manifests.verifyAsset(manifest, original).valueOrThrow().logicalPath == ChapterInstallPolicy.GROUP)
        currentCoroutineContext().ensureActive()
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
        val rebuiltManifest = manifests.replaceAsset(manifest, original, rebuilt).valueOrThrow()
        manifests.verifyAsset(OriginalAsset.capture(manifest.fingerprint.path, rebuiltManifest.toByteArray()), rebuiltOriginal).valueOrThrow()
        val oldRows = manifests.entries(manifest).valueOrThrow()
        val newRows = manifests.entries(OriginalAsset.capture(manifest.fingerprint.path, rebuiltManifest.toByteArray())).valueOrThrow()
        check(oldRows.size == newRows.size)
        oldRows.zip(newRows).forEach { (a, b) ->
            check(b == if (a.assetPath == original.fingerprint.path) a.copy(size = rebuilt.size.toLong(), crc32 = crc32(rebuilt.toByteArray())) else a)
        }
        currentCoroutineContext().ensureActive()
        return Prepared(store.savePrepared(record, mode, rebuilt.toByteArray(), rebuiltManifest.toByteArray()), preview)
    }
}
