package com.fgogotran.chapter

import java.io.File
import java.nio.file.Files
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.*

class LocalChapterPathsTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun module(): File = File(temporary.newFolder(), "chapter").apply { mkdirs() }.canonicalFile

    @Test fun `accepts both original capture roots but not atlas builds or a sibling prefix`() {
        val module = module()
        val legacy = File(module, "local-fixtures/capture").apply { mkdirs() }
        val current = File(module.parentFile, "story_assets/jp-originals/capture").apply { mkdirs() }
        assertEquals(legacy.canonicalFile, LocalChapterPaths.capture(module, legacy.path))
        assertEquals(current.canonicalFile, LocalChapterPaths.capture(module, current.path))
        listOf(
            File(module, "local-fixtures"), File(module.parentFile, "story_assets/jp-originals"),
            File(module.parentFile, "story_assets/atlas/snapshot"), File(module.parentFile, "story_assets/builds/job"),
            File(module, "local-fixtures-other/capture"), File(module.parentFile, "outside")
        ).forEach { assertFailsWith<IllegalArgumentException> { LocalChapterPaths.capture(module, it.path) } }
    }

    @Test fun `new proofs use builds legacy proofs remain local and neither overwrites previous evidence`() {
        val module = module()
        val current = File(module.parentFile, "story_assets/jp-originals/capture").apply { mkdirs() }.canonicalFile
        val legacy = File(module, "local-fixtures/capture").apply { mkdirs() }.canonicalFile
        val first = LocalChapterPaths.proofOutput(module, current)
        File(first, "evidence.txt").writeText("preserve")
        val second = LocalChapterPaths.proofOutput(module, current)
        assertNotEquals(first, second)
        assertEquals("preserve", File(first, "evidence.txt").readText())
        assertTrue(first.toPath().startsWith(File(module.parentFile, "story_assets/builds").toPath()))
        assertTrue(LocalChapterPaths.proofOutput(module, legacy).toPath().startsWith(File(module, "local-output").toPath()))
    }

    @Test fun `linked capture directories are refused`() {
        val module = module()
        val outside = File(module.parentFile, "outside").apply { mkdirs() }
        val root = File(module.parentFile, "story_assets/jp-originals").apply { mkdirs() }
        val linked = File(root, "linked")
        val created = runCatching { Files.createSymbolicLink(linked.toPath(), outside.toPath()) }.isSuccess
        assumeTrue("Host must allow creating test symlinks", created)
        assertFailsWith<IllegalArgumentException> { LocalChapterPaths.capture(module, linked.path) }
    }
}
