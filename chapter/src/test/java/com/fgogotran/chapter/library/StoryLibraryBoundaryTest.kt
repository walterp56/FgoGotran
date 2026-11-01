package com.fgogotran.chapter.library

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.*

class StoryLibraryBoundaryTest {
    private val module get() = File(requireNotNull(System.getProperty("chapter.projectDir")))

    @Test fun `preview and local library cannot acquire installer API translation or network authority`() {
        val sources = listOf("StoryPackController.kt", "StoryPackLibrary.kt", "StoryPackPreview.kt").map { name ->
            assertFalse(File(module, "src/main/java/com/fgogotran/chapter/library/$name").exists())
            File(module, "src/test/java/com/fgogotran/chapter/library/$name")
        }
        assertTrue(sources.isNotEmpty())
        sources.forEach { source ->
            val text = source.readText()
            listOf("com.fgogotran.chapter.install", "com.fgogotran.chapter.storage", "import android.", "java.net.",
                "okhttp", "ProcessBuilder", "Runtime.getRuntime").forEach { assertFalse(text.contains(it), "${source.name}: $it") }
        }
        val preview = File(module, "src/test/java/com/fgogotran/chapter/library/StoryPackPreview.kt").readText()
        listOf("File(", "OutputStream", "CompiledStoryAssets", "export", "writeBytes").forEach { assertFalse(preview.contains(it)) }
        assertFalse(File(module, "src/main/java/com/fgogotran/chapter/ui/StoryPackCard.kt").exists())
        assertFalse(File(module, "src/main/java/com/fgogotran/chapter/ui/StoryPackInstallCard.kt").exists())
        val screen = File(module, "src/main/java/com/fgogotran/chapter/ui/ChapterScreen.kt").readText()
        listOf("OpenDocument", "StoryPackController", "StoryPackLibrary", "StoryPackPreviewer").forEach { assertFalse(screen.contains(it)) }
    }

    @Test fun `language pack resources exist in all three app languages with the same format placeholders`() {
        fun resources(directory: String): Map<String, String> {
            val factory = DocumentBuilderFactory.newInstance().apply { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            val nodes = factory.newDocumentBuilder().parse(File(module, "src/main/res/$directory/strings.xml")).getElementsByTagName("string")
            return (0 until nodes.length).map { nodes.item(it) as org.w3c.dom.Element }
                .filter { it.getAttribute("name").startsWith("chapter_") }.associate { it.getAttribute("name") to it.textContent }
        }
        val english = resources("values-en")
        assertTrue(english.size >= 60)
        for (directory in listOf("values", "values-b+zh+Hant")) {
            val translated = resources(directory)
            assertEquals(english.keys, translated.keys)
            english.forEach { (key, value) ->
                val format = Regex("%[1-9]\\$[ds]")
                assertEquals(format.findAll(value).map { it.value }.toList(), format.findAll(translated.getValue(key)).map { it.value }.toList())
            }
        }
    }
}
