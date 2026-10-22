package com.fgogotran.chapter.packinstall

import com.fgogotran.chapter.install.*
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.*

class StoryPackRecoverySessionTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun `legacy installed operation can export inspect and restore without a preparation gate`(): Unit = runBlocking {
        val fixture = PackInstallFixture(temporary.newFolder())
        fixture.desired(fixture.import())
        val historical = fixture.session()
        historical.prepare(fixture.reader); fixture.exported(historical); historical.install(true)
        fixture.gate = null
        val reads = fixture.reads
        val recovery = StoryPackRecoverySession(fixture.store, fixture.transport)
        recovery.reload()
        assertTrue(recovery.state.value.needsInspection)
        val zip = ByteArrayOutputStream()
        recovery.export({ zip }, { zip.toByteArray().inputStream() })
        assertNull(recovery.state.value.error)
        assertTrue(zip.size() > 0)
        recovery.inspect(); recovery.restore(true)
        assertNull(recovery.state.value.error)
        assertEquals(ChapterInstallState.RESTORED, recovery.state.value.operation!!.transport.state)
        assertEquals(reads, fixture.reads, "Recovery must not read or compile JP source scripts")
        assertEquals(1, fixture.commits)
        assertEquals(1, fixture.restores)
    }
    @Test fun `recovery rejects unknown game bytes and preserves original backups`(): Unit = runBlocking {
        val fixture = PackInstallFixture(temporary.newFolder())
        fixture.desired(fixture.import())
        fixture.session().prepare(fixture.reader)
        val operation = fixture.store.load()!!
        val original = fixture.store.bytes(operation.transport, true, false)
        File(fixture.live, ChapterInstallPolicy.BUNDLE).writeText("foreign content")
        val recovery = StoryPackRecoverySession(fixture.store, fixture.transport)
        recovery.restore(true)
        assertNotNull(recovery.state.value.error)
        assertEquals(0, fixture.restores)
        assertContentEquals(original, fixture.store.bytes(fixture.store.load()!!.transport, true, false))
        assertEquals("foreign content", File(fixture.live, ChapterInstallPolicy.BUNDLE).readText())
    }
}
