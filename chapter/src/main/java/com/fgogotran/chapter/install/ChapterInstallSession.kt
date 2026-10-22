package com.fgogotran.chapter.install

import com.fgogotran.chapter.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

interface ChapterInstallTransport {
    suspend fun environment(): ChapterGameStatus
    suspend fun inspect(record: ChapterInstallRecord): ChapterPairState
    suspend fun stage(record: ChapterInstallRecord)
    suspend fun commit(record: ChapterInstallRecord)
    suspend fun restore(record: ChapterInstallRecord)
}

data class ChapterAssetTestState(
    val record: ChapterInstallRecord? = null,
    val busy: Boolean = false,
    val critical: Boolean = false,
    val needsInspection: Boolean = false,
    val preview: ChapterRebuildPreparer.Preview? = null,
    val error: String? = null
)

/** Pure orchestration: tests use local files/transport. No Activity or OCR lifecycle owns commits. */
class ChapterInstallSession(private val store: ChapterBackupStore, private val transport: ChapterInstallTransport) {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(ChapterAssetTestState())
    val state = mutableState.asStateFlow()
    private fun publish(record: ChapterInstallRecord?, inspect: Boolean = mutableState.value.needsInspection) {
        mutableState.value = mutableState.value.copy(record = record, needsInspection = inspect)
    }
    private fun record() = requireNotNull(store.load()) { "Back up originals first" }
    suspend fun reload() = operation {
        val record = store.load()
        if (record != mutableState.value.record) mutableState.value = mutableState.value.copy(preview = null)
        publish(record, record?.state in listOf(ChapterInstallState.STAGED, ChapterInstallState.COMMITTING, ChapterInstallState.INSTALLED,
            ChapterInstallState.RESTORING, ChapterInstallState.RECOVERY_REQUIRED))
    }
    suspend fun backup(storage: ChapterOriginalStorage) = operation {
        val (environment, bundle, manifest) = captureBaseline(storage)
        publish(store.create(environment.identity, bundle, manifest), false)
    }
    suspend fun reviewLegacyBaseline(storage: ChapterOriginalStorage, operatorApproved: Boolean) = operation {
        check(operatorApproved) { "Explicit disposable-device and PC-backup confirmation is required" }
        val old = record()
        check(ChapterLegacyBaselineReview.eligible(old)) { "This failed test operation has not been reviewed" }
        val (environment, bundle, manifest) = captureBaseline(storage)
        publish(store.reviewLegacyBaseline(old, environment.identity, bundle, manifest, operatorApproved), false)
        mutableState.value = mutableState.value.copy(preview = null)
    }
    private suspend fun captureBaseline(storage: ChapterOriginalStorage): Triple<ChapterGameStatus, ByteArray, ByteArray> {
        val environment = transport.environment()
        check(environment.stopped) { "Force stop JP FGO before creating the baseline" }
        suspend fun read(name: String): ByteArray = when (val result = storage.readOriginal(GameAssetPath.parse(name))) {
            is OriginalAssetReadResult.Read -> result.original.bytes.toByteArray()
            else -> error("Cannot capture the complete original pair")
        }
        val manifest = read(ChapterInstallPolicy.MANIFEST)
        val bundle = read(ChapterInstallPolicy.BUNDLE)
        validateNativeInstallPair(bundle, manifest)
        val candidate = ChapterInstallRecord("0".repeat(32), environment.identity, InstallFileHash.of(bundle), InstallFileHash.of(manifest))
        ChapterInstallPolicy.requireOriginals(candidate)
        check(transport.environment() == environment && transport.inspect(candidate) == ChapterPairState.ORIGINAL) { "Originals changed during capture" }
        currentCoroutineContext().ensureActive()
        return Triple(environment, bundle, manifest)
    }
    suspend fun prepare(mode: ChapterRebuildMode) = operation {
        val record = record()
        check(!mutableState.value.needsInspection)
        check(transport.inspect(record) == ChapterPairState.ORIGINAL) { "Restore or rescan the original pair first" }
        val prepared = ChapterRebuildPreparer(store).prepare(record, mode)
        publish(prepared.record, false)
        mutableState.value = mutableState.value.copy(preview = prepared.preview)
    }
    suspend fun export(output: () -> OutputStream, readBack: () -> InputStream) = operation {
        var record = record()
        // The chosen URI may overwrite the previous export. Revoke its receipt first.
        if (record.exportSha256 != null) {
            record = store.replace(record, record.copy(exportSha256 = null))
            publish(record)
        }
        val digest = MessageDigest.getInstance("SHA-256")
        var count = 0L
        val stream = object : FilterOutputStream(output()) {
            override fun write(b: Int) { out.write(b); digest.update(b.toByte()); count++ }
            override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len); digest.update(b, off, len); count += len }
        }
        stream.use { store.export(record, it) }
        val expected = digest.digest()
        val actual = MessageDigest.getInstance("SHA-256")
        var actualCount = 0L
        readBack().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                currentCoroutineContext().ensureActive()
                val length = input.read(buffer); if (length < 0) break
                actualCount += length
                check(actualCount <= 2L * ChapterInstallPolicy.MAX_FILE + 128 * 1024) { "Export read-back exceeds limit" }
                actual.update(buffer, 0, length)
            }
        }
        check(count == actualCount && expected.contentEquals(actual.digest())) { "Export read-back failed; installation remains blocked" }
        publish(store.replace(record, record.copy(exportSha256 = expected.joinToString("") { "%02x".format(it.toInt() and 255) })))
    }
    suspend fun install(operatorApproved: Boolean) = operation {
        check(operatorApproved) { "Explicit disposable-device and PC-backup confirmation is required" }
        var record = record()
        check(!mutableState.value.needsInspection && record.state == ChapterInstallState.PREPARED && record.exportSha256 != null) { "Prepare and verify the recovery export first" }
        check(record.mode != ChapterRebuildMode.MARKER || mutableState.value.preview != null) { "Prepare the marker again to review its before/after text" }
        store.original(record, true); store.original(record, false)
        store.prepared(record, true); store.prepared(record, false)
        transport.stage(record) // Only owned temporary files; still cancellable before commit intent.
        currentCoroutineContext().ensureActive()
        record = store.replace(record, record.copy(state = ChapterInstallState.STAGED)); publish(record)
        critical {
            record = store.replace(record, record.copy(state = ChapterInstallState.COMMITTING)); publish(record, true)
            transport.commit(record)
            check(transport.inspect(record) == ChapterPairState.REPLACEMENT)
            record = store.replace(record, record.copy(state = ChapterInstallState.INSTALLED)); publish(record, false)
        }
    }
    suspend fun inspect() = operation {
        val record = record()
        val pair = transport.inspect(record)
        when (pair) {
            ChapterPairState.ORIGINAL -> {
                // STAGED may still own temporary files; use explicit Restore to clean them.
                if (record.state in listOf(ChapterInstallState.COMMITTING, ChapterInstallState.INSTALLED, ChapterInstallState.RESTORING, ChapterInstallState.RECOVERY_REQUIRED)) {
                    publish(store.replace(record, record.copy(state = ChapterInstallState.RECOVERY_REQUIRED)), true)
                } else publish(record, false)
            }
            ChapterPairState.REPLACEMENT -> {
                check(record.mode != null)
                if (record.state in listOf(ChapterInstallState.COMMITTING, ChapterInstallState.INSTALLED, ChapterInstallState.RECOVERY_REQUIRED))
                    publish(store.replace(record, record.copy(state = ChapterInstallState.INSTALLED)), false)
                else publish(store.replace(record, record.copy(state = ChapterInstallState.RECOVERY_REQUIRED)), true)
            }
            ChapterPairState.MIXED, ChapterPairState.INTERRUPTED -> publish(store.replace(record, record.copy(state = ChapterInstallState.RECOVERY_REQUIRED)), true)
            ChapterPairState.UNKNOWN -> {
                publish(if (record.mode != null) store.replace(record, record.copy(state = ChapterInstallState.RECOVERY_REQUIRED)) else record, true)
                error("Unexpected files or game update; do not overwrite them automatically")
            }
        }
    }
    suspend fun confirmPlayback() = operation {
        val record = record()
        check(record.writeProtocol == ChapterWriteProtocol.IN_PLACE_V1) { "New write-back validation requires a fresh baseline" }
        check(!mutableState.value.needsInspection && record.state == ChapterInstallState.INSTALLED && record.mode == ChapterRebuildMode.UNCHANGED)
        check(transport.inspect(record) == ChapterPairState.REPLACEMENT)
        publish(store.replace(record, record.copy(playbackConfirmed = true)))
    }
    suspend fun restore(operatorApproved: Boolean) = operation {
        check(operatorApproved) { "Explicit restoration confirmation is required" }
        var record = record()
        check(record.mode != null) { "No prepared or installed test exists" }
        store.original(record, true); store.original(record, false)
        val status = transport.environment()
        check(status.identity == record.game && status.stopped) { "Force stop the matching JP FGO installation before restoring" }
        check(transport.inspect(record) != ChapterPairState.UNKNOWN) { "Unexpected live files; preserve the backup and review manually" }
        critical {
            record = store.replace(record, record.copy(state = ChapterInstallState.RESTORING)); publish(record, true)
            transport.restore(record)
            check(transport.inspect(record) == ChapterPairState.ORIGINAL)
            record = store.replace(record, record.copy(state = ChapterInstallState.RESTORED,
                unchangedVerified = record.unchangedVerified || (record.mode == ChapterRebuildMode.UNCHANGED && record.playbackConfirmed)))
            publish(record, false)
        }
    }
    private suspend fun critical(block: suspend () -> Unit) {
        currentCoroutineContext().ensureActive()
        mutableState.value = mutableState.value.copy(critical = true)
        try {
            currentCoroutineContext().ensureActive()
            withContext(NonCancellable) { block() }
        } finally { mutableState.value = mutableState.value.copy(critical = false) }
    }
    private suspend fun operation(block: suspend () -> Unit) {
        if (!mutex.tryLock()) return
        mutableState.value = mutableState.value.copy(busy = true, error = null)
        try { block() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            var recovery = mutableState.value.needsInspection
            val current = runCatching { store.load() }.getOrNull()
            if (current?.state in listOf(ChapterInstallState.COMMITTING, ChapterInstallState.RESTORING)) {
                recovery = true
                runCatching { store.replace(requireNotNull(current), current.copy(state = ChapterInstallState.RECOVERY_REQUIRED)) }
            }
            mutableState.value = mutableState.value.copy(record = runCatching { store.load() }.getOrNull() ?: current,
                needsInspection = recovery, error = failure.message ?: "Asset test failed")
        } finally {
            mutableState.value = mutableState.value.copy(busy = false, critical = false)
            mutex.unlock()
        }
    }
}
