package com.fgogotran.chapter.library

import com.fgogotran.chapter.packs.*
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.concurrent.CancellationException
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.*

class StoryPackLibraryTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun fixture() = StoryLibraryFixture(temporary.newFolder())

    @Test fun `imports are privately copied retained across instances revalidated and idempotent`() {
        val fixture = fixture(); val pack = fixture.pack(); val library = fixture.library()
        var closed = false
        val entry = library.import({ object : ByteArrayInputStream(fixture.archive(pack).readBytes()) {
            override fun close() { closed = true; super.close() }
        } })
        assertTrue(closed)
        assertEquals(pack.archiveSha256, entry.sha256)
        assertEquals(entry, fixture.import(library, pack))
        assertEquals(listOf(entry), fixture.library().list())
        assertEquals(pack.archiveSha256, fixture.library().load(listOf(entry)).single().archiveSha256)
        assertTrue(File(fixture.folder, "library/incoming").listFiles().orEmpty().isEmpty())
        assertEquals(1, File(fixture.folder, "library/archives").listFiles()!!.size)
    }

    @Test fun `revisions and language catalogue remain explicit and conflicting identities cannot overwrite`() {
        val fixture = fixture(); val library = fixture.library()
        val one = fixture.import(library, fixture.pack())
        val two = fixture.import(library, fixture.pack(revision = 2, replacement = "New revision"))
        val english = fixture.import(library, fixture.pack(language = StoryLanguage.ENGLISH))
        assertEquals(setOf(two, english), StoryCatalogue(library.list()).latest().toSet())
        assertTrue(one in library.list()) // Immutable old revision is retained, not replaced.
        val conflict = fixture.pack(replacement = "Conflicting same revision")
        assertFailsWith<IllegalArgumentException> { fixture.import(library, conflict) }
        assertEquals(3, library.list().size)
        assertEquals(one.sha256, library.load(listOf(one)).single().archiveSha256)
    }

    @Test fun `invalid oversized interrupted and cancelled streams close and never become selectable`() {
        val fixture = fixture(); val library = fixture.library()
        assertFails { library.import({ ByteArrayInputStream("not an archive".toByteArray()) }) }
        var closed = false
        val oversized = object : InputStream() {
            var remaining = StoryPackPolicy.MAX_ARCHIVE + 1
            override fun read(): Int = if (remaining-- > 0) 0 else -1
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (remaining == 0) return -1
                val count = minOf(length, remaining); remaining -= count
                buffer.fill(0, offset, offset + count); return count
            }
            override fun close() { closed = true }
        }
        assertFailsWith<IllegalArgumentException> { library.import({ oversized }) }
        assertTrue(closed)
        assertFailsWith<IOException> { library.import({ throw IOException("Synthetic provider failure") }) }
        val pack = fixture.pack()
        var checks = 0
        assertFailsWith<CancellationException> { library.import({ fixture.archive(pack).inputStream() }) { if (++checks == 3) throw CancellationException("Cancelled") } }
        assertTrue(library.list().isEmpty())
        assertTrue(File(fixture.folder, "library/incoming").listFiles().orEmpty().isEmpty())
    }

    @Test fun `corrupt stored history and stale selections are rejected rather than rehashed or overwritten`() {
        val fixture = fixture(); val library = fixture.library(); val entry = fixture.import(library)
        val stored = File(fixture.folder, "library/archives/${entry.sha256}.fgostory.zip")
        stored.writeBytes(byteArrayOf(1, 2, 3))
        assertFails { library.list() }
        assertFails { library.load(listOf(entry)) }
        assertFails { fixture.import(library, fixture.pack(revision = 2)) }
        assertContentEquals(byteArrayOf(1, 2, 3), stored.readBytes())
    }

    @Test fun `library rejects cross-instance lock contention without altering existing archives`() {
        val fixture = fixture(); val library = fixture.library(); val entry = fixture.import(library)
        FileChannel.open(File(fixture.folder, "library/.library.lock").toPath(), StandardOpenOption.WRITE).use { channel ->
            channel.lock().use { assertFails { fixture.library().list() } }
        }
        assertEquals(listOf(entry), library.list())
    }
}
