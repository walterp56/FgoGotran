package com.fgogotran.chapter.install

import com.fgogotran.chapter.*
import com.fgogotran.chapter.codec.FgoJpFormatProfile
import java.io.Closeable

/** Port used by the strict Android adapter and temporary-directory fault tests. */
interface ChapterInstallFiles {
    fun lock(): Closeable
    fun read(name: String): ByteArray
    fun exists(name: String): Boolean
    fun freeBytes(): Long
    fun probe(id: String)
    fun stage(name: String, bytes: ByteArray)
    fun verifyReplacementAccess(stage: String, live: String, gameUid: Int)
    fun replace(stage: String, live: String)
    fun deleteStage(name: String)
}

data class ChapterGameStatus(val identity: ChapterGameIdentity, val stopped: Boolean)

/** Stateless across process death: expected hashes and commit intent live in the app journal. */
class ChapterTransactionEngine internal constructor(
    private val files: ChapterInstallFiles,
    private val gameStatus: () -> ChapterGameStatus,
    private val pairValidator: (ByteArray, ByteArray) -> Unit = ::validateNativeInstallPair
) {
    private fun gate(record: ChapterInstallRecord) {
        val status = gameStatus()
        check(status.identity == record.game) { "FGO installation changed; do not restore an old manifest" }
        check(status.stopped) { "Force stop JP FGO before staging, installing or restoring" }
    }
    private fun hash(name: String): InstallFileHash? = if (files.exists(name)) InstallFileHash.of(files.read(name)) else null
    private fun <T> exclusive(record: ChapterInstallRecord, block: () -> T): T {
        gate(record) // Even creating the fixed lock file requires a stopped, matching game.
        return files.lock().use { gate(record); block() }
    }
    fun inspect(record: ChapterInstallRecord): ChapterPairState {
        check(gameStatus().identity == record.game) { "FGO installation changed" }
        return record.classify(hash(ChapterInstallPolicy.BUNDLE), hash(ChapterInstallPolicy.MANIFEST))
    }
    fun stage(record: ChapterInstallRecord, bundle: ByteArray, manifest: ByteArray) = exclusive(record) {
        require(record.state == ChapterInstallState.PREPARED && record.exportSha256 != null)
        gate(record); check(inspect(record) == ChapterPairState.ORIGINAL) { "Original files changed" }
        requireNotNull(record.replacementBundle).verify(bundle); requireNotNull(record.replacementManifest).verify(manifest)
        pairValidator(bundle, manifest)
        check(files.freeBytes() >= bundle.size.toLong() + manifest.size + record.originalBundle.size + record.originalManifest.size + 1024 * 1024) { "Insufficient free space" }
        files.probe(record.id)
        stagePair(record, bundle, manifest, false)
    }
    private fun stagePair(record: ChapterInstallRecord, bundle: ByteArray, manifest: ByteArray, restore: Boolean) {
        val bundleName = ChapterInstallPolicy.stageName(record.id, "bundle", restore)
        val manifestName = ChapterInstallPolicy.stageName(record.id, "manifest", restore)
        try {
            files.stage(bundleName, bundle); files.stage(manifestName, manifest)
            files.verifyReplacementAccess(bundleName, ChapterInstallPolicy.BUNDLE, record.game.uid)
            files.verifyReplacementAccess(manifestName, ChapterInstallPolicy.MANIFEST, record.game.uid)
            check(files.read(bundleName).contentEquals(bundle) && files.read(manifestName).contentEquals(manifest)) { "Staged read-back differs" }
        } catch (failure: Exception) {
            runCatching { files.deleteStage(bundleName) }; runCatching { files.deleteStage(manifestName) }
            throw failure
        }
    }
    fun commit(record: ChapterInstallRecord) = exclusive(record) {
        require(record.state == ChapterInstallState.COMMITTING && record.exportSha256 != null)
        gate(record); check(inspect(record) == ChapterPairState.ORIGINAL) { "Original files changed before commit" }
        val bundle = ChapterInstallPolicy.stageName(record.id, "bundle")
        val manifest = ChapterInstallPolicy.stageName(record.id, "manifest")
        files.verifyReplacementAccess(bundle, ChapterInstallPolicy.BUNDLE, record.game.uid)
        files.verifyReplacementAccess(manifest, ChapterInstallPolicy.MANIFEST, record.game.uid)
        requireNotNull(record.replacementBundle).verify(files.read(bundle)); requireNotNull(record.replacementManifest).verify(files.read(manifest))
        pairValidator(files.read(bundle), files.read(manifest))
        gate(record)
        files.replace(bundle, ChapterInstallPolicy.BUNDLE)
        check(hash(ChapterInstallPolicy.BUNDLE) == record.replacementBundle)
        gate(record)
        check(hash(ChapterInstallPolicy.MANIFEST) == record.originalManifest) { "Manifest changed during commit" }
        files.replace(manifest, ChapterInstallPolicy.MANIFEST)
        check(inspect(record) == ChapterPairState.REPLACEMENT) { "Installed pair verification failed" }
    }
    fun restore(record: ChapterInstallRecord, bundle: ByteArray, manifest: ByteArray) = exclusive(record) {
        require(record.state == ChapterInstallState.RESTORING)
        gate(record)
        val current = inspect(record)
        check(current != ChapterPairState.UNKNOWN) { "Unexpected live files; preserve backups and review manually" }
        record.originalBundle.verify(bundle); record.originalManifest.verify(manifest); pairValidator(bundle, manifest)
        if (current == ChapterPairState.ORIGINAL) { clean(record); return@exclusive }
        check(files.freeBytes() >= bundle.size.toLong() + manifest.size + 1024 * 1024) { "Insufficient restore space" }
        files.probe(record.id)
        stagePair(record, bundle, manifest, true)
        gate(record)
        check(inspect(record) != ChapterPairState.UNKNOWN) { "Files changed before restore" }
        files.replace(ChapterInstallPolicy.stageName(record.id, "bundle", true), ChapterInstallPolicy.BUNDLE)
        gate(record)
        check(hash(ChapterInstallPolicy.MANIFEST) in listOf(record.originalManifest, record.replacementManifest))
        files.replace(ChapterInstallPolicy.stageName(record.id, "manifest", true), ChapterInstallPolicy.MANIFEST)
        check(inspect(record) == ChapterPairState.ORIGINAL) { "Restoration verification failed" }
        clean(record)
    }
    private fun clean(record: ChapterInstallRecord) {
        for (role in listOf("bundle", "manifest")) for (restore in listOf(false, true))
            files.deleteStage(ChapterInstallPolicy.stageName(record.id, role, restore))
    }
}

internal fun validateNativeInstallPair(bundle: ByteArray, manifest: ByteArray) {
    FgoJpFormatProfile.manifestCodec().verifyAsset(
        OriginalAsset.capture(GameAssetPath.parse(ChapterInstallPolicy.MANIFEST), manifest),
        OriginalAsset.capture(GameAssetPath.parse(ChapterInstallPolicy.BUNDLE), bundle)
    ).let { check(it is NativeCodecResult.Success) { "Manifest does not describe the staged bundle" } }
}
