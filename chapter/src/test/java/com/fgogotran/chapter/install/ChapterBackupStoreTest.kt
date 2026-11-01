package com.fgogotran.chapter.install

import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream
import kotlin.test.*

class ChapterBackupStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun store(root: File, durable: TestChapterDurableFiles = TestChapterDurableFiles()) = ChapterBackupStore(root, durable)
    @Test fun `new version can capture a separate baseline only after exported unused backup`() {
        val root = temporary.newFolder(); val store = store(root)
        var old = store.create(InstallSamples.game, InstallSamples.bundle, InstallSamples.manifest)
        val updatedGame = ChapterGameIdentity("2.139.5", 502, 223456789, 10075)
        assertFailsWith<IllegalStateException> { store.create(updatedGame, InstallSamples.bundle, InstallSamples.manifest) }
        old = store.replace(old, old.copy(exportSha256 = "a".repeat(64)))
        val updated = store.create(updatedGame, InstallSamples.bundle, InstallSamples.replacementManifest)
        assertNotEquals(old.id, updated.id)
        assertEquals(updated, store.load())
        assertTrue(File(root, "${old.id}/record.json").isFile)
        assertContentEquals(InstallSamples.manifest, store.original(old, false))
        assertContentEquals(InstallSamples.replacementManifest, store.original(updated, false))
    }
    @Test fun `game update cannot replace prepared staged installed or unresolved operation`() {
        for (state in listOf(ChapterInstallState.PREPARED, ChapterInstallState.STAGED, ChapterInstallState.COMMITTING,
            ChapterInstallState.INSTALLED, ChapterInstallState.RESTORING, ChapterInstallState.RECOVERY_REQUIRED)) {
            val store = store(temporary.newFolder())
            val old = store.create(InstallSamples.game, InstallSamples.bundle, InstallSamples.manifest)
            val prepared = store.savePrepared(old, ChapterRebuildMode.UNCHANGED, InstallSamples.replacementBundle, InstallSamples.replacementManifest)
            val active = store.replace(prepared, prepared.copy(state = state, exportSha256 = "a".repeat(64)))
            assertFailsWith<IllegalStateException> {
                store.create(ChapterGameIdentity("2.139.5", 502, 223456789, 10075), InstallSamples.bundle, InstallSamples.manifest)
            }
            assertEquals(active, store.load())
        }
    }
    @Test fun `backup survives new store instance with exact original bytes`() {
        val root = temporary.newFolder(); val store = store(root)
        val record = store.create(InstallSamples.game, InstallSamples.bundle, InstallSamples.manifest)
        val reopened = store(root)
        assertEquals(record, reopened.load())
        assertContentEquals(InstallSamples.bundle, reopened.original(record, true))
        assertContentEquals(InstallSamples.manifest, reopened.original(record, false))
    }
    @Test fun `unfinished backup is not overwritten by another baseline`() {
        val store = store(temporary.newFolder())
        val record = store.create(InstallSamples.game, InstallSamples.bundle, InstallSamples.manifest)
        assertFailsWith<IllegalStateException> { store.create(InstallSamples.game, InstallSamples.replacementBundle, InstallSamples.replacementManifest) }
        assertEquals(record, store.load()); assertContentEquals(InstallSamples.bundle, store.original(record, true))
    }
    @Test fun `legacy unused exported baseline can start a new job without changing old protocol or originals`() {
        val root = temporary.newFolder(); val store = store(root)
        val current = store.create(InstallSamples.game, InstallSamples.bundle, InstallSamples.manifest)
        val legacyJson = ChapterInstallRecordCodec.encode(current.copy(exportSha256 = "e".repeat(64)))
            .replace("\"schema\":2", "\"schema\":1").replace(",\"writeProtocol\":\"IN_PLACE_V1\"", "")
        File(root, "${current.id}/record.json").writeText(legacyJson)
        val legacy = requireNotNull(store.load())
        assertEquals(ChapterWriteProtocol.LEGACY_RENAME, legacy.writeProtocol)
        assertFails { store.replace(legacy, legacy.copy(writeProtocol = ChapterWriteProtocol.IN_PLACE_V1)) }
        assertFails { store.savePrepared(legacy, ChapterRebuildMode.UNCHANGED, InstallSamples.replacementBundle, InstallSamples.replacementManifest) }
        val fresh = store.create(InstallSamples.game, InstallSamples.bundle, InstallSamples.manifest)
        assertEquals(ChapterWriteProtocol.IN_PLACE_V1, fresh.writeProtocol)
        assertNull(fresh.exportSha256); assertNotEquals(legacy.id, fresh.id)
        assertEquals(legacyJson, File(root, "${current.id}/record.json").readText())
        assertContentEquals(InstallSamples.manifest, store.original(legacy, false))
    }
    @Test fun `prepared data cannot replace original backups`() {
        val store = store(temporary.newFolder()); val record = store.create(InstallSamples.game, InstallSamples.bundle, InstallSamples.manifest)
        val prepared = store.savePrepared(record, ChapterRebuildMode.UNCHANGED, InstallSamples.replacementBundle, InstallSamples.replacementManifest)
        assertContentEquals(InstallSamples.bundle, store.original(prepared, true))
        assertContentEquals(InstallSamples.replacementBundle, store.prepared(prepared, true))
    }
    @Test fun `marker preparation requires verified unchanged playback and restoration`() {
        val store = store(temporary.newFolder()); val record = store.create(InstallSamples.game, InstallSamples.bundle, InstallSamples.manifest)
        assertFailsWith<IllegalArgumentException> { store.savePrepared(record, ChapterRebuildMode.MARKER, InstallSamples.replacementBundle, InstallSamples.replacementManifest) }
        assertEquals(record, store.load())
    }
    @Test fun `corrupt backup is refused for transfer and export`() {
        val root = temporary.newFolder(); val store = store(root)
        val record = store.create(InstallSamples.game, InstallSamples.bundle, InstallSamples.manifest)
        File(root, "${record.id}/original-bundle").writeText("broken")
        assertFailsWith<IllegalArgumentException> { store.transferFile(record, true, false) }
        assertFailsWith<IllegalArgumentException> { store.export(record, ByteArrayOutputStream()) }
    }
    @Test fun `failed prepared journal publication never marks installation committed`() {
        val root = temporary.newFolder(); val durable = TestChapterDurableFiles(); val store = store(root, durable)
        val record = store.create(InstallSamples.game, InstallSamples.bundle, InstallSamples.manifest)
        durable.failRecordWrite = true
        assertFails { store.savePrepared(record, ChapterRebuildMode.UNCHANGED, InstallSamples.replacementBundle, InstallSamples.replacementManifest) }
        assertEquals(record, store.load()); assertContentEquals(InstallSamples.bundle, store.original(record, true))
    }
    @Test fun `recovery export contains only the exact pair and versioned record`() {
        val store = store(temporary.newFolder()); val record = store.create(InstallSamples.game, InstallSamples.bundle, InstallSamples.manifest)
        val output = ByteArrayOutputStream(); store.export(record, output)
        val contents = linkedMapOf<String, ByteArray>()
        ZipInputStream(output.toByteArray().inputStream()).use { input ->
            while (true) { val entry = input.nextEntry ?: break; contents[entry.name] = input.readBytes(); input.closeEntry() }
        }
        assertEquals(setOf(ChapterInstallPolicy.BUNDLE, ChapterInstallPolicy.MANIFEST, "record.json"), contents.keys)
        assertContentEquals(InstallSamples.bundle, contents.getValue(ChapterInstallPolicy.BUNDLE))
        assertContentEquals(InstallSamples.manifest, contents.getValue(ChapterInstallPolicy.MANIFEST))
        assertEquals(record, ChapterInstallRecordCodec.decode(contents.getValue("record.json").toString(Charsets.UTF_8)))
    }
    @Test fun `corrupt active pointer fails closed instead of appearing as no backup`() {
        val root = temporary.newFolder(); File(root, "active").writeText("../other")
        assertFailsWith<IllegalArgumentException> { store(root).load() }
    }
    @Test fun `journal compare and replace rejects stale operations`() {
        val store = store(temporary.newFolder()); val record = store.create(InstallSamples.game, InstallSamples.bundle, InstallSamples.manifest)
        store.replace(record, record.copy(exportSha256 = "a".repeat(64)))
        assertFailsWith<IllegalStateException> { store.replace(record, record.copy(exportSha256 = "b".repeat(64))) }
    }
}
