package com.fgogotran.chapter.install

import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.*

class ChapterTransactionEngineTest {
    @get:Rule val temporary = TemporaryFolder()
    private val record = InstallSamples.record()
    private var status = ChapterGameStatus(record.game, true)
    private lateinit var files: TestChapterInstallFiles
    private fun engine(): ChapterTransactionEngine {
        files = TestChapterInstallFiles(temporary.newFolder())
        files.putOriginals(InstallSamples.bundle, InstallSamples.manifest)
        return ChapterTransactionEngine(files, { status }, InstallSamples::validate)
    }
    private fun ChapterTransactionEngine.stage() = stage(record, InstallSamples.replacementBundle, InstallSamples.replacementManifest)
    private fun ChapterTransactionEngine.commit() = commit(record.copy(state = ChapterInstallState.COMMITTING))
    private fun ChapterTransactionEngine.restore() = restore(record.copy(state = ChapterInstallState.RESTORING), InstallSamples.bundle, InstallSamples.manifest)

    @Test fun `staging does not modify originals and commit installs bundle before manifest`() {
        val engine = engine(); engine.stage()
        assertEquals(ChapterPairState.ORIGINAL, engine.inspect(record))
        engine.commit()
        assertEquals(ChapterPairState.REPLACEMENT, engine.inspect(record))
        assertEquals(listOf(ChapterInstallPolicy.BUNDLE, ChapterInstallPolicy.MANIFEST), files.writes.filter { it in listOf(ChapterInstallPolicy.BUNDLE, ChapterInstallPolicy.MANIFEST) })
        engine.restore()
        assertEquals(ChapterPairState.ORIGINAL, engine.inspect(record))
    }
    @Test fun `running game is rejected before creating even the lock file`() {
        val engine = engine(); status = status.copy(stopped = false)
        assertFailsWith<IllegalStateException> { engine.stage() }
        assertEquals(0, files.lockCount); assertTrue(files.writes.isEmpty())
    }
    @Test fun `changed installation is rejected before writes`() {
        val engine = engine(); status = status.copy(identity = status.identity.copy(updatedAt = status.identity.updatedAt + 1))
        assertFailsWith<IllegalStateException> { engine.stage() }; assertTrue(files.writes.isEmpty())
    }
    @Test fun `changed original manifest blocks staging`() {
        val engine = engine(); files.corruptManifest()
        assertEquals(ChapterPairState.UNKNOWN, engine.inspect(record))
        assertFailsWith<IllegalStateException> { engine.stage() }; assertTrue(files.writes.isEmpty())
    }
    @Test fun `corrupt replacement is rejected before staging`() {
        val engine = engine()
        assertFailsWith<IllegalArgumentException> { engine.stage(record, "corrupt".toByteArray(), InstallSamples.replacementManifest) }
        assertTrue(files.writes.isEmpty())
    }
    @Test fun `low space blocks staging and originals remain intact`() {
        val engine = engine(); files.available = 0
        assertFailsWith<IllegalStateException> { engine.stage() }
        assertEquals(ChapterPairState.ORIGINAL, engine.inspect(record)); assertTrue(files.writes.isEmpty())
    }
    @Test fun `unsupported staging semantics block installation`() {
        val engine = engine(); files.unsupportedProbe = true
        assertFails { engine.stage() }; assertTrue(files.writes.isEmpty())
        assertEquals(ChapterPairState.ORIGINAL, engine.inspect(record))
    }
    @Test fun `permission failure never truncates originals`() {
        val engine = engine(); files.denyStage = true
        assertFails { engine.stage() }; assertEquals(ChapterPairState.ORIGINAL, engine.inspect(record))
    }
    @Test fun `unsupported live write access is refused and staging cleaned before replacement`() {
        val engine = engine(); files.denyReplacementAccess = true
        assertFailsWith<IllegalStateException> { engine.stage() }
        assertEquals(0, files.replacements)
        assertEquals(ChapterPairState.ORIGINAL, engine.inspect(record))
        assertFalse(files.exists(ChapterInstallPolicy.stageName(record.id, "bundle")))
        assertFalse(files.exists(ChapterInstallPolicy.stageName(record.id, "manifest")))
    }
    @Test fun `commit rechecks staged access before replacing either live file`() {
        val engine = engine(); engine.stage(); files.denyReplacementAccess = true
        assertFailsWith<IllegalStateException> { engine.commit() }
        assertEquals(0, files.replacements)
        assertEquals(ChapterPairState.ORIGINAL, engine.inspect(record))
    }
    @Test fun `failure before first replacement leaves original pair recoverable`() {
        val engine = engine(); engine.stage(); files.failBeforeReplace = 1
        assertFails { engine.commit() }; assertEquals(ChapterPairState.ORIGINAL, engine.inspect(record))
        files.failBeforeReplace = 0; engine.restore(); assertEquals(ChapterPairState.ORIGINAL, engine.inspect(record))
    }
    @Test fun `failure after bundle replacement creates known mixed pair and exact restore works`() {
        val engine = engine(); engine.stage(); files.failAfterReplace = 1
        assertFails { engine.commit() }; assertEquals(ChapterPairState.MIXED, engine.inspect(record))
        files.failAfterReplace = 0; engine.restore(); assertEquals(ChapterPairState.ORIGINAL, engine.inspect(record))
    }
    @Test fun `matching replacement bytes without durable completion remain interrupted`() {
        val engine = engine(); engine.stage(); files.failAfterReplace = 2
        assertFails { engine.commit() }; assertEquals(ChapterPairState.INTERRUPTED, engine.inspect(record))
        files.failAfterReplace = 0; engine.restore(); assertEquals(ChapterPairState.ORIGINAL, engine.inspect(record))
    }
    @Test fun `interrupted restoration can be retried from mixed hashes`() {
        val engine = engine(); engine.stage(); engine.commit(); files.failAfterReplace = 3
        assertFails { engine.restore() }; assertEquals(ChapterPairState.MIXED, engine.inspect(record))
        files.failAfterReplace = 0; engine.restore(); assertEquals(ChapterPairState.ORIGINAL, engine.inspect(record))
    }
    @Test fun `unknown live files block restore without touching them`() {
        val engine = engine(); engine.stage(); engine.commit(); files.corruptManifest()
        val before = files.replacements
        assertFailsWith<IllegalStateException> { engine.restore() }; assertEquals(before, files.replacements)
    }
    @Test fun `corrupt backup blocks restoration`() {
        val engine = engine(); engine.stage(); engine.commit()
        assertFailsWith<IllegalArgumentException> { engine.restore(record.copy(state = ChapterInstallState.RESTORING), "bad backup".toByteArray(), InstallSamples.manifest) }
        assertEquals(ChapterPairState.REPLACEMENT, engine.inspect(record))
    }
    @Test fun `missing or modified stage blocks both replacements`() {
        val engine = engine(); engine.stage()
        files.deleteStage(ChapterInstallPolicy.stageName(record.id, "manifest"))
        assertFails { engine.commit() }; assertEquals(0, files.replacements)
    }
    @Test fun `identity running state is checked again before manifest replacement`() {
        val delegate = TestChapterInstallFiles(temporary.newFolder()).also { it.putOriginals(InstallSamples.bundle, InstallSamples.manifest) }
        delegate.onLiveSynced = { status = status.copy(stopped = false) }
        val engine = ChapterTransactionEngine(delegate, { status }, InstallSamples::validate)
        engine.stage(); assertFailsWith<IllegalStateException> { engine.commit() }
        assertEquals(1, delegate.replacements); assertEquals(ChapterPairState.MIXED, engine.inspect(record))
    }
    @Test fun `unavailable process inspection blocks even lock creation`() {
        val files = TestChapterInstallFiles(temporary.newFolder())
        val engine = ChapterTransactionEngine(files, { error("process inspection unavailable") }, InstallSamples::validate)
        assertFailsWith<IllegalStateException> { engine.stage() }; assertEquals(0, files.lockCount)
    }
}
