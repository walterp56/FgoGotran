package com.fgogotran.ui

import com.fgogotran.localization.EnglishUiText
import java.io.File
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EnglishGuideResourcesTest {
    private val english by lazy { resources("values-en") }

    @Test
    fun `English guide shows the exact Japanese path above its English explanation`() {
        val japanesePath = "マイルーム → ゲームオプション → テキスト表示速度"
        val englishPath = "My Room → Game Options → Text Display Speed"
        val path = english.getValue("guide_auto_1")

        assertEquals(listOf(japanesePath, englishPath), path.split("\\n"))
        assertEquals(1, Regex("""\\n""").findAll(path).count())
        for (directory in listOf("values", "values-b+zh+Hant")) {
            assertEquals(japanesePath, resources(directory).getValue("guide_auto_1").substringBefore("\\n"))
        }
    }

    @Test
    fun `English guide shows exact Japanese setting labels before English without extra speed suffixes`() {
        val expected = mapOf(
            "guide_auto_18" to "句読点待ち時間 / Punctuation wait",
            "guide_auto_21" to "ページ送り / Page turn",
            "guide_auto_25" to "文字送り / Text speed"
        )
        expected.forEach { (key, text) ->
            assertEquals(text, english.getValue(key), key)
            assertFalse(english.getValue(key).contains(" / 表示速度"), key)
        }
    }

    @Test
    fun `recovery guidance is localized and distinguishes emulator restart from PC restart`() {
        for ((directory, emulator, pc) in listOf(
            Triple("values", "模拟器", "不需要重启电脑"),
            Triple("values-b+zh+Hant", "模擬器", "不需要重新啟動電腦"),
            Triple("values-en", "emulator", "do not need to restart your computer")
        )) {
            val strings = resources(directory)
            val guide = strings.getValue("guide_capture_recovery")
            val connection = strings.getValue("accessibility_not_connected_message")
            for (text in listOf(guide, connection)) {
                for (name in listOf("MuMu", "Android", "Windows")) {
                    assertFalse(text.contains(name, ignoreCase = true), "$directory contains $name")
                }
                assertTrue(text.contains("2–3"), directory)
                assertTrue(text.contains(emulator), directory)
                assertTrue(text.contains(pc), directory)
            }
            assertEquals(2, Regex("""\\n""").findAll(guide).count(), directory)
        }
        val guide = source("ui/screen/GuideScreen.kt")
            .substringAfter("private fun GuideScopeCard()")
            .substringBefore("private fun GuideWebsiteCard(")
        assertTrue(guide.contains("stringResource(R.string.guide_capture_recovery)"))
        assertFalse(guide.contains("AlertDialog"))
    }

    @Test
    fun `guide copy stays free of third party software names`() {
        for (directory in listOf("values", "values-b+zh+Hant", "values-en")) {
            resources(directory).filterKeys { it.startsWith("guide_") }.forEach { (key, text) ->
                for (name in listOf("MuMu", "Android", "Windows")) {
                    assertFalse(text.contains(name, ignoreCase = true), "$directory $key contains $name")
                }
            }
        }
    }

    @Test
    fun `disclosures describe default foreground scope without promising FGO only capture`() {
        for ((directory, foreground, stop) in listOf(
            Triple("values", "其他前台应用", "停止翻译"),
            Triple("values-b+zh+Hant", "其他前景應用程式", "停止翻譯"),
            Triple("values-en", "other foreground apps", "Stop translation")
        )) {
            val strings = resources(directory)
            for (key in listOf("accessibility_summary", "accessibility_description",
                "accessibility_disclosure_message", "overlay_permission_message", "guide_auto_7")) {
                assertTrue(strings.getValue(key).contains(foreground, ignoreCase = true), "$directory $key")
            }
            val disclosure = strings.getValue("accessibility_disclosure_message")
            assertTrue(disclosure.contains(stop), directory)
            assertFalse(disclosure.contains("不会读取联系人"))
            assertFalse(disclosure.contains("不會讀取聯絡人"))
            assertFalse(disclosure.contains("does not read contacts"))
        }
        val disclosure = english.getValue("accessibility_disclosure_message")
        assertTrue(disclosure.contains("only after you start translation"))
        assertTrue(disclosure.contains("service you selected or configured"))
        assertTrue(disclosure.contains("current foreground app"))
        assertTrue(disclosure.contains("translation screenshots and overlay tap forwarding are inactive"))
        assertFalse(english.getValue("guide_auto_7").contains("only work on the running FGO screen"))
    }

    @Test
    fun `revised diagnostic recovery and qualified dark frame text have English mappings`() {
        val sources = source("accessibility/FgoAccessibilityService.kt") + source("ui/screen/HomeScreen.kt")
        val revised = Regex("(?:title|message|detail) = \"([^\"]+)\"").findAll(sources)
            .map { it.groupValues[1] }
            .filter { it.contains("不是只重开游戏") || it.contains("其他可用渲染器") ||
                it.contains("截图疑似全黑") || it.contains("截图采样点") || it.contains("手动模式请再次点击翻译") }
            .toSet()
        assertEquals(6, revised.size)
        revised.forEach { text ->
            val translated = assertNotNull(EnglishUiText.translate(text), text)
            assertFalse(Regex("[\\u4e00-\\u9fff]").containsMatchIn(translated), text)
        }
        // Persisted older diagnostic records must remain readable too.
        assertNotNull(EnglishUiText.translate("截图内容为空（全黑）"))
    }

    private fun source(path: String): String = listOf(
        File("src/main/java/com/fgogotran/$path"),
        File("app/src/main/java/com/fgogotran/$path")
    ).first(File::isFile).readText().replace("\r\n", "\n")

    private fun resources(directory: String): Map<String, String> {
        val factory = DocumentBuilderFactory.newInstance().apply {
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", "")
            setAttribute("http://javax.xml.XMLConstants/property/accessExternalSchema", "")
        }
        val file = listOf(
            File("src/main/res/$directory/strings.xml"),
            File("app/src/main/res/$directory/strings.xml")
        ).first(File::isFile)
        val document = factory.newDocumentBuilder().parse(file)
        assertEquals("resources", document.documentElement.tagName)
        val strings = document.documentElement.getElementsByTagName("string")
        val result = linkedMapOf<String, String>()
        for (index in 0 until strings.length) {
            val element = strings.item(index)
            val name = element.attributes.getNamedItem("name").nodeValue
            assertFalse(result.containsKey(name), "$directory has duplicate resource $name")
            result[name] = element.textContent
        }
        return result
    }
}
