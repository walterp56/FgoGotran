package com.fgogotran.ui

import java.io.File
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Resource and display-path checks; these do not require Android's ICU at test runtime. */
class TraditionalChineseResourcesTest {
    private data class StringResource(val text: String, val translatable: Boolean)

    private val defaults by lazy { resources("values") }
    private val traditional by lazy { resources("values-b+zh+Hant") }

    @Test
    fun `traditional resources cover all translatable keys without duplicates`() {
        // resources() rejects duplicate keys before creating its map.
        assertEquals(defaults.filterValues { it.translatable }.keys, traditional.keys)
        assertFalse(traditional.containsKey("settings_ruby_example_annotation"))
        assertFalse(traditional.containsKey("settings_ruby_example_base"))
        assertEquals("シリウスライト", defaults.getValue("settings_ruby_example_annotation").text)
        assertEquals("大令呪", defaults.getValue("settings_ruby_example_base").text)
    }

    @Test
    fun `traditional translations retain indexed format placeholders`() {
        val formatToken = Regex("""%(?:\d+\$)?[-#+ 0,(<]*\d*(?:\.\d+)?(?:[tT])?[a-zA-Z%]""")
        traditional.forEach { (key, resource) ->
            val expected = formatToken.findAll(defaults.getValue(key).text).map { it.value }.sorted().toList()
            val actual = formatToken.findAll(resource.text).map { it.value }.sorted().toList()
            assertEquals(expected, actual, key)
        }
    }

    @Test
    fun `traditional translations retain explicit line breaks`() {
        val lineBreak = Regex("""\\n""")
        traditional.forEach { (key, resource) ->
            assertEquals(
                lineBreak.findAll(defaults.getValue(key).text).count(),
                lineBreak.findAll(resource.text).count(),
                key
            )
        }
    }

    @Test
    fun `reviewed UI terms are traditional before any runtime conversion`() {
        val expected = mapOf(
            "settings_auto_48" to "設定",
            "settings_auto_24" to "API介面",
            "settings_auto_26" to "管理介面",
            "settings_auto_20" to "即時語音字幕",
            "settings_auto_35" to "翻譯緩存",
            "settings_auto_36" to "啟用緩存",
            "settings_auto_37" to "清除緩存",
            "settings_auto_38" to "錯誤紀錄",
            "settings_auto_42" to "已儲存",
            "settings_auto_6" to "選擇適合的 OCR（光學字元辨識）引擎",
            "guide_auto_40" to "裁切",
            "guide_auto_44" to "戰",
            "voice_auto_20" to "儲存 Azure 設定",
            "voice_auto_37" to "即時字幕預覽",
            "voice_auto_28" to "已恢復預設位置"
        )
        expected.forEach { (key, text) -> assertEquals(text, traditional.getValue(key).text, key) }
        assertTrue(traditional.getValue("settings_auto_4").text.contains("緩存"))
        assertFalse(traditional.values.any { it.text.contains("快取") })
    }

    @Test
    fun `UI copy has no common simplified forms outside intentional sample content`() {
        // A focused regression guard, not a general-purpose Chinese script classifier.
        val simplifiedForms = "设译缓识选语玛测试个国发无战图页读时开关机计类况转档复户词".toSet()
        val japaneseGuideKeys = setOf("guide_auto_1", "guide_auto_18", "guide_auto_21", "guide_auto_25")
        val simplifiedSamples = setOf("voice_auto_10", "voice_auto_50")
        traditional.filterKeys { it !in simplifiedSamples }.forEach { (key, resource) ->
            val uiText = when {
                key == "guide_auto_1" -> resource.text.substringAfter("\\n")
                key in japaneseGuideKeys -> resource.text.substringAfter(" / ")
                else -> resource.text
            }
            val unexpected = uiText.filter { it in simplifiedForms }.toSet()
            assertTrue(unexpected.isEmpty(), "$key contains Simplified Chinese UI text: $unexpected")
        }
    }

    @Test
    fun `guide retains exact Japanese labels and the approved TW settings path`() {
        val expected = mapOf(
            "guide_auto_1" to "マイルーム → ゲームオプション → テキスト表示速度\\n個人空間 → 遊戲設定 → 劇情演出速度",
            "guide_auto_18" to "句読点待ち時間 / 標點符號等待時間",
            "guide_auto_21" to "ページ送り / 傳送頁面",
            "guide_auto_25" to "文字送り / 傳送文字"
        )
        expected.forEach { (key, text) -> assertEquals(text, traditional.getValue(key).text, key) }
    }

    @Test
    fun `voice test samples retain their intentionally different scripts`() {
        val expected = mapOf(
            "voice_auto_9" to "瑪修・基列萊特，在此。御主……戰鬥準備完成，請下達指示。",
            "voice_auto_10" to "玛修·基列莱特，在此。御主……战斗准备完成，请下达指示。",
            "voice_auto_49" to "瑪修",
            "voice_auto_50" to "玛修"
        )
        expected.forEach { (key, text) -> assertEquals(text, traditional.getValue(key).text, key) }
    }

    @Test
    fun `guide renders selected resources directly without converting Japanese characters`() {
        val guide = source("ui/screen/GuideScreen.kt")
        assertTrue(guide.contains("import androidx.compose.material3.Text\n"))
        assertFalse(guide.contains("LocalizedText"))
        assertFalse(guide.contains("localizeUiText("))
        for (key in listOf("guide_auto_1", "guide_auto_18", "guide_auto_21", "guide_auto_25")) {
            assertTrue(guide.contains("stringResource(R.string.$key)"), key)
        }
    }

    @Test
    fun `legacy UI fallback remains available outside the guide`() {
        assertTrue(source("localization/LocalizedText.kt").contains("AppLanguageManager.localizeUiText(context, text)"))
        val manager = source("localization/AppLanguageManager.kt")
        assertTrue(manager.contains("LANGUAGE_TRADITIONAL -> toTraditional(text)"))
        assertTrue(manager.contains("LANGUAGE_SIMPLIFIED -> toSimplified(text)"))
        assertTrue(source("ui/screen/SettingsScreen.kt").contains("import com.fgogotran.localization.LocalizedText as Text"))
    }

    private fun resources(directory: String): Map<String, StringResource> {
        val factory = DocumentBuilderFactory.newInstance().apply {
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            // Android's compile-time XMLConstants omits these JDK parser constants.
            setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", "")
            setAttribute("http://javax.xml.XMLConstants/property/accessExternalSchema", "")
        }
        val document = factory.newDocumentBuilder().parse(projectFile("res/$directory/strings.xml"))
        assertEquals("resources", document.documentElement.tagName)
        val strings = document.documentElement.getElementsByTagName("string")
        val result = linkedMapOf<String, StringResource>()
        for (index in 0 until strings.length) {
            val element = strings.item(index)
            val name = element.attributes.getNamedItem("name").nodeValue
            assertFalse(result.containsKey(name), "$directory has duplicate resource $name")
            val translatable = element.attributes.getNamedItem("translatable")?.nodeValue != "false"
            result[name] = StringResource(element.textContent, translatable)
        }
        return result
    }

    private fun source(path: String): String =
        projectFile("java/com/fgogotran/$path").readText().replace("\r\n", "\n")

    private fun projectFile(path: String): File = listOf(
        File("src/main/$path"), File("app/src/main/$path")
    ).first(File::isFile)
}
