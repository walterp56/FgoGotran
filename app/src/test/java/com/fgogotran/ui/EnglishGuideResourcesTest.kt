package com.fgogotran.ui

import java.io.File
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

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
