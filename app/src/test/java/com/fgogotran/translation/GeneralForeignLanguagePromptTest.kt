package com.fgogotran.translation

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GeneralForeignLanguagePromptTest {
    private val builder = PromptBuilder()
    private val targets = listOf("zh-Hans", "zh-Hant", "en")
    private val policy = "For foreign expressions, choose retention or natural target-language translation by context, voice and readability."

    @Test
    fun `all general targets and base profiles choose by context without forcing retention`() {
        for (target in targets) {
            val contexts = listOf(
                PromptContext(targetLanguage = target),
                PromptContext(targetLanguage = target, isCropMode = true, isDialogue = false),
                PromptContext(targetLanguage = target, promptProfile = TranslationPromptProfile.BATTLE_SUBTITLE),
                PromptContext(targetLanguage = target, promptProfile = TranslationPromptProfile.DIALOGUE_SUBTITLE)
            )
            for (context in contexts) {
                val prompt = builder.buildSystemPrompt(context)
                assertEquals(1, prompt.split(policy).size - 1)
                assertTrue(prompt.contains("Keep or restore the foreign word itself when discussing its language, word forms or wordplay"))
                assertTrue(prompt.contains("restore clear katakana to established foreign spelling and preserve diacritics"))
                assertTrue(prompt.contains("if unsure, translate the meaning"))
                assertTrue(prompt.contains("メルシー is French Merci, not English mercy"))
                assertFalse(prompt.contains("メルシー can be Merci or thank you"))
                assertTrue(prompt.contains("Never invent foreign wording."))
                assertFalse(prompt.contains("Foreign-language exception:"))
                assertFalse(prompt.contains("even when the requested translation is Chinese or English"))
                assertFalse(prompt.contains("メルシー -> Merci"))
                assertFalse(prompt.contains("including retained foreign expressions"))
                assertTrue(prompt.contains("Use glossary targets exactly for their specified source meanings"))
                assertTrue(prompt.contains("Preserve configured player names and explicitly protected literals verbatim, including kana."))
                assertTrue(prompt.contains(if (target == "en") "leave no other kana" else "common loanwords may use compact English"))
                assertFalse(prompt.contains("base〈ruby〉"))
            }
        }
    }

    @Test
    fun `ruby keeps base and reading roles without forcing either into a single language`() {
        for (target in targets) {
            for (source in listOf("別れの挨拶〈アディオス〉だ。", "Merci〈ありがとう〉、マスター。")) {
                val context = builder.buildPromptContext(PromptOutputFormat.PLAIN_TEXT, source, targetLanguage = target)
                assertTrue(context.hasRuby)
                val prompt = builder.buildSystemPrompt(context)
                assertTrue(prompt.contains("preserve both meanings and their base/ruby roles"))
                assertTrue(prompt.contains("apply the target-language and foreign-expression rules to both"))
                assertTrue(prompt.contains("Never omit source ruby or invent annotations."))
                assertTrue(prompt.contains("If the translated ruby is identical to the translated base, output the base only"))
                assertFalse(prompt.contains("Chinese base〈ruby〉"))
                assertFalse(prompt.contains("English base〈English ruby〉"))
                assertFalse(prompt.contains("English-style ruby"))
                assertTrue(builder.buildUserPrompt(source, emptyList()).endsWith(source))
            }
        }
    }

    @Test
    fun `source spelling diacritics and choice order survive real request assembly`() {
        val source = "Très bien, señorita.\nメルシー、マスター。"
        assertEquals(source, TextNormalizer.normalizeForTranslation(source))
        assertEquals("Current Japanese:\n$source", buildGeneralSingleUserPrompt(builder, source, emptyList()))
        val choices = listOf("メルシー！", "Adiós, amigo!", "ここで待とう。")
        val user = buildGeneralBatchUserPrompt(choices, translateAsChoices = true)
        assertTrue(user.contains("1. メルシー！\n2. Adiós, amigo!\n3. ここで待とう。"))
        assertTrue(user.contains("exactly 3 nonempty translated strings"))
        assertTrue(user.contains("treat every item as a player choice"))
        assertTrue(TextNormalizer.hasTranslatableContent("Très bien!"))
        assertFalse(TextNormalizer.isPunctuationOnlyDialogue("Merci!"))
    }

    @Test
    fun `strict repair retains foreign rules source and required response shape`() {
        for (target in targets) {
            for (format in PromptOutputFormat.entries) {
                val context = PromptContext(targetLanguage = target, outputFormat = format, hasRuby = true)
                val original = listOf(
                    ChatMessage("system", builder.buildSystemPrompt(context)),
                    ChatMessage("user", buildGeneralSingleUserPrompt(builder, "Merci〈ありがとう〉。", emptyList()))
                )
                val repair = buildGeneralStrictRepairMessages(original, target, "ありがとう。", listOf("ありがとう"))
                assertEquals(original[0], repair[0])
                assertTrue(repair[0].content.contains(policy))
                assertTrue(repair[1].content.startsWith(original[1].content))
                assertTrue(repair[1].content.contains("required output format"))
                assertTrue(repair[1].content.contains("Translate untranslated Japanese except exact player names"))
            }
        }
    }

    @Test
    fun `response contracts accept foreign only and mixed target strings without adding filler`() {
        val choices = Json.parseToJsonElement("""["Merci!","Adiós, amigo!","在這裡等吧。"]""")
        assertEquals(listOf("Merci!", "Adiós, amigo!", "在這裡等吧。"),
            GeneralTranslationResponseContract.parseStrings(choices, 3))
        assertFailsWith<IllegalArgumentException> { GeneralTranslationResponseContract.parseStrings(choices, 2) }
        val scene = Json.parseToJsonElement("""{"name":"御主","dialogue":"Merci!","choices":["Adiós!"]}""")
        val parsed = GeneralTranslationResponseContract.parseScene(
            scene as kotlinx.serialization.json.JsonObject, true, true, 1)
        assertEquals("Merci!", parsed.dialogue)
        assertEquals(listOf("Adiós!"), parsed.choices)
    }

    @Test
    fun `contextual policy versions invalidate the earlier forced retention results`() {
        for (target in targets) {
            assertTrue(builder.buildSystemPrompt(PromptContext(targetLanguage = target)).contains(policy))
            assertTrue(PromptBuilder.promptVersionFor(target, false).endsWith("contextual-foreign"))
            assertFalse(PromptBuilder.promptVersionFor(target, false).endsWith("foreign-expressions"))
        }
    }
}
