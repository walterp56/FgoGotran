package com.fgogotran.chapter.storage

import com.fgogotran.chapter.codec.FgoJpFormatProfile
import java.nio.file.Files
import kotlin.test.*

class ChapterAssetPolicyTest {
    @Test fun `only manifest and canonical hashed bundle filenames are accepted`() {
        assertEquals(FgoJpFormatProfile.MANIFEST_NAME, ChapterAssetPolicy.requireFilename(FgoJpFormatProfile.MANIFEST_NAME))
        assertEquals("a".repeat(40) + ".bin", ChapterAssetPolicy.requireFilename("a".repeat(40) + ".bin"))
        listOf("", "../" + FgoJpFormatProfile.MANIFEST_NAME, "/sdcard/test", "a/b.bin", "a\\b.bin", "x".repeat(40) + ".bin", "a".repeat(39) + ".bin", "a".repeat(40) + ".BIN", "test.sh", "start.sh").forEach {
            assertFailsWith<IllegalArgumentException>(it) { ChapterAssetPolicy.requireFilename(it) }
        }
    }

    @Test fun `accepted filenames resolve only as direct children`() {
        val root = Files.createTempDirectory("chapter-path-test").toFile()
        try {
            val target = ChapterAssetPolicy.resolve(root, FgoJpFormatProfile.MANIFEST_NAME)
            assertEquals(root.canonicalFile, target.canonicalFile.parentFile)
            assertFailsWith<IllegalArgumentException> { ChapterAssetPolicy.resolve(root, "../escape") }
        } finally { root.delete() }
    }
}
