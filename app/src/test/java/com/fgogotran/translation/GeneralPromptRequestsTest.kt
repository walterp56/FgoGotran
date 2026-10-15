package com.fgogotran.translation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GeneralPromptRequestsTest {
    private val builder = PromptBuilder()
    private val previous = SceneDialogueContext(
        sourceSpeakerName = "マシュ",
        translatedSpeakerName = "Mash",
        sourceDialogue = "まだ、まだ……！",
        translatedDialogue = "Still... still!",
        targetLocale = "en",
        dialogueSourceKey = "previous-scene"
    )
    private val glossary = listOf(TranslationGlossaryEntry("マスター", "Master", "female"))

    @Test
    fun `single no-context prompt preserves input formatting and established layout`() {
        val source = "違う、違う。\nそれは───〈ルビ〉！？"
        val choices = listOf("はい……", "まだ、まだ！")
        assertEquals(
            builder.buildUserPrompt(source, choices),
            buildGeneralSingleUserPrompt(builder, source, choices)
        )
        assertEquals(
            "Current Japanese:\n$source",
            buildGeneralSingleUserPrompt(builder, source, choices, translateAsName = true)
        )
        assertEquals(
            "Current Japanese:\n$source",
            buildGeneralSingleUserPrompt(builder, source, choices, translateAsChoices = true)
        )
    }

    @Test
    fun `dialogue speaker applies only to current dialogue and reference speaker cannot lend register`() {
        val dialogueContext = buildGeneralCurrentSpeakerContext("  Beni-Enma (JP: 紅閻魔)  ", true)
        assertTrue(dialogueContext.contains("Current speaker (context only):\nBeni-Enma (JP: 紅閻魔)"))
        assertTrue(
            dialogueContext.contains(
                "Use this identity only for voice and register of the current dialogue supported by Japanese."
            )
        )
        assertTrue(
            dialogueContext.contains(
                "Never apply it to player choices or name labels, or infer omitted participants, possession, or relationships."
            )
        )
        val referenceContext = buildGeneralCurrentSpeakerContext("Beni-Enma (JP: 紅閻魔)", false)
        assertTrue(referenceContext.startsWith("Scene speaker (reference only):"))
        assertTrue(referenceContext.contains("player choices or name labels"))
        assertTrue(referenceContext.contains("this speaker does not supply their voice or register"))
        assertFalse(referenceContext.contains("Use this identity only for voice"))
        assertEquals("", buildGeneralCurrentSpeakerContext(" \n ", true))
        assertEquals("", buildGeneralCurrentSpeakerContext("", false))
    }

    @Test
    fun `single dialogue name and choice builders scope the same scene identity`() {
        val source = "行こう……？"
        for ((asName, asChoices) in listOf(false to false, true to false, false to true)) {
            val prompt = buildGeneralSingleUserPrompt(
                builder, source, listOf("はい。"), listOf(previous), "Beni-Enma (JP: 紅閻魔)",
                translateAsChoices = asChoices, translateAsName = asName, glossaryEntries = glossary
            )
            assertTrue(prompt.contains("Glossary (exact targets for matching source meanings):"))
            assertTrue(prompt.contains("player title only"))
            assertTrue(prompt.contains("Current Japanese:\n$source"))
            assertTrue(prompt.contains("JP: ${previous.sourceDialogue}"))
            if (asName || asChoices) {
                assertTrue(prompt.contains("Scene speaker (reference only):"))
                assertTrue(prompt.contains("Current items are ${if (asName) "name labels" else "player choices"};"))
                assertFalse(prompt.contains("Current speaker (context only):"))
                assertFalse(prompt.contains("Player choices (context only; do not translate):"))
                assertFalse(prompt.contains("Use this identity only for voice"))
            } else {
                assertTrue(prompt.contains("Current speaker (context only):"))
                assertTrue(prompt.contains("Player choices (context only; do not translate):\n1. はい。"))
                assertFalse(prompt.contains("Scene speaker (reference only):"))
            }
        }
    }

    @Test
    fun `batch preserves row count order wrapping and scopes speaker for player choices`() {
        val texts = listOf("はい……", "違う、違う。\nまだ……！", "南瓜狼〈パンプキンウルフ〉？")
        for (asChoices in listOf(false, true)) {
            val prompt = buildGeneralBatchUserPrompt(
                texts, "Beni-Enma (JP: 紅閻魔)", asChoices, glossary, listOf(previous)
            )
            assertTrue(prompt.contains("Current Japanese rows:\n1. はい……\n2. 違う、違う。\n   まだ……！\n3. 南瓜狼〈パンプキンウルフ〉？"))
            assertTrue(prompt.contains("exactly 3 nonempty translated strings, one per input item, in the same order"))
            assertTrue(prompt.contains("JP: ${previous.sourceDialogue}"))
            assertTrue(prompt.indexOf("Glossary (") < prompt.indexOf("JP: ${previous.sourceDialogue}"))
            assertTrue(prompt.indexOf("JP: ${previous.sourceDialogue}") < prompt.indexOf("Current Japanese rows:"))
            assertEquals(asChoices, prompt.contains("treat every item as a player choice"))
            if (asChoices) {
                assertTrue(prompt.contains("Scene speaker (reference only):"))
                assertTrue(prompt.contains("Current items are player choices; this speaker does not supply their voice or register."))
                assertFalse(prompt.contains("Use this identity only for voice"))
            } else {
                assertTrue(prompt.contains("Current speaker (context only):"))
            }
        }
    }

    @Test
    fun `strict repair keeps initial system and user prefix across targets and request shapes`() {
        for ((target, targetLabel) in listOf(
            "zh-Hans" to "Simplified Chinese", "zh-Hant" to "Traditional Chinese", "en" to "English"
        )) {
            val source = "マスター、まだマスターしていない……？"
            val userPrompts = listOf(
                buildGeneralSingleUserPrompt(
                    builder, source, listOf("はい。", "いいえ。"), listOf(previous), "マシュ", glossaryEntries = glossary
                ),
                buildGeneralSingleUserPrompt(builder, "マスター様", emptyList(), translateAsName = true, glossaryEntries = glossary),
                buildGeneralBatchUserPrompt(listOf("はい。", "まだ……"), "マシュ", true, glossary, listOf(previous)),
                buildGeneralBatchUserPrompt(listOf(source, "違う、違う！"), "マシュ", false, glossary, listOf(previous)),
                builder.buildCropUserPrompt("マスター\nまだ……", glossary)
            )
            for (userPrompt in userPrompts) {
                val system = " \n" + builder.buildSystemPrompt(
                    PromptContext(targetLanguage = target, outputFormat = PromptOutputFormat.JSON_ARRAY, hasRuby = true)
                ) + "\n "
                val originalUser = " \n$userPrompt\n "
                val initial = listOf(ChatMessage("system", system), ChatMessage("user", originalUser))
                val first = buildGeneralStrictRepairMessages(initial, target, "FIRST_BAD_DRAFT マスター", listOf("マスター"))
                val second = buildGeneralStrictRepairMessages(initial, target, "SECOND_BAD_DRAFT まだ", listOf("まだ"))
                assertEquals(2, first.size)
                assertEquals(initial[0], first[0])
                assertEquals(initial[0], second[0])
                assertTrue(first[1].content.startsWith(originalUser))
                assertTrue(second[1].content.startsWith(originalUser))
                assertTrue(second[1].content.contains("Use $targetLabel."))
                assertTrue(second[1].content.contains("Repair the draft using the current Japanese source and required output format."))
                assertTrue(
                    second[1].content.contains(
                        "Translate untranslated Japanese except exact player names and literals explicitly required to remain unchanged."
                    )
                )
                assertTrue(second[1].content.contains("Preserve every meaning; do not add explanation."))
                assertEquals(1, second[1].content.split("Previous draft (repair only):").size - 1)
                assertEquals(1, second[1].content.split("SECOND_BAD_DRAFT").size - 1)
                assertFalse(second[1].content.contains("FIRST_BAD_DRAFT"))
                assertFalse(second[1].content.contains("remove all Japanese kana"))
                assertEquals(initial[1].content, originalUser)
            }
        }
    }

    @Test
    fun `strict repair without prior draft still appends one feedback block`() {
        val initial = listOf(ChatMessage("system", "SYSTEM"), ChatMessage("user", "Current Japanese:\nユウキ"))
        val repair = buildGeneralStrictRepairMessages(initial, "en")
        assertEquals("SYSTEM", repair[0].content)
        assertTrue(repair[1].content.startsWith(initial[1].content))
        assertEquals(1, repair[1].content.split("Previous draft (repair only):").size - 1)
        assertFalse(repair[1].content.contains("Review these Japanese fragments:"))
    }
}
