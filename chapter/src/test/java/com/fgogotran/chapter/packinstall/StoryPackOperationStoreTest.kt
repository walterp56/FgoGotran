package com.fgogotran.chapter.packinstall

import com.fgogotran.chapter.install.*
import com.fgogotran.chapter.library.*
import com.fgogotran.chapter.packs.StoryLanguage
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.*

class StoryPackOperationStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun fixture() = PackInstallFixture(temporary.newFolder())

    @Test fun `desired selection is durable monotonic compare-and-set intent not installed state`() {
        val f = fixture(); val before = f.store.loadSelection()
        val next = f.store.saveSelection(before, StoryLanguage.ENGLISH, setOf("a".repeat(64)))
        assertEquals(1, next.revision)
        assertEquals(next, StoryPackOperationStore(f.root, f.durable).loadSelection())
        assertNull(f.store.load()); assertTrue(f.files.writes.isEmpty())
        assertFails { f.store.saveSelection(before, StoryLanguage.TRADITIONAL_CHINESE, emptySet()) }
        File(f.root, "desired.json").writeText("{\"schema\":1,\"schema\":1}")
        assertFails { f.store.loadSelection() }
    }

    @Test fun `operation schema freezes identities selected archives and composition across state changes`(): Unit = runBlocking {
        val f = fixture(); f.desired(f.import()); f.session().prepare(f.reader)
        val value = f.store.load()!!
        val encoded = StoryPackOperationJson.encode(value)
        assertContentEquals(encoded, StoryPackOperationJson.encode(StoryPackOperationJson.decode(encoded)))
        assertFails { f.store.replace(value, value.transport.copy(game = f.game.copy(updatedAt = 3))) }
        assertFails { StoryPackOperationJson.decode(encoded.toString(Charsets.UTF_8).replace(value.references.single().sha256, "f".repeat(64)).toByteArray()) }
        val legacy = ChapterBackupStore(temporary.newFolder(), TestChapterDurableFiles())
        val baseline = legacy.create(f.game, f.original.bytes.toByteArray(), f.index.bytes.toByteArray())
        assertFails { legacy.replace(baseline, value.transport.copy(id = baseline.id)) }
        assertFails { legacy.savePrepared(baseline, ChapterRebuildMode.LANGUAGE_PACK, f.original.bytes.toByteArray(), f.index.bytes.toByteArray()) }
    }

    @Test fun `corrupt originals or compiled bytes block installation and are never regenerated`(): Unit = runBlocking {
        for (file in listOf("original-bundle", "prepared-manifest")) {
            val f = fixture(); f.desired(f.import()); val session = f.session(); session.prepare(f.reader); f.exported(session)
            File(f.root, "${f.store.load()!!.transport.id}/$file").writeBytes(byteArrayOf(1))
            session.install(true)
            assertEquals(0, f.stages); assertNotNull(session.state.value.error)
            assertContentEquals(byteArrayOf(1), File(f.root, "${f.store.load()!!.transport.id}/$file").readBytes())
        }
    }

    @Test fun `bad recovery export revokes an old receipt before writing to the same destination`(): Unit = runBlocking {
        val f = fixture(); f.desired(f.import()); val session = f.session(); session.prepare(f.reader); f.exported(session)
        session.export({ ByteArrayOutputStream() }, { byteArrayOf(1).inputStream() })
        assertNull(f.store.load()!!.transport.exportSha256)
        session.install(true); assertEquals(0, f.stages)
    }

    @Test fun `corrupt and orphan journals block new operations without cleaning recovery material`(): Unit = runBlocking {
        val f = fixture(); f.desired(f.import()); val session = f.session(); session.prepare(f.reader)
        f.journal().writeText("unknown future journal")
        session.reload(); assertTrue(session.state.value.journalBlocked); assertTrue(session.state.value.blocksAssetTests)
        assertFails { f.store.load() }
        val orphan = fixture(); val directory = File(orphan.root, "a".repeat(32)); check(directory.mkdirs())
        assertFails { orphan.store.load() }; assertTrue(directory.isDirectory)
    }
}
