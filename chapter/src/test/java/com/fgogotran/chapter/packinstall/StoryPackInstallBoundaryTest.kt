package com.fgogotran.chapter.packinstall

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.*

class StoryPackInstallBoundaryTest {
    private val root get() = File(requireNotNull(System.getProperty("chapter.projectDir")))
    @Test fun `native scope never gains arbitrary filename command automatic cleanup or translation ports`() {
        val session = File(root, "src/test/java/com/fgogotran/chapter/packinstall/StoryPackInstallSession.kt").readText()
        assertTrue(session.contains("gate.verified()")); assertTrue(session.contains("ChapterInstallPolicy.GROUP"))
        assertFalse(File(root, "src/main/java/com/fgogotran/chapter/packinstall/StoryPackInstallSession.kt").exists())
        val recovery = File(root, "src/main/java/com/fgogotran/chapter/packinstall/StoryPackRecoverySession.kt").readText()
        assertTrue(recovery.contains("withContext(NonCancellable)"))
        listOf("compiler", "library", "suspend fun prepare(", "suspend fun install(").forEach { assertFalse(recovery.contains(it)) }
        val sources = File(root, "src/main/java/com/fgogotran/chapter/packinstall").listFiles().orEmpty().map { it.readText() }
        sources.forEach { text ->
            listOf("java.net.", "okhttp", "ProcessBuilder", "Runtime.getRuntime", "force-stop", "deleteRecursively", "Shizuku.newProcess").forEach { assertFalse(text.contains(it)) }
        }
        val service = File(root, "src/main/java/com/fgogotran/chapter/storage/ChapterInstallService.kt").readText()
        assertEquals(5, Regex("ChapterInstallPolicy::requireOriginals").findAll(service).count())
        assertFalse(service.contains("Os.rename")); assertFalse(service.contains("Os.chown"))
        val aidl = File(root, "src/main/aidl/com/fgogotran/chapter/storage/IChapterInstallService.aidl").readText()
        assertFalse(aidl.contains("String path")); assertFalse(aidl.contains("String command"))
    }
    @Test fun `new installation resources match all three languages`() {
        fun strings(name: String): Map<String, String> {
            val factory = DocumentBuilderFactory.newInstance().apply { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            val nodes = factory.newDocumentBuilder().parse(File(root, "src/main/res/$name/strings.xml")).getElementsByTagName("string")
            return (0 until nodes.length).map { nodes.item(it) as org.w3c.dom.Element }.filter {
                it.getAttribute("name").startsWith("chapter_native_") || it.getAttribute("name").startsWith("chapter_recovery_")
            }
                .associate { it.getAttribute("name") to it.textContent }
        }
        val english = strings("values-en"); assertTrue(english.size >= 18)
        for (language in listOf("values", "values-b+zh+Hant")) {
            val translated = strings(language); assertEquals(english.keys, translated.keys)
            english.forEach { (key, text) ->
                val format = Regex("%[1-9]\\$[ds]")
                assertEquals(format.findAll(text).map { it.value }.toList(), format.findAll(translated.getValue(key)).map { it.value }.toList())
            }
        }
    }
}
