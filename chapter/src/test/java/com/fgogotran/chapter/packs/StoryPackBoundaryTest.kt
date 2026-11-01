package com.fgogotran.chapter.packs

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StoryPackBoundaryTest {
    @Test fun `package validation has no network device compiler or installer dependency`() {
        val module = File(requireNotNull(System.getProperty("chapter.projectDir")))
        val directory = File(module, "src/main/java/com/fgogotran/chapter/packs")
        val sources = directory.listFiles().orEmpty().filter { it.extension == "kt" }
        assertTrue(sources.isNotEmpty())
        sources.forEach { source ->
            val text = source.readText()
            listOf("import android.", "import java.net.", "okhttp", "ProcessBuilder", "Runtime.getRuntime",
                "com.fgogotran.chapter.install", "com.fgogotran.chapter.storage", "com.fgogotran.chapter.codec").forEach { reference ->
                assertFalse(text.contains(reference), "${source.name} must not acquire $reference")
            }
        }
        assertFalse(File(directory, "StoryPackPublisherMain.kt").exists())
        assertFalse(File(directory, "StoryPackArchive.kt").exists())
        assertTrue(File(module, "src/test/java/com/fgogotran/chapter/packs/StoryPackArchive.kt").isFile)
        assertTrue(File(module, "src/test/java/com/fgogotran/chapter/packs/tools/LocalStoryPackPublisher.kt").isFile)
        val wrapper = File(module, "tools/Publish-StoryLanguagePack.ps1").readText()
        assertTrue(wrapper.contains(":chapter:publishLocalStoryPack"))
        assertTrue(wrapper.contains("--offline"))
        assertFalse(Regex("(?im)\\b(adb|curl|Invoke-WebRequest|Invoke-RestMethod)\\b").containsMatchIn(wrapper))
    }
}
