package com.fgogotran.diagnostic

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DiagnosticFilePersistenceTest {
    private fun withDirectory(test: (File) -> Unit) {
        val directory = Files.createTempDirectory("fgogotran-diagnostics-test-").toFile()
        try {
            test(directory)
        } finally {
            // Only this test's freshly allocated temporary fixture is removed.
            directory.deleteRecursively()
        }
    }

    @Test fun `initial save creates directories and retains UTF8 TSV content`() = withDirectory { directory ->
        val target = File(directory, "diagnostics/recent_events.tsv")
        val text = "timestamp_ms\ttitle\n123\t截圖異常\n"
        assertNull(writeDiagnosticFile(target) { it.write(text) })
        assertEquals(text, target.readText(Charsets.UTF_8))
        assertTrue(!File(target.parentFile, "${target.name}.tmp").exists())
    }

    @Test fun `successful replacement updates an existing report`() = withDirectory { directory ->
        val target = File(directory, "recent_events.tsv")
        assertNull(writeDiagnosticFile(target) { it.write("old") })
        assertNull(writeDiagnosticFile(target) { it.write("new") })
        assertEquals("new", target.readText())
    }

    @Test fun `failed write keeps previous report and a later write can recover`() = withDirectory { directory ->
        val target = File(directory, "recent_events.tsv")
        target.writeText("previous")
        val failure = IOException("disk write failed")
        assertSame(failure, writeDiagnosticFile(target) {
            it.write("partial")
            throw failure
        })
        assertEquals("previous", target.readText())
        assertNull(writeDiagnosticFile(target) { it.write("recovered") })
        assertEquals("recovered", target.readText())
    }

    @Test fun `permission failure is contained without deleting previous report`() = withDirectory { directory ->
        val target = File(directory, "recent_events.tsv")
        target.writeText("previous")
        val failure = SecurityException("access denied")
        assertSame(failure, writeDiagnosticFile(target) { throw failure })
        assertEquals("previous", target.readText())
    }

    @Test fun `invalid parent directory reports failure`() = withDirectory { directory ->
        val parent = File(directory, "not-a-directory").apply { writeText("keep") }
        assertIs<IOException>(writeDiagnosticFile(File(parent, "recent_events.tsv")) { it.write("new") })
        assertEquals("keep", parent.readText())
    }

    @Test fun `failed replacement reports failure and preserves existing target`() = withDirectory { directory ->
        val target = File(directory, "recent_events.tsv").apply { mkdir() }
        val existing = File(target, "keep.txt").apply { writeText("keep") }
        assertIs<IOException>(writeDiagnosticFile(target) { it.write("new") })
        assertEquals("keep", existing.readText())
    }

    @Test fun `cancellation is not swallowed`() = withDirectory { directory ->
        val failure = CancellationException("cancelled")
        assertSame(failure, assertFailsWith<CancellationException> {
            writeDiagnosticFile(File(directory, "recent_events.tsv")) { throw failure }
        })
    }

    @Test fun `programming errors are not swallowed`() = withDirectory { directory ->
        val failure = IllegalStateException("unexpected")
        assertSame(failure, assertFailsWith<IllegalStateException> {
            writeDiagnosticFile(File(directory, "recent_events.tsv")) { throw failure }
        })
    }

    @Test fun `all background store writes share the guard and keep memory first`() {
        val store = listOf(
            File("src/main/java/com/fgogotran/diagnostic/DiagnosticEventStore.kt"),
            File("app/src/main/java/com/fgogotran/diagnostic/DiagnosticEventStore.kt")
        ).first(File::isFile).readText().replace("\r\n", "\n")
        for ((memory, disk) in listOf(
            "_events.value = loaded" to "writeEventsLocked(loaded)",
            "_events.value = nextEvents" to "writeEventsLocked(nextEvents)",
            "_events.value = emptyList()" to "writeEventsLocked(emptyList())"
        )) {
            assertTrue(store.indexOf(memory) in 0 until store.indexOf(disk))
        }
        val write = store.substringAfter("private fun writeEventsLocked(").substringBefore("private fun pruneEvents(")
        assertTrue(write.contains("val failure = writeDiagnosticFile(eventsFile)"))
        assertTrue(write.contains("FgoLogger.warn("))
        assertTrue(!write.contains("record("))
        assertTrue(!write.contains(".delete()"))
        assertTrue(write.contains("writer.appendLine(HEADER)"))
        assertTrue(write.contains("writer.appendLine(event.toTsvLine())"))
    }
}
