package com.fgogotran.chapter.packinstall

import com.fgogotran.chapter.install.*
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex

data class StoryPackRecoveryState(val operation: StoryPackOperation? = null, val busy: Boolean = false,
    val critical: Boolean = false, val needsInspection: Boolean = false,
    val error: String? = null, val journalBlocked: Boolean = false) {
    val blocksAssetTests: Boolean get() = busy || journalBlocked || operation?.transport?.state?.let { it != ChapterInstallState.RESTORED } == true
}

/** Recovery-only compatibility for saved source-pack journals. No source import or new installation. */
open class StoryPackRecoverySession internal constructor(
    protected val store: StoryPackOperationStore,
    protected val transport: ChapterInstallTransport
) {
    private val mutex = Mutex()
    protected val mutableState = MutableStateFlow(StoryPackRecoveryState())
    val state = mutableState.asStateFlow()
    protected fun publish(value: StoryPackOperation?, inspection: Boolean = mutableState.value.needsInspection) {
        mutableState.value = mutableState.value.copy(operation = value, needsInspection = inspection)
    }
    protected fun active() = requireNotNull(store.load()) { "No saved source-pack operation exists" }
    protected fun change(operation: StoryPackOperation, state: ChapterInstallState) = store.replace(operation, operation.transport.copy(state = state))
    suspend fun reload() = operation {
        val value = store.load()
        publish(value, value?.transport?.state in listOf(ChapterInstallState.STAGED, ChapterInstallState.COMMITTING,
            ChapterInstallState.INSTALLED, ChapterInstallState.RESTORING, ChapterInstallState.RECOVERY_REQUIRED))
        mutableState.value = mutableState.value.copy(journalBlocked = false)
    }
    suspend fun export(output: () -> OutputStream, readBack: () -> InputStream) = operation {
        var value = active()
        if (value.transport.exportSha256 != null) { value = store.replace(value, value.transport.copy(exportSha256 = null)); publish(value) }
        val digest = MessageDigest.getInstance("SHA-256"); var size = 0L
        val stream = object : FilterOutputStream(output()) {
            override fun write(b: Int) { out.write(b); digest.update(b.toByte()); size++ }
            override fun write(b: ByteArray, offset: Int, length: Int) { out.write(b, offset, length); digest.update(b, offset, length); size += length }
        }
        stream.use { store.export(value, it) }
        val expected = digest.digest(); val actual = MessageDigest.getInstance("SHA-256"); var received = 0L
        readBack().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer); if (count < 0) break
                received += count; require(received <= 2L * ChapterInstallPolicy.MAX_FILE + StoryPackOperationJson.MAX + 128 * 1024)
                actual.update(buffer, 0, count)
            }
        }
        check(size == received && expected.contentEquals(actual.digest())) { "Recovery export read-back failed; installation stays blocked" }
        publish(store.replace(value, value.transport.copy(exportSha256 = expected.joinToString("") { "%02x".format(it.toInt() and 255) })))
    }
    suspend fun inspect() = operation {
        val value = active(); val record = value.transport
        when (transport.inspect(record)) {
            ChapterPairState.ORIGINAL -> publish(if (record.state in listOf(ChapterInstallState.PREPARED, ChapterInstallState.RESTORED)) value
                else change(value, ChapterInstallState.RECOVERY_REQUIRED), record.state !in listOf(ChapterInstallState.PREPARED, ChapterInstallState.RESTORED))
            ChapterPairState.REPLACEMENT -> {
                if (record.state in listOf(ChapterInstallState.COMMITTING, ChapterInstallState.INSTALLED, ChapterInstallState.RECOVERY_REQUIRED))
                    publish(change(value, ChapterInstallState.INSTALLED), false)
                else publish(change(value, ChapterInstallState.RECOVERY_REQUIRED), true)
            }
            ChapterPairState.MIXED, ChapterPairState.INTERRUPTED -> publish(change(value, ChapterInstallState.RECOVERY_REQUIRED), true)
            ChapterPairState.UNKNOWN -> {
                publish(change(value, ChapterInstallState.RECOVERY_REQUIRED), true)
                error("Unexpected game files/index or installation update; preserve backups, never overwrite automatically")
            }
        }
    }
    suspend fun restore(approved: Boolean) = operation {
        check(approved) { "Explicit restore/close confirmation required" }
        var value = active()
        store.bytes(value.transport, true, false); store.bytes(value.transport, false, false)
        val environment = transport.environment()
        check(environment.stopped && environment.identity == value.transport.game) { "Force stop the matching installation; never restore an old index after an update" }
        check(transport.inspect(value.transport) != ChapterPairState.UNKNOWN) { "Unexpected live bytes; preserve recovery data for review" }
        critical {
            value = change(value, ChapterInstallState.RESTORING); publish(value, true)
            transport.restore(value.transport)
            check(transport.inspect(value.transport) == ChapterPairState.ORIGINAL)
            value = change(value, ChapterInstallState.RESTORED); publish(value, false)
        }
        // Desired intent and source archives remain untouched. Removed packs are never silently reapplied.
    }
    protected suspend fun critical(block: suspend () -> Unit) {
        currentCoroutineContext().ensureActive()
        mutableState.value = mutableState.value.copy(critical = true)
        try { withContext(NonCancellable) { block() } }
        finally { mutableState.value = mutableState.value.copy(critical = false) }
    }
    protected suspend fun operation(block: suspend () -> Unit) {
        if (!mutex.tryLock()) return
        mutableState.value = mutableState.value.copy(busy = true, error = null)
        try { block() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            val loaded = runCatching { store.load() }
            val value = loaded.getOrNull()
            val recovery = value?.transport?.state in listOf(ChapterInstallState.STAGED, ChapterInstallState.COMMITTING, ChapterInstallState.INSTALLED, ChapterInstallState.RESTORING, ChapterInstallState.RECOVERY_REQUIRED)
            val retained = if (recovery && value != null && value.transport.state != ChapterInstallState.INSTALLED)
                runCatching { change(value, ChapterInstallState.RECOVERY_REQUIRED) }.getOrDefault(value) else value
            mutableState.value = mutableState.value.copy(operation = retained ?: mutableState.value.operation, needsInspection = recovery || mutableState.value.needsInspection, journalBlocked = loaded.isFailure,
                error = (failure.message ?: "Language-pack operation failed").take(240))
        } finally {
            mutableState.value = mutableState.value.copy(busy = false, critical = false); mutex.unlock()
        }
    }
}
