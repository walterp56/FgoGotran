package com.fgogotran.chapter.packinstall

import com.fgogotran.chapter.install.*
import java.io.File
import kotlinx.coroutines.*
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.*

class StoryPackInstallSessionTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun fixture() = PackInstallFixture(temporary.newFolder())

    @Test fun `complete selected set shares one rebuild and native transaction then restores exact originals`(): Unit = runBlocking {
        val f = fixture(); val intro = f.import(); val fuyuki = f.import("fuyuki", "Second")
        f.desired(intro, fuyuki)
        val session = f.session(); session.prepare(f.reader)
        assertNull(session.state.value.error)
        val prepared = assertNotNull(f.store.load())
        assertEquals(2, prepared.references.size); assertEquals(ChapterRebuildMode.LANGUAGE_PACK, prepared.transport.mode)
        assertEquals(f.raw, f.currentText()); assertEquals(0, f.stages)
        f.exported(session); session.install(true)
        assertNull(session.state.value.error); assertEquals(ChapterInstallState.INSTALLED, f.store.load()!!.transport.state)
        assertEquals(1, f.stages); assertEquals(1, f.commits)
        assertEquals(f.raw.replace("First", "Translated intro").replace("Second", "Translated fuyuki"), f.currentText())
        val reopened = f.session(); val inspections = f.inspections
        reopened.reload(); assertEquals(inspections, f.inspections); assertTrue(reopened.state.value.needsInspection)
        reopened.inspect(); assertFalse(reopened.state.value.needsInspection)
        reopened.restore(true)
        assertEquals(ChapterInstallState.RESTORED, f.store.load()!!.transport.state)
        assertContentEquals(f.original.bytes.toByteArray(), f.files.read(ChapterInstallPolicy.BUNDLE))
        assertContentEquals(f.index.bytes.toByteArray(), f.files.read(ChapterInstallPolicy.MANIFEST))
        assertEquals(2, f.store.loadSelection().archives.size)
    }

    @Test fun `updates and removal require explicit restoration and never lose other selected chapter edits`(): Unit = runBlocking {
        val f = fixture(); val intro = f.import(); val fuyuki = f.import("fuyuki", "Second")
        f.desired(intro, fuyuki); val session = f.session(); session.prepare(f.reader); f.exported(session); session.install(true)
        val firstId = f.store.load()!!.transport.id
        f.desired(fuyuki)
        assertTrue(f.currentText().contains("Translated intro")) // Intent alone never writes.
        session.prepare(f.reader); assertNotNull(session.state.value.error); assertEquals(firstId, f.store.load()!!.transport.id)
        session.restore(true); session.prepare(f.reader); f.exported(session); session.install(true)
        assertNull(session.state.value.error)
        assertEquals(f.raw.replace("Second", "Translated fuyuki"), f.currentText())
        assertNotEquals(firstId, f.store.load()!!.transport.id)
        assertTrue(File(f.root, firstId).isDirectory)
        f.desired(); session.restore(true)
        assertTrue(f.store.loadSelection().archives.isEmpty()); assertEquals(f.raw, f.currentText())
        assertEquals(2, f.library.list().size)
    }

    @Test fun `unproven gate fails before source reads compilation or game writes`(): Unit = runBlocking {
        val f = fixture(); f.desired(f.import()); f.gate = null
        val session = f.session(); session.reload(); assertNull(session.state.value.operation)
        session.prepare(f.reader)
        assertNotNull(session.state.value.error); assertEquals(0, f.reads); assertEquals(0, f.stages)
        assertNull(f.store.load()); assertTrue(f.files.writes.isEmpty())
        assertFalse(session.state.value.blocksAssetTests)
    }

    @Test fun `selection changes export failures approval and native gate changes block before staging`(): Unit = runBlocking {
        val f = fixture(); val entry = f.import(); f.desired(entry)
        val session = f.session(); session.prepare(f.reader)
        session.install(true); assertEquals(0, f.stages) // No recovery receipt.
        f.exported(session); session.install(false); assertEquals(0, f.stages)
        f.desired(); session.install(true); assertEquals(0, f.stages)
        f.desired(entry); session.restore(true); session.prepare(f.reader); f.exported(session)
        f.gate = null; session.install(true); assertEquals(0, f.stages)
        assertEquals(f.raw, f.currentText())
    }

    @Test fun `partial bundle or manifest writes survive restart and repeated interrupted restoration`(): Unit = runBlocking {
        for (failure in listOf(1, 2)) {
            val f = fixture(); f.desired(f.import()); val session = f.session(); session.prepare(f.reader); f.exported(session)
            val record = f.store.load()!!.transport
            val original = f.store.bytes(record, failure == 1, false)
            val replacement = f.store.bytes(record, failure == 1, true)
            // Encrypted headers may share a prefix: fail only after a genuinely changed byte.
            f.files.partialBytes = (0 until minOf(original.size, replacement.size)).first { original[it] != replacement[it] } + 1
            f.files.failDuringWrite = failure; session.install(true)
            assertEquals(ChapterInstallState.RECOVERY_REQUIRED, f.store.load()!!.transport.state)
            f.files.failDuringWrite = 0
            val reopened = f.session(); reopened.reload(); reopened.inspect()
            assertEquals(ChapterInstallState.RECOVERY_REQUIRED, f.store.load()!!.transport.state)
            f.files.failDuringWrite = f.files.replacements + 1
            reopened.restore(true); assertEquals(ChapterInstallState.RECOVERY_REQUIRED, f.store.load()!!.transport.state)
            f.files.failDuringWrite = 0; f.session().restore(true)
            assertEquals(ChapterInstallState.RESTORED, f.store.load()!!.transport.state)
            assertEquals(f.raw, f.currentText())
        }
    }

    @Test fun `journal failure before or after live commit does not falsely acknowledge installation`(): Unit = runBlocking {
        val f = fixture(); f.desired(f.import()); val session = f.session(); session.prepare(f.reader); f.exported(session)
        f.durable.beforeState = ChapterInstallState.COMMITTING; session.install(true)
        assertEquals(0, f.commits); assertEquals(f.raw, f.currentText())
        f.durable.beforeState = null; session.restore(true); session.prepare(f.reader); f.exported(session)
        f.durable.beforeState = ChapterInstallState.INSTALLED; session.install(true)
        assertEquals(ChapterInstallState.RECOVERY_REQUIRED, f.store.load()!!.transport.state)
        f.durable.beforeState = null; f.session().restore(true); assertEquals(f.raw, f.currentText())
    }

    @Test fun `unknown new resource index and game update never get overwritten by recovery`(): Unit = runBlocking {
        val f = fixture(); f.desired(f.import()); val session = f.session(); session.prepare(f.reader); f.exported(session); session.install(true)
        val index = File(f.live, ChapterInstallPolicy.MANIFEST)
        index.writeBytes("genuine newer index".toByteArray())
        val writes = f.files.replacements
        session.restore(true); assertNotNull(session.state.value.error); assertEquals(writes, f.files.replacements)
        assertContentEquals("genuine newer index".toByteArray(), index.readBytes())
        f.status = f.status.copy(identity = f.game.copy(updatedAt = f.game.updatedAt + 1))
        session.restore(true); assertEquals(writes, f.files.replacements)
    }

    @Test fun `critical commit completes despite Activity cancellation and cannot be displaced by another action`(): Unit = runBlocking {
        val f = fixture(); f.desired(f.import()); val session = f.session(); session.prepare(f.reader); f.exported(session)
        val entered = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>()
        f.beforeCommit = { entered.complete(Unit); finish.await() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val work = scope.launch { session.install(true) }
            withTimeout(5000) { entered.await() }; assertTrue(session.state.value.critical)
            work.cancel(); session.prepare(f.reader); assertEquals(1, f.commits)
            finish.complete(Unit); withTimeout(5000) { work.join() }
            assertEquals(ChapterInstallState.INSTALLED, f.store.load()!!.transport.state)
            assertFalse(session.state.value.critical)
        } finally { finish.complete(Unit); scope.cancel() }
    }

    @Test fun `restoration is independent of missing source packs corrupt desired intent and a lost native gate`(): Unit = runBlocking {
        val f = fixture(); val entry = f.import(); f.desired(entry)
        val session = f.session(); session.prepare(f.reader); f.exported(session); session.install(true)
        f.gate = null
        File(f.root, "desired.json").writeText("corrupt intent")
        check(File(f.folder, "library/archives/${entry.sha256}.fgostory.zip").delete())
        session.restore(true)
        assertNull(session.state.value.error); assertEquals(ChapterInstallState.RESTORED, f.store.load()!!.transport.state)
        assertEquals(f.raw, f.currentText())
    }
}
