package com.fgogotran.translation

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class GeneralTranslationResponseContractTest {
    private val hintContext = VoiceHintContext(
        voiceNames = listOf("zh-CN-Xiaoxiao2:DragonHDFlashLatestNeural"),
        allowedStyles = listOf("anxious", "whispering")
    )

    @Test
    fun `batch strings preserve literal primitive words and punctuation`() {
        val strings = GeneralTranslationResponseContract.parseStrings(
            Json.parseToJsonElement("""[" null ","true","42","……？！","  台詞。  "]"""),
            expectedCount = 5
        )
        assertEquals(listOf("null", "true", "42", "……？！", "台詞。"), strings)
        assertEquals(emptyList(), GeneralTranslationResponseContract.parseStrings(
            Json.parseToJsonElement("[]"), expectedCount = 0
        ))
    }

    @Test
    fun `batch rejects non arrays and incorrect item counts`() {
        for (raw in listOf("null", "true", "42", "\"台詞\"", "{}")) {
            assertFailsWith<IllegalArgumentException>(raw) {
                GeneralTranslationResponseContract.parseStrings(Json.parseToJsonElement(raw), 1)
            }
        }
        for (raw in listOf("[]", "[\"一\",\"二\"]")) {
            assertFailsWith<IllegalArgumentException>(raw) {
                GeneralTranslationResponseContract.parseStrings(Json.parseToJsonElement(raw), 1)
            }
        }
    }

    @Test
    fun `batch rejects non string and blank items`() {
        for (raw in invalidStringValues) {
            assertFailsWith<IllegalArgumentException>(raw) {
                GeneralTranslationResponseContract.parseStrings(
                    Json.parseToJsonElement("[$raw]"), expectedCount = 1
                )
            }
        }
    }

    @Test
    fun `full scene parses exact requested text fields and tolerates unknown keys`() {
        val fields = parseScene("""{
            "name":"  角色  ","dialogue":"  ……？！  ","choices":[" null ","true","42"],
            "unknown":{"nested":true},"styles":["anxious"]
        }""", expectName = true, expectDialogue = true, expectedChoiceCount = 3)
        assertEquals(GeneralSceneTextFields("角色", "……？！", listOf("null", "true", "42")), fields)
    }

    @Test
    fun `requested scene fields reject missing non string and blank values`() {
        for (field in listOf("name", "dialogue")) {
            assertFailsWith<IllegalArgumentException>("missing $field") {
                parseScene("{}", expectName = field == "name", expectDialogue = field == "dialogue")
            }
            for (raw in invalidStringValues) {
                assertFailsWith<IllegalArgumentException>("$field=$raw") {
                    parseScene("""{"$field":$raw}""",
                        expectName = field == "name", expectDialogue = field == "dialogue")
                }
            }
        }
    }

    @Test
    fun `compact scene permits missing or null unrequested text fields`() {
        for (raw in listOf(
            """{"dialogue":"台詞。"}""",
            """{"name":null,"dialogue":"台詞。","choices":[]}"""
        )) {
            assertEquals(GeneralSceneTextFields(null, "台詞。", emptyList()),
                parseScene(raw, expectDialogue = true))
        }
        assertEquals(GeneralSceneTextFields("角色", null, emptyList()),
            parseScene("""{"name":"角色","dialogue":null}""", expectName = true))
        assertEquals(GeneralSceneTextFields(null, null, listOf("選項")),
            parseScene("""{"choices":["選項"]}""", expectedChoiceCount = 1))
    }

    @Test
    fun `unrequested scene text fields must be missing or null`() {
        for (field in listOf("name", "dialogue")) {
            for (raw in invalidStringValues.filterNot { it == "null" } + "\"unexpected\"") {
                assertFailsWith<IllegalArgumentException>("$field=$raw") {
                    parseScene("""{"$field":$raw}""")
                }
            }
        }
    }

    @Test
    fun `scene choices enforce arrays exact counts and nonempty strings`() {
        assertFailsWith<IllegalArgumentException> {
            parseScene("{}", expectedChoiceCount = 1)
        }
        for (raw in listOf("null", "true", "42", "\"選項\"", "{}", "[]", "[\"一\",\"二\"]")) {
            assertFailsWith<IllegalArgumentException>(raw) {
                parseScene("""{"choices":$raw}""", expectedChoiceCount = 1)
            }
        }
        for (raw in invalidStringValues) {
            assertFailsWith<IllegalArgumentException>(raw) {
                parseScene("""{"choices":[$raw]}""", expectedChoiceCount = 1)
            }
        }
        for (raw in listOf("null", "{}", "\"\"", "[\"unexpected\"]")) {
            assertFailsWith<IllegalArgumentException>(raw) {
                parseScene("""{"choices":$raw}""", expectedChoiceCount = 0)
            }
        }
    }

    @Test
    fun `malformed optional voice hints cannot invalidate parsed translation`() {
        val malformedHints = listOf(
            "null", "[]", "42", "\"Exasperated, scolding tone\"", "{}",
            """{"styles":[{},null,5],"rate":{},"pitch":[],"pause":true}"""
        )
        for (raw in malformedHints) {
            val response = sceneObject("""{"dialogue":"台詞。","voice_hint":$raw}""")
            assertEquals(GeneralSceneTextFields(null, "台詞。", emptyList()),
                GeneralTranslationResponseContract.parseScene(response, false, true, 0))
            assertNull(parseVoiceLineHint(response["voice_hint"], hintContext), raw)
        }

        val response = sceneObject("""{
            "name":"角色","dialogue":"台詞。","choices":["選項"],"voice_hint":{
                "styles":[{},null,5,"anxious"],"rate":{},"pitch":[],"pause":true,
                "intensity":[],"confidence":{}
            }
        }""")
        assertEquals(GeneralSceneTextFields("角色", "台詞。", listOf("選項")),
            GeneralTranslationResponseContract.parseScene(response, true, true, 1))
        val hint = assertNotNull(parseVoiceLineHint(response["voice_hint"], hintContext))
        assertEquals(listOf("anxious"), hint.styles)
        assertNull(hint.rate)
        assertNull(hint.pitch)
        assertNull(hint.pause)
        assertNull(hint.intensity)
        assertNull(hint.confidence)
    }

    @Test
    fun `valid translation retains request local hint aliases and exact styles`() {
        for (field in listOf("styles", "style", "dragon_styles", "dragonStyles")) {
            val response = sceneObject("""{
                "dialogue":"台詞。","voice_hint":{"$field":"WHISPERING"}
            }""")
            assertEquals("台詞。", GeneralTranslationResponseContract.parseScene(
                response, expectName = false, expectDialogue = true, expectedChoiceCount = 0
            ).dialogue)
            val hint = assertNotNull(parseVoiceLineHint(response["voice_hint"], hintContext))
            assertEquals(listOf("whispering"), hint.styles)
            assertEquals(hintContext.voiceNames.toSet(), hint.targetVoiceNames)
        }
        val response = sceneObject("""{
            "dialogue":"台詞。","voice_hint":{"styles":["serious","strict"]}
        }""")
        assertEquals("台詞。", GeneralTranslationResponseContract.parseScene(response, false, true, 0).dialogue)
        assertNull(parseVoiceLineHint(response["voice_hint"], hintContext))
    }

    private fun parseScene(
        raw: String,
        expectName: Boolean = false,
        expectDialogue: Boolean = false,
        expectedChoiceCount: Int = 0
    ): GeneralSceneTextFields = GeneralTranslationResponseContract.parseScene(
        sceneObject(raw), expectName, expectDialogue, expectedChoiceCount
    )

    private fun sceneObject(raw: String): JsonObject = Json.parseToJsonElement(raw).jsonObject

    private val invalidStringValues = listOf(
        "null", "true", "false", "42", "{}", "[]", "\"\"", "\" \\t \\n \""
    )
}
