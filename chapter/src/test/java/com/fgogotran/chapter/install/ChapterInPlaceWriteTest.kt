package com.fgogotran.chapter.install

import java.io.File
import kotlin.random.Random
import kotlin.test.*
import org.junit.Rule
import org.junit.rules.TemporaryFolder

/** Real temporary-file overwrites; simulated faults/UIDs, not Android/FUSE/power-loss proof. */
class ChapterInPlaceWriteTest {
    @get:Rule val temporary = TemporaryFolder()
    private val plan = InstallSamples.record()
    private val committing = plan.copy(state = ChapterInstallState.COMMITTING)
    private val restoring = plan.copy(state = ChapterInstallState.RESTORING)
    private lateinit var root: File
    private lateinit var files: TestChapterInstallFiles
    private fun engine(): ChapterTransactionEngine {
        root = temporary.newFolder()
        files = TestChapterInstallFiles(root).also { it.putOriginals(InstallSamples.bundle, InstallSamples.manifest) }
        return reopened(files)
    }
    private fun reopened(adapter: TestChapterInstallFiles) = ChapterTransactionEngine(adapter, { ChapterGameStatus(plan.game, true) }, InstallSamples::validate)
    private fun ChapterTransactionEngine.stage() = stage(plan, InstallSamples.replacementBundle, InstallSamples.replacementManifest)
    private fun ChapterTransactionEngine.restore(record: ChapterInstallRecord = restoring) = restore(record, InstallSamples.bundle, InstallSamples.manifest)

    @Test fun `in place install and restore retain both original identities and exact bytes`() {
        val engine = engine()
        val identity = listOf(ChapterInstallPolicy.BUNDLE, ChapterInstallPolicy.MANIFEST).associateWith(files::identity)
        engine.stage(); engine.commit(committing)
        assertEquals(ChapterPairState.REPLACEMENT, engine.inspect(committing))
        identity.forEach { (name, value) -> assertEquals(value, files.identity(name)) }
        engine.restore()
        identity.forEach { (name, value) -> assertEquals(value, files.identity(name)) }
        assertContentEquals(InstallSamples.bundle, files.read(ChapterInstallPolicy.BUNDLE))
        assertContentEquals(InstallSamples.manifest, files.read(ChapterInstallPolicy.MANIFEST))
        assertTrue(root.listFiles().orEmpty().all { !it.name.startsWith(".fgogotran-${plan.id}-") })
    }
    @Test fun `before image and durable intent publication failures precede live writes`() {
        for (part in listOf(".new.before", ".new.intent")) for (after in listOf(false, true)) {
            val engine = engine(); engine.stage()
            if (after) files.failAfterStagePart = part else files.failStagePart = part
            assertFails { engine.commit(committing) }
            assertEquals(0, files.replacements)
            assertEquals(ChapterPairState.ORIGINAL, engine.inspect(committing))
            files.failAfterStagePart = null; files.failStagePart = null
            engine.restore(); assertEquals(ChapterPairState.ORIGINAL, engine.inspect(restoring))
        }
    }
    @Test fun `partial bundle or manifest overwrite can be restored after adapter restart`() {
        for (write in 1..2) {
            val engine = engine(); engine.stage(); files.failDuringWrite = write
            assertFails { engine.commit(committing) }
            assertEquals(ChapterPairState.INTERRUPTED, engine.inspect(committing))
            val restarted = TestChapterInstallFiles(root)
            val recovered = reopened(restarted)
            assertEquals(ChapterPairState.INTERRUPTED, recovered.inspect(restoring))
            recovered.restore(); assertEquals(ChapterPairState.ORIGINAL, recovered.inspect(restoring))
        }
    }
    @Test fun `interrupted restoration is repeatable after each live file and across restarts`() {
        for (write in 3..4) {
            val engine = engine(); engine.stage(); engine.commit(committing)
            files.failDuringWrite = write
            assertFails { engine.restore() }
            assertEquals(ChapterPairState.INTERRUPTED, engine.inspect(restoring))
            val fresh = reopened(TestChapterInstallFiles(root))
            fresh.restore(); assertEquals(ChapterPairState.ORIGINAL, fresh.inspect(restoring))
        }
    }
    @Test fun `restoring an interrupted install can itself be interrupted without losing its before image`() {
        val engine = engine(); engine.stage(); files.failDuringWrite = 1; files.partialBytes = 5
        assertFails { engine.commit(committing) }
        files.failDuringWrite = 2; files.partialBytes = 2
        assertFails { engine.restore() }
        assertEquals(ChapterPairState.INTERRUPTED, engine.inspect(restoring))
        val before = ChapterInstallPolicy.writeName(plan.id, "bundle", true, "before")
        val saved = files.read(before)
        files.failDuringWrite = 3; files.partialBytes = 3
        assertFails { engine.restore() }
        assertContentEquals(saved, files.read(before))
        files.failDuringWrite = 0; engine.restore()
        assertEquals(ChapterPairState.ORIGINAL, engine.inspect(restoring))
    }
    @Test fun `complete overwrite with old tail before final truncation is recoverable`() {
        val engine = engine(); engine.stage(); files.failBeforeTruncate = 1
        assertFails { engine.commit(committing) }
        assertEquals(ChapterPairState.INTERRUPTED, engine.inspect(committing))
        files.failBeforeTruncate = 0; engine.restore()
        assertEquals(ChapterPairState.ORIGINAL, engine.inspect(restoring))
    }
    @Test fun `missing write evidence does not authorise an unknown live file`() {
        for (part in listOf("before", "intent")) {
            val engine = engine(); engine.stage(); files.failDuringWrite = 1
            assertFails { engine.commit(committing) }
            files.deleteStage(ChapterInstallPolicy.writeName(plan.id, "bundle", false, part))
            val count = files.replacements
            assertFails { engine.restore() }; assertEquals(count, files.replacements)
        }
    }
    @Test fun `corrupted before image intent or prepared bytes fail closed`() {
        for (part in listOf("before", "intent", "stage")) {
            val engine = engine(); engine.stage(); files.failDuringWrite = 1
            assertFails { engine.commit(committing) }
            val name = if (part == "stage") ChapterInstallPolicy.stageName(plan.id, "bundle")
                else ChapterInstallPolicy.writeName(plan.id, "bundle", false, part)
            File(root, name).writeText("corrupt")
            val count = files.replacements
            assertFails { engine.restore() }; assertEquals(count, files.replacements)
        }
    }
    @Test fun `external content missing empty target or file identity changes never become recoverable`() {
        for (change in listOf("bytes", "empty", "missing", "inode", "mode", "owner", "group")) {
            val engine = engine(); engine.stage(); engine.commit(committing)
            val live = ChapterInstallPolicy.MANIFEST
            val identity = files.identity(live)
            when (change) {
                "bytes" -> files.corruptManifest()
                "empty" -> File(root, live).writeBytes(byteArrayOf())
                "missing" -> assertTrue(File(root, live).delete())
                "inode" -> files.accessOverrides[live] = identity.copy(inode = identity.inode + 1)
                "mode" -> files.accessOverrides[live] = identity.copy(mode = 384)
                "owner" -> files.accessOverrides[live] = identity.copy(uid = 2000)
                "group" -> files.accessOverrides[live] = identity.copy(gid = identity.gid + 1)
            }
            val count = files.replacements
            assertFails { engine.restore() }; assertEquals(count, files.replacements, change)
        }
    }
    @Test fun `completed write does not excuse later bytes that look like a partial overwrite`() {
        val engine = engine(); engine.stage(); engine.commit(committing)
        val altered = InstallSamples.bundle.copyOf()
        altered[0] = InstallSamples.replacementBundle[0]
        File(root, ChapterInstallPolicy.BUNDLE).writeBytes(altered)
        assertEquals(ChapterPairState.UNKNOWN, engine.inspect(restoring))
        val count = files.replacements
        assertFails { engine.restore() }; assertEquals(count, files.replacements)
    }
    @Test fun `failure during recovery preflight keeps evidence for the next attempt`() {
        val engine = engine(); engine.stage(); engine.commit(committing)
        files.failDuringWrite = 3
        assertFails { engine.restore() }
        files.failDuringWrite = 0; files.denyReplacementAccess = true
        assertFails { engine.restore() }
        assertTrue(files.exists(ChapterInstallPolicy.stageName(plan.id, "bundle", true)))
        assertTrue(files.exists(ChapterInstallPolicy.writeName(plan.id, "bundle", true, "before")))
        files.denyReplacementAccess = false; engine.restore()
        assertEquals(ChapterPairState.ORIGINAL, engine.inspect(restoring))
    }
    @Test fun `legacy prepared jobs cannot stage or commit under the new protocol`() {
        val engine = engine()
        val legacy = plan.copy(writeProtocol = ChapterWriteProtocol.LEGACY_RENAME)
        assertFails { engine.stage(legacy, InstallSamples.replacementBundle, InstallSamples.replacementManifest) }
        assertFails { engine.commit(legacy.copy(state = ChapterInstallState.COMMITTING)) }
        assertEquals(0, files.replacements)
        engine.restore(legacy.copy(state = ChapterInstallState.RESTORING))
        assertEquals(ChapterPairState.ORIGINAL, engine.inspect(legacy))
    }
    @Test fun `known legacy replacement can be restored in place with new recovery evidence`() {
        val engine = engine()
        val legacy = restoring.copy(writeProtocol = ChapterWriteProtocol.LEGACY_RENAME)
        files.putOriginals(InstallSamples.replacementBundle, InstallSamples.replacementManifest)
        files.failDuringWrite = 1
        assertFails { engine.restore(legacy) }
        assertEquals(ChapterPairState.INTERRUPTED, engine.inspect(legacy))
        reopened(TestChapterInstallFiles(root)).restore(legacy)
        assertEquals(ChapterPairState.ORIGINAL, engine.inspect(legacy))
    }
    @Test fun `live bytes changing between pair preflight and writable open are not overwritten`() {
        val engine = engine(); engine.stage()
        val wrapped = object : ChapterInstallFiles by files {
            private var opens = 0
            override fun openLive(name: String, gameUid: Int): ChapterLiveFile {
                opens++
                // Delegated preflight is on the adapter; the first intercepted open is the write.
                if (opens == 1) File(root, name).writeText("external bytes after preflight")
                return files.openLive(name, gameUid)
            }
        }
        assertFails { reopenedWith(wrapped).commit(committing) }
        assertEquals(0, files.replacements)
        assertEquals("external bytes after preflight", File(root, ChapterInstallPolicy.BUNDLE).readText())
    }
    private fun reopenedWith(adapter: ChapterInstallFiles) = ChapterTransactionEngine(adapter, { ChapterGameStatus(plan.game, true) }, InstallSamples::validate)
    @Test fun `write binding ignores UI state and export receipts but binds identity payload role and direction`() {
        val binding = writePlan(plan, "bundle", false)
        assertEquals(binding, writePlan(plan.copy(state = ChapterInstallState.RESTORING, exportSha256 = null, playbackConfirmed = true, unchangedVerified = true), "bundle", false))
        assertNotEquals(binding, writePlan(plan.copy(game = plan.game.copy(updatedAt = plan.game.updatedAt + 1)), "bundle", false))
        assertNotEquals(binding, writePlan(plan.copy(replacementBundle = InstallFileHash.of("different".toByteArray())), "bundle", false))
        assertNotEquals(binding, writePlan(plan, "manifest", false))
        assertNotEquals(binding, writePlan(plan, "bundle", true))
    }
    @Test fun `prefix suffix classifier matches a brute force sequential write oracle`() {
        val random = Random(20261019)
        repeat(500) {
            val before = random.nextBytes(random.nextInt(1, 25))
            val after = random.nextBytes(random.nextInt(1, 25))
            val candidates = (0..after.size).map { cut ->
                ByteArray(maxOf(before.size, cut)) { index -> if (index < cut) after[index] else before[index] }
            } + listOf(after) + List(8) { random.nextBytes(random.nextInt(0, maxOf(before.size, after.size) + 3)) }
            candidates.forEach { current ->
                val expected = current.contentEquals(after) || (0..after.size).any { cut ->
                    current.contentEquals(ByteArray(maxOf(before.size, cut)) { index -> if (index < cut) after[index] else before[index] })
                }
                assertEquals(expected, matchesInterruptedWrite(current, before, after))
            }
        }
    }
}
