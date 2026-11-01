package com.fgogotran.chapter.install

import kotlinx.coroutines.*
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.test.*

class ChapterInstallSessionTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var store: ChapterBackupStore
    private lateinit var files: TestChapterInstallFiles
    private lateinit var transport: TestInstallTransport
    private lateinit var root: File
    private lateinit var durable: TestChapterDurableFiles
    private fun setup(exported: Boolean = true): ChapterInstallRecord {
        root = temporary.newFolder()
        durable = TestChapterDurableFiles()
        store = ChapterBackupStore(root, durable)
        val baseline = store.create(InstallSamples.game, InstallSamples.bundle, InstallSamples.manifest)
        var record = store.savePrepared(baseline, ChapterRebuildMode.UNCHANGED, InstallSamples.replacementBundle, InstallSamples.replacementManifest)
        if (exported) record = store.replace(record, record.copy(exportSha256 = "e".repeat(64)))
        files = TestChapterInstallFiles(temporary.newFolder()).also { it.putOriginals(InstallSamples.bundle, InstallSamples.manifest) }
        transport = TestInstallTransport(files, store)
        return record
    }
    @Test fun `installation persists intent before writing and reports verified pair only`() = runBlocking {
        setup(); val session = ChapterInstallSession(store, transport)
        session.install(true)
        assertEquals(ChapterInstallState.INSTALLED, store.load()?.state)
        assertEquals(ChapterPairState.REPLACEMENT, transport.engine.inspect(requireNotNull(store.load())))
        assertFalse(session.state.value.busy); assertNull(session.state.value.error)
    }
    @Test fun `operator confirmation cannot be bypassed by prepared files`() = runBlocking<Unit> {
        setup(); val session = ChapterInstallSession(store, transport)
        session.install(false)
        assertEquals(0, transport.stages); assertEquals(0, transport.commits); assertNotNull(session.state.value.error)
    }
    @Test fun `export is mandatory before staging`() = runBlocking {
        setup(false); val session = ChapterInstallSession(store, transport)
        session.install(true)
        assertEquals(0, transport.stages); assertEquals(ChapterInstallState.PREPARED, store.load()?.state)
    }
    @Test fun `corrupt original backup blocks installation before staging`() = runBlocking<Unit> {
        val record = setup(); File(root, "${record.id}/original-manifest").writeText("bad")
        val session = ChapterInstallSession(store, transport); session.install(true)
        assertEquals(0, transport.stages); assertNotNull(session.state.value.error)
    }
    @Test fun `corrupt prepared file blocks staging`() = runBlocking {
        val record = setup(); File(root, "${record.id}/prepared-bundle").writeText("bad")
        val session = ChapterInstallSession(store, transport); session.install(true)
        assertEquals(0, transport.stages); assertEquals(0, files.replacements)
    }
    @Test fun `mid commit failure becomes recoverable and restore produces exact originals`() = runBlocking {
        setup(); files.failAfterReplace = 1
        val session = ChapterInstallSession(store, transport); session.install(true)
        assertEquals(ChapterInstallState.RECOVERY_REQUIRED, store.load()?.state); assertTrue(session.state.value.needsInspection)
        files.failAfterReplace = 0; session.restore(true)
        assertEquals(ChapterInstallState.RESTORED, store.load()?.state)
        assertEquals(ChapterPairState.ORIGINAL, transport.engine.inspect(requireNotNull(store.load())))
        assertFalse(requireNotNull(store.load()).unchangedVerified)
    }
    @Test fun `post second write failure still needs restore without completion receipt`() = runBlocking {
        setup(); files.failAfterReplace = 2
        val session = ChapterInstallSession(store, transport); session.install(true)
        assertEquals(ChapterInstallState.RECOVERY_REQUIRED, store.load()?.state)
        session.inspect()
        assertEquals(ChapterInstallState.RECOVERY_REQUIRED, store.load()?.state); assertTrue(session.state.value.needsInspection)
        files.failAfterReplace = 0; session.restore(true)
        assertEquals(ChapterInstallState.RESTORED, store.load()?.state)
    }
    @Test fun `fresh session does no remote work until inspection is explicitly requested`() = runBlocking {
        var record = setup()
        transport.engine.stage(record, InstallSamples.replacementBundle, InstallSamples.replacementManifest)
        record = store.replace(record, record.copy(state = ChapterInstallState.COMMITTING))
        files.failAfterReplace = 1; assertFails { transport.engine.commit(record) }; files.failAfterReplace = 0
        val session = ChapterInstallSession(ChapterBackupStore(root, TestChapterDurableFiles()), transport)
        session.reload()
        assertTrue(session.state.value.needsInspection); assertEquals(0, transport.inspections)
        session.inspect(); assertEquals(ChapterInstallState.RECOVERY_REQUIRED, store.load()?.state)
        session.restore(true); assertEquals(ChapterInstallState.RESTORED, store.load()?.state)
    }
    @Test fun `fresh session restores a partial live write using persisted file evidence`() = runBlocking {
        setup(); files.failDuringWrite = 1
        ChapterInstallSession(store, transport).install(true)
        assertEquals(ChapterInstallState.RECOVERY_REQUIRED, store.load()?.state)
        files.failDuringWrite = 0
        val reopened = ChapterInstallSession(ChapterBackupStore(root, TestChapterDurableFiles()), transport)
        reopened.reload(); assertTrue(reopened.state.value.needsInspection)
        reopened.inspect(); assertEquals(ChapterInstallState.RECOVERY_REQUIRED, store.load()?.state)
        reopened.restore(true)
        assertEquals(ChapterInstallState.RESTORED, store.load()?.state)
        assertFalse(requireNotNull(store.load()).unchangedVerified)
        assertNull(reopened.state.value.error)
    }
    @Test fun `unexpected files block restore without erasing backups`() = runBlocking<Unit> {
        setup(); val session = ChapterInstallSession(store, transport); session.install(true)
        files.corruptManifest(); val writes = files.replacements
        session.restore(true)
        assertEquals(writes, files.replacements); assertContentEquals(InstallSamples.bundle, store.original(requireNotNull(store.load()), true))
        assertNotNull(session.state.value.error)
    }
    @Test fun `manual playback confirmation plus verified restoration unlocks marker`() = runBlocking {
        setup(); val session = ChapterInstallSession(store, transport)
        session.install(true); session.confirmPlayback(); assertTrue(requireNotNull(store.load()).playbackConfirmed)
        session.restore(true); assertTrue(requireNotNull(store.load()).unchangedVerified)
    }
    @Test fun `originals restored without manual playback do not unlock marker`() = runBlocking {
        setup(); val session = ChapterInstallSession(store, transport)
        session.install(true); session.restore(true)
        assertFalse(requireNotNull(store.load()).unchangedVerified)
    }
    @Test fun `export is marked verified only after matching read back`() = runBlocking {
        setup(false); val session = ChapterInstallSession(store, transport); val output = ByteArrayOutputStream()
        session.export({ output }, { output.toByteArray().inputStream() })
        assertNotNull(requireNotNull(store.load()).exportSha256); assertNull(session.state.value.error)
    }
    @Test fun `failed export read back keeps installation blocked`() = runBlocking<Unit> {
        setup(false); val session = ChapterInstallSession(store, transport)
        session.export({ ByteArrayOutputStream() }, { "bad read-back".byteInputStream() })
        assertNull(requireNotNull(store.load()).exportSha256); assertNotNull(session.state.value.error)
    }
    @Test fun `cancellation before commit never enters replacement phase`() = runBlocking {
        setup(); val entered = CompletableDeferred<Unit>()
        transport.onStage = { entered.complete(Unit); awaitCancellation() }
        val session = ChapterInstallSession(store, transport)
        val job = launch { session.install(true) }
        withTimeout(5000) { entered.await() }; job.cancelAndJoin()
        assertEquals(0, transport.commits); assertEquals(0, files.replacements)
        assertEquals(ChapterInstallState.PREPARED, store.load()?.state); assertFalse(session.state.value.busy)
    }
    @Test fun `cancellation inside critical commit still finishes and journals installed state`() = runBlocking {
        setup(); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        transport.onCommit = { entered.complete(Unit); release.await() }
        val session = ChapterInstallSession(store, transport)
        val job = launch { session.install(true) }
        withTimeout(5000) { entered.await() }; assertTrue(session.state.value.critical)
        job.cancel(); release.complete(Unit); job.join()
        assertEquals(ChapterInstallState.INSTALLED, store.load()?.state)
        assertEquals(ChapterPairState.REPLACEMENT, transport.engine.inspect(requireNotNull(store.load())))
        assertFalse(session.state.value.busy)
    }
    @Test fun `concurrent restore is not interleaved with commit`() = runBlocking {
        setup(); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        transport.onCommit = { entered.complete(Unit); release.await() }
        val session = ChapterInstallSession(store, transport); val install = launch { session.install(true) }
        withTimeout(5000) { entered.await() }; session.restore(true)
        assertEquals(0, transport.restores); release.complete(Unit); install.join()
        assertEquals(ChapterInstallState.INSTALLED, store.load()?.state)
    }
    @Test fun `failed durable commit intent never calls replacement`() = runBlocking {
        setup(); durable.failState = ChapterInstallState.COMMITTING
        val session = ChapterInstallSession(store, transport); session.install(true)
        assertEquals(0, transport.commits); assertEquals(0, files.replacements)
        assertEquals(ChapterInstallState.STAGED, store.load()?.state)
    }
    @Test fun `failed sync after commit intent still recovers without any live replacement`() = runBlocking {
        setup(); durable.failAfterState = ChapterInstallState.COMMITTING
        val session = ChapterInstallSession(store, transport); session.install(true)
        assertEquals(0, transport.commits); assertEquals(0, files.replacements)
        assertEquals(ChapterInstallState.RECOVERY_REQUIRED, store.load()?.state)
        session.restore(true); assertEquals(ChapterInstallState.RESTORED, store.load()?.state)
    }
    @Test fun `journal failure after actual replacement is recovered from hashes`() = runBlocking {
        setup(); durable.failState = ChapterInstallState.INSTALLED
        val session = ChapterInstallSession(store, transport); session.install(true)
        assertEquals(ChapterPairState.REPLACEMENT, transport.engine.inspect(requireNotNull(store.load())))
        assertEquals(ChapterInstallState.RECOVERY_REQUIRED, store.load()?.state)
        durable.failState = null; session.inspect()
        assertEquals(ChapterInstallState.INSTALLED, store.load()?.state)
    }
    @Test fun `failed replacement export revokes the old verification receipt`() = runBlocking {
        setup(); val session = ChapterInstallSession(store, transport)
        session.export({ ByteArrayOutputStream() }, { "corrupt replacement export".byteInputStream() })
        assertNull(requireNotNull(store.load()).exportSha256)
        session.install(true); assertEquals(0, transport.stages)
    }
}
