package com.fgogotran.translation

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VoiceHintContractTest {
    private val context = VoiceHintContext(
        listOf("zh-CN-Xiaoxiao2:DragonHDFlashLatestNeural"),
        listOf("anxious", "affectionate", "empathetic", "sad", "whispering")
    )

    @Test
    fun `supported Azure identifiers are parsed exactly`() {
        for (style in listOf("anxious", "affectionate", "empathetic")) {
            val hint = assertNotNull(parse("""{"styles":["$style"],"confidence":0.9}"""))
            assertEquals(listOf(style), hint.styles)
            assertTrue(hint.dragonStyles.isEmpty())
            assertEquals(context.voiceNames.toSet(), hint.targetVoiceNames)
        }
        val childContext = context.copy(allowedStyles = listOf("cutesy"))
        assertEquals(listOf("cutesy"), parse("""{"styles":["cutesy"]}""", childContext)?.styles)
        assertNull(parse("""{"styles":["cute"]}""", childContext))
    }

    @Test
    fun `unsupported and generic synonym styles are not rewritten`() {
        for (style in listOf("strict", "serious", "happy", "fearful", "soft", "normal", "neutral")) {
            assertNull(parse("""{"styles":["$style"]}"""), style)
        }
        assertNull(parse("""{"emotion":"sad","delivery":"soft","energy":"high"}"""))
    }

    @Test
    fun `multiple returned styles collapse to first exact allowed style`() {
        val hint = assertNotNull(parse("""{
            "styles":["strict","anxious","sad"],"dragon_styles":["whispering"]
        }"""))
        assertEquals(listOf("anxious"), hint.styles)
        assertTrue(hint.dragonStyles.isEmpty())
    }

    @Test
    fun `compatible style fields use same request local list`() {
        for (field in listOf("style", "dragon_styles", "dragonStyles")) {
            val hint = assertNotNull(parse("""{"$field":"WHISPERING"}"""))
            assertEquals(listOf("whispering"), hint.styles)
        }
        assertEquals(listOf("anxious"), parse("""{"styles":"strict | anxious / sad"}""")?.styles)
    }

    @Test
    fun `delivery values keep existing bounds and omit zero`() {
        val hint = assertNotNull(parse("""{
            "rate":999,"pitch":-999,"pause":1.2,"intensity":5,"confidence":-1
        }"""))
        assertEquals(2, hint.rate)
        assertEquals(-2, hint.pitch)
        assertEquals(1, hint.pause)
        assertEquals(1.0, hint.intensity)
        assertEquals(0.0, hint.confidence)
        assertNull(parse("""{"rate":0,"pitch":0,"pause":0,"intensity":0.5}"""))
    }

    @Test
    fun `styleless voice may still receive bounded prosody`() {
        val styleless = context.copy(allowedStyles = emptyList())
        val hint = assertNotNull(parse("""{"styles":["sad"],"rate":-1,"pause":1}""", styleless))
        assertTrue(hint.styles.isEmpty())
        assertEquals(-1, hint.rate)
        assertEquals(1, hint.pause)
        assertNull(parse("""{"styles":["sad"]}""", styleless))
    }

    @Test
    fun `malformed optional hint fields never invalidate dialogue`() {
        val response = Json.parseToJsonElement("""{
            "dialogue":"台詞。","voice_hint":{
                "styles":[{},null,5,"anxious"],"rate":{},"pitch":[],"pause":true,
                "intensity":[],"confidence":{}
            }
        }""").jsonObject
        assertEquals("台詞。", response.getValue("dialogue").jsonPrimitive.content)
        val hint = assertNotNull(parseVoiceLineHint(response["voice_hint"], context))
        assertEquals(listOf("anxious"), hint.styles)
        assertNull(hint.rate)
        assertNull(hint.pitch)
        assertNull(hint.pause)
        assertNull(hint.intensity)
        assertNull(hint.confidence)
        for (raw in listOf("null", "[]", "42", "\"sad\"", "{}")) {
            assertNull(parse(raw))
        }
    }

    @Test
    fun `non finite numeric values cannot affect delivery`() {
        assertNull(parse("""{"rate":"NaN","pitch":"Infinity","pause":"-Infinity"}"""))
        val hint = assertNotNull(parse("""{"styles":["anxious"],"intensity":"NaN","confidence":"Infinity"}"""))
        assertNull(hint.intensity)
        assertNull(hint.confidence)
    }

    @Test
    fun `unknown target context skips parsing`() {
        assertNull(parseVoiceLineHint(Json.parseToJsonElement("""{"styles":["sad"],"rate":1}"""), null))
    }

    @Test
    fun `prompt supplies only exact voice specific choices without a static emotion example`() {
        val prompt = buildVoiceHintInstructions(context)
        assertTrue(prompt.contains("Voice(s): ${context.voiceNames.single()}"))
        assertTrue(prompt.contains("Allowed styles: anxious,affectionate,empathetic,sad,whispering"))
        assertTrue(prompt.contains("zero or one exact allowed style"))
        assertTrue(prompt.contains("never change translation"))
        assertFalse(prompt.contains("serious"))
        assertFalse(prompt.contains("strict"))
        assertTrue(buildVoiceHintInstructions(context.copy(allowedStyles = emptyList()))
            .contains("Allowed styles: none"))
    }

    @Test
    fun `prompt explicitly nests hint fields and forbids descriptive output`() {
        val prompt = buildVoiceHintInstructions(context)
        assertTrue(prompt.contains("voice_hint must be a JSON object or null, never a string."))
        assertTrue(prompt.contains("Only inside voice_hint: styles, dragon_styles, intensity, rate, pitch, pause, confidence"))
        assertTrue(prompt.contains("never put these at the top level"))
        assertTrue(prompt.contains("No descriptions, explanations, Markdown, or extra keys."))
        assertTrue(prompt.contains("retain required outer keys; do not copy example values"))

        val example = Json.parseToJsonElement(prompt.lineSequence()
            .single { it.startsWith("voice_hint field example only") }.substringAfter(": ")).jsonObject
        assertEquals(setOf("voice_hint"), example.keys)
        val objectValue = example.getValue("voice_hint").jsonObject
        val hint = assertNotNull(parseVoiceLineHint(objectValue, context))
        assertTrue(hint.styles.isEmpty())
        assertEquals(-1, hint.rate)
        assertEquals(0.8, hint.confidence)
    }

    @Test
    fun `nested active hints parse equally for standalone compact and full scene responses`() {
        val hintValue = """{"styles":["anxious"],"intensity":0.6,"confidence":0.85}"""
        val responseShapes = listOf(
            """{"voice_hint":$hintValue}""",
            """{"dialogue":"台詞。","voice_hint":$hintValue}""",
            """{"name":"角色","dialogue":"台詞。","choices":["選項"],"voice_hint":$hintValue}"""
        )
        for (raw in responseShapes) {
            val response = Json.parseToJsonElement(raw).jsonObject
            val hint = assertNotNull(parseVoiceLineHint(response["voice_hint"], context))
            assertEquals(listOf("anxious"), hint.styles)
            assertEquals(0.6, hint.intensity)
            assertEquals(0.85, hint.confidence)
            response["dialogue"]?.let { assertEquals("台詞。", it.jsonPrimitive.content) }
        }
    }

    @Test
    fun `neutral null is valid in all three response shapes`() {
        val responseShapes = listOf(
            """{"voice_hint":null}""",
            """{"dialogue":"台詞。","voice_hint":null}""",
            """{"name":"角色","dialogue":"台詞。","choices":[],"voice_hint":null}"""
        )
        for (raw in responseShapes) {
            val response = Json.parseToJsonElement(raw).jsonObject
            assertNull(parseVoiceLineHint(response["voice_hint"], context))
        }
    }

    @Test
    fun `observed flat descriptive response is still rejected without repair`() {
        val response = Json.parseToJsonElement("""{
            "voice_hint":"Exasperated, scolding tone", "styles":["anxious"],
            "intensity":0.65, "confidence":0.85, "rate":-1, "pause":1
        }""").jsonObject
        assertNull(parseVoiceLineHint(response["voice_hint"], context))
    }

    private fun parse(raw: String, target: VoiceHintContext = context): VoiceLineHint? =
        parseVoiceLineHint(Json.parseToJsonElement(raw), target)
}
