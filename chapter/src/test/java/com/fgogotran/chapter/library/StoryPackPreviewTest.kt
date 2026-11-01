package com.fgogotran.chapter.library

import com.fgogotran.chapter.*
import com.fgogotran.chapter.packs.*
import kotlinx.coroutines.*
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.*

class StoryPackPreviewTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun fixture() = StoryLibraryFixture(temporary.newFolder())

    @Test fun `preview compiles exact originals reads them back and returns metadata not installation output`(): Unit = runBlocking {
        val fixture = fixture(); val storage = fixture.storage(); val pack = fixture.pack()
        val result = fixture.previewer(storage).preview(listOf(pack))
        assertEquals(1, result.packCount); assertEquals(1, result.requestedFieldCount)
        assertEquals(1, result.changedScriptCount); assertEquals(1, result.rebuiltBundleCount)
        assertEquals(fixture.index.fingerprint.sha256.hex, result.manifestSha256)
        assertEquals(listOf(fixture.index.fingerprint.path, fixture.original.fingerprint.path, fixture.original.fingerprint.path, fixture.index.fingerprint.path), storage.reads)
        assertEquals(fixture.original, storage.files.getValue(fixture.original.fingerprint.path))
    }

    @Test fun `wrong version disconnected storage missing originals and mixed languages cannot pass`(): Unit = runBlocking {
        val fixture = fixture(); val pack = fixture.pack(); val storage = fixture.storage()
        assertFailsWith<IllegalArgumentException> { fixture.previewer(storage) { StoryGameVersion("2.0.0", 1) }.preview(listOf(pack)) }
        assertTrue(storage.reads.isEmpty())
        storage.capability = ChapterStorageCapability.Unavailable(StorageUnavailableReason.PERMISSION_DENIED)
        assertFailsWith<IllegalStateException> { fixture.previewer(storage).preview(listOf(pack)) }
        assertTrue(storage.reads.isEmpty())
        val missing = MemoryOriginals(mapOf(fixture.index.fingerprint.path to fixture.index))
        assertFailsWith<IllegalStateException> { fixture.previewer(missing).preview(listOf(pack)) }
        val english = fixture.pack(chapter = "other", language = StoryLanguage.ENGLISH)
        assertFailsWith<IllegalArgumentException> { fixture.previewer(fixture.storage()).preview(listOf(pack, english)) }
    }

    @Test fun `asset index and game-version changes during preview reject stale success`(): Unit = runBlocking {
        val fixture = fixture(); val pack = fixture.pack()
        for (changingPath in listOf(fixture.original.fingerprint.path, fixture.index.fingerprint.path)) {
            val storage = fixture.storage()
            storage.transform = { path, original ->
                if (path == changingPath && storage.reads.count { it == path } == 2)
                    OriginalAsset.capture(path, original.bytes.toByteArray() + byteArrayOf(0)) else original
            }
            assertFailsWith<IllegalStateException> { fixture.previewer(storage).preview(listOf(pack)) }
        }
        var versions = 0
        assertFailsWith<IllegalStateException> {
            fixture.previewer(fixture.storage()) { if (++versions == 1) fixture.native.version else StoryGameVersion("2.139.6", 503) }.preview(listOf(pack))
        }
    }

    @Test fun `cancellation and mismatched native originals do not produce a report`(): Unit = runBlocking {
        val fixture = fixture(); val pack = fixture.pack(); val storage = fixture.storage()
        storage.beforeRead = { _, count -> if (count == 2) throw CancellationException("Stopped") }
        assertFailsWith<CancellationException> { fixture.previewer(storage).preview(listOf(pack)) }
        val changed = fixture.storage().apply { transform = { path, original ->
            if (path == fixture.original.fingerprint.path) OriginalAsset.capture(path, byteArrayOf(1)) else original
        } }
        assertFailsWith<IllegalArgumentException> { fixture.previewer(changed).preview(listOf(pack)) }
    }

    @Test fun `a package cannot read a binary outside its verified live story mapping`(): Unit = runBlocking {
        val fixture = fixture(); val storage = fixture.storage()
        val pack = fixture.native.pack(fixture.folder, listOf("ScriptActionEncrypt/01" to fixture.original), mapOf(
            "0100000011" to listOf(fixture.native.change(fixture.native.source(fixture.original), "First", StoryTextToken.Text("New")))
        ), descriptorTransform = { it.copy(logicalBundle = "ScriptActionEncrypt/02") })
        assertFailsWith<IllegalArgumentException> { fixture.previewer(storage).preview(listOf(pack)) }
        assertEquals(listOf(fixture.index.fingerprint.path), storage.reads)
    }
}
