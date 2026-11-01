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
    fun identity(name: String): ChapterFileIdentity
    fun openLive(name: String, gameUid: Int): ChapterLiveFile
    fun deleteStage(name: String)
}

data class ChapterGameStatus(val identity: ChapterGameIdentity, val stopped: Boolean)

/** Stateless across process death: expected hashes and commit intent live in the app journal. */
class ChapterTransactionEngine internal constructor(
    private val files: ChapterInstallFiles,
    private val gameStatus: () -> ChapterGameStatus,
    private val pairValidator: (ByteArray, ByteArray) -> Unit = ::validateNativeInstallPair,
    private val manifestRecovery: ChapterManifestRecovery? = null
) {
    private val writer = ChapterInPlaceWriter(files, manifestRecovery)
    private fun afterCompletedRun(record: ChapterInstallRecord): Boolean = manifestRecovery != null &&
        record.writeProtocol == ChapterWriteProtocol.IN_PLACE_V1 &&
        (record.state == ChapterInstallState.RESTORED || listOf("bundle", "manifest").all { writer.completed(record, it) })
    private fun gate(record: ChapterInstallRecord) {
        val status = gameStatus()
        check(status.identity == record.game) { "FGO installation changed; do not restore an old manifest" }
        check(status.stopped) { "Force stop JP FGO before staging, installing or restoring" }
    }
    private fun hash(name: String): InstallFileHash? = if (files.exists(name)) files.read(name).takeIf { it.isNotEmpty() }?.let(InstallFileHash::of) else null
    private fun <T> exclusive(record: ChapterInstallRecord, block: () -> T): T {
        gate(record) // Even creating the fixed lock file requires a stopped, matching game.
        return files.lock().use { gate(record); block() }
    }
    fun inspect(record: ChapterInstallRecord): ChapterPairState {
        check(gameStatus().identity == record.game) { "FGO installation changed" }
        if (!files.exists(ChapterInstallPolicy.BUNDLE) || !files.exists(ChapterInstallPolicy.MANIFEST)) return ChapterPairState.UNKNOWN
        val completed = afterCompletedRun(record)
        val contents = listOf("bundle" to ChapterInstallPolicy.BUNDLE, "manifest" to ChapterInstallPolicy.MANIFEST).map { (role, name) ->
            val identity = files.identity(name)
            writer.verifyKnownIdentity(record, role, identity, completed)
            val bytes = files.read(name)
            check(files.identity(name) == identity) { "Live file changed during inspection" }
            Triple(role, identity, bytes)
        }
        fun ByteArray.hash() = takeIf { it.isNotEmpty() }?.let(InstallFileHash::of)
        val rawIndex = contents[1].third.hash()
        val restoredWithoutIntents = manifestRecovery != null && record.writeProtocol == ChapterWriteProtocol.IN_PLACE_V1 &&
            record.state == ChapterInstallState.RESTORING && listOf("bundle", "manifest").all { role ->
                listOf(false, true).none { files.exists(ChapterInstallPolicy.writeName(record.id, role, it, "intent")) }
            }
        val index = if (rawIndex !in listOfNotNull(record.originalManifest, record.replacementManifest) && (completed || restoredWithoutIntents)) {
            when (manifestRecovery?.state(contents[1].third)) {
                0 -> record.originalManifest
                1 -> if (completed) record.replacementManifest else rawIndex
                else -> rawIndex
            }
        } else rawIndex
        val pair = record.classify(contents[0].third.hash(), index)
        if (pair == ChapterPairState.REPLACEMENT && record.writeProtocol == ChapterWriteProtocol.IN_PLACE_V1 &&
            !listOf("bundle", "manifest").all { writer.completed(record, it) }) return ChapterPairState.INTERRUPTED
        if (pair != ChapterPairState.UNKNOWN) return pair
        val recognised = contents.all { (role, identity, bytes) ->
            val original = if (role == "bundle") record.originalBundle else record.originalManifest
            val replacement = if (role == "bundle") record.replacementBundle else record.replacementManifest
            bytes.hash() in listOfNotNull(original, replacement) || writer.interrupted(record, role, identity, bytes)
        }
        return if (recognised) ChapterPairState.INTERRUPTED else ChapterPairState.UNKNOWN
    }
    fun stage(record: ChapterInstallRecord, bundle: ByteArray, manifest: ByteArray) = exclusive(record) {
        require(record.state == ChapterInstallState.PREPARED && record.exportSha256 != null)
        require(record.writeProtocol == ChapterWriteProtocol.IN_PLACE_V1) { "Legacy jobs must be restored and backed up again" }
        gate(record); check(inspect(record) == ChapterPairState.ORIGINAL) { "Original files changed" }
        requireNotNull(record.replacementBundle).verify(bundle); requireNotNull(record.replacementManifest).verify(manifest)
        pairValidator(bundle, manifest)
        check(files.freeBytes() >= 3L * (bundle.size.toLong() + manifest.size + record.originalBundle.size + record.originalManifest.size) + 1024 * 1024) { "Insufficient staging/recovery space" }
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
            // Existing intent may need these bytes after a service/app restart.
            if (listOf("bundle", "manifest").none { files.exists(ChapterInstallPolicy.writeName(record.id, it, restore, "intent")) }) {
                runCatching { files.deleteStage(bundleName) }; runCatching { files.deleteStage(manifestName) }
            }
            throw failure
        }
    }
    fun commit(record: ChapterInstallRecord) = exclusive(record) {
        require(record.state == ChapterInstallState.COMMITTING && record.exportSha256 != null)
        require(record.writeProtocol == ChapterWriteProtocol.IN_PLACE_V1)
        gate(record); check(inspect(record) == ChapterPairState.ORIGINAL) { "Original files changed before commit" }
        val bundle = ChapterInstallPolicy.stageName(record.id, "bundle")
        val manifest = ChapterInstallPolicy.stageName(record.id, "manifest")
        files.verifyReplacementAccess(bundle, ChapterInstallPolicy.BUNDLE, record.game.uid)
        files.verifyReplacementAccess(manifest, ChapterInstallPolicy.MANIFEST, record.game.uid)
        requireNotNull(record.replacementBundle).verify(files.read(bundle)); requireNotNull(record.replacementManifest).verify(files.read(manifest))
        pairValidator(files.read(bundle), files.read(manifest))
        gate(record)
        writer.write(record, "bundle", false, files.read(bundle)) { gate(record) }
        check(hash(ChapterInstallPolicy.BUNDLE) == record.replacementBundle)
        gate(record)
        check(hash(ChapterInstallPolicy.MANIFEST) == record.originalManifest) { "Manifest changed during commit" }
        writer.write(record, "manifest", false, files.read(manifest)) { gate(record) }
        check(inspect(record) == ChapterPairState.REPLACEMENT) { "Installed pair verification failed" }
    }
    fun restore(record: ChapterInstallRecord, bundle: ByteArray, manifest: ByteArray) = exclusive(record) {
        require(record.state == ChapterInstallState.RESTORING)
        gate(record)
        val current = inspect(record)
        check(current != ChapterPairState.UNKNOWN) { "Unexpected live files; preserve backups and review manually" }
        record.originalBundle.verify(bundle); record.originalManifest.verify(manifest); pairValidator(bundle, manifest)
        val completed = afterCompletedRun(record)
        val liveManifest = files.read(ChapterInstallPolicy.MANIFEST)
        val restoreManifest = writer.savedRestoreManifest(record) ?: if (completed && manifestRecovery?.state(liveManifest) != null)
            requireNotNull(manifestRecovery).restore(liveManifest) else manifest
        pairValidator(bundle, restoreManifest)
        // Exact old encoding is reused only when the complete decoded index is
        // still identical. Otherwise unrelated current entries are retained.
        if (current == ChapterPairState.ORIGINAL && liveManifest.contentEquals(restoreManifest)) { clean(record); return@exclusive }
        check(files.freeBytes() >= 2L * (bundle.size.toLong() + restoreManifest.size + files.read(ChapterInstallPolicy.BUNDLE).size + liveManifest.size) + 1024 * 1024) { "Insufficient restore space" }
        files.probe(record.id)
        stagePair(record, bundle, restoreManifest, true)
        gate(record)
        check(inspect(record) != ChapterPairState.UNKNOWN) { "Files changed before restore" }
        writer.write(record, "bundle", true, bundle, completed) { gate(record) }
        gate(record)
        check(inspect(record) != ChapterPairState.UNKNOWN) { "Files changed during restore" }
        writer.write(record, "manifest", true, restoreManifest, completed) { gate(record) }
        check(inspect(record) == ChapterPairState.ORIGINAL) { "Restoration verification failed" }
        clean(record)
    }
    private fun clean(record: ChapterInstallRecord) {
        for (role in listOf("bundle", "manifest")) for (restore in listOf(false, true)) {
            files.deleteStage(ChapterInstallPolicy.stageName(record.id, role, restore))
            for (part in listOf("done", "intent", "before")) files.deleteStage(ChapterInstallPolicy.writeName(record.id, role, restore, part))
        }
    }
}

internal fun validateNativeInstallPair(bundle: ByteArray, manifest: ByteArray) {
    // The install model keeps the captured OFFICIAL index rows: FGO reconciles rows
    // against its own metadata at startup and repairs any row that describes a
    // rebuilt file, marking the asset uninstalled. So this pair check verifies that
    // both files are valid and that the bundle is still recorded as installed; it
    // deliberately does not require the row's size/CRC to describe `bundle`.
    val manifestAsset = OriginalAsset.capture(GameAssetPath.parse(ChapterInstallPolicy.MANIFEST), manifest)
    val entries = FgoJpFormatProfile.manifestCodec().entries(manifestAsset).let {
        check(it is NativeCodecResult.Success) { "Index does not decode" }
        it.value
    }
    val target = GameAssetPath.parse(ChapterInstallPolicy.BUNDLE)
    check(entries.single { it.assetPath == target }.installed) { "Manifest does not mark the story bundle installed" }
    val bundleAsset = OriginalAsset.capture(target, bundle)
    check(FgoJpFormatProfile.containerCodec().decode(bundleAsset) is NativeCodecResult.Success) {
        "Staged bundle is not a decodable story container"
    }
}
