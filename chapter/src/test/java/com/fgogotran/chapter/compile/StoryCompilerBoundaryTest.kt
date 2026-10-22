package com.fgogotran.chapter.compile

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StoryCompilerBoundaryTest {
    @Test fun `compiler has no device network filesystem or installer port`() {
        val module = File(requireNotNull(System.getProperty("chapter.projectDir")))
        val production = File(module, "src/main/java/com/fgogotran/chapter/compile")
        assertTrue(production.listFiles().orEmpty().none { it.extension == "kt" }, "Source compilation must not ship in the APK")
        val files = listOf("StoryPackCompiler.kt", "StoryTextCompiler.kt").map { File(module, "src/test/java/com/fgogotran/chapter/compile/$it") }
        assertTrue(files.isNotEmpty())
        files.forEach { source ->
            val text = source.readText()
            listOf("import android.", "import java.io.", "import java.nio.file.", "import java.net.", "okhttp",
                "ProcessBuilder", "Runtime.getRuntime", "com.fgogotran.chapter.install", "com.fgogotran.chapter.storage",
                "com.fgogotran.ocr", "com.fgogotran.translation").forEach { reference ->
                assertFalse(text.contains(reference), "${source.name} must not acquire $reference")
            }
        }
    }
}
