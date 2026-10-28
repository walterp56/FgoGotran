package com.fgogotran.translation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PromptGlossaryTargetPolicyTest {
    private val builder = PromptBuilder()

    // A deterministic routing fixture, not a substitute for an on-device ICU accuracy test.
    private fun convertedTerm(target: String): String = selectPromptGlossaryTarget(
        chineseText = " 魔术 ",
        englishText = " Magecraft ",
        targetLanguage = target
    ) { text, language ->
        assertEquals(" 魔术 ", text)
        if (language.startsWith("zh-Hant")) "魔術" else text.trim()
    }

    @Test
    fun `Chinese references use the selected script while English bypasses Chinese conversion`() {
        assertEquals("魔术", convertedTerm("zh-Hans"))
        for (target in listOf("zh-Hant", "zh-Hant-TW", "zh-Hant-HK")) {
            assertEquals("魔術", convertedTerm(target))
        }
        for (target in listOf("en", "en-US")) {
            assertEquals("Magecraft", selectPromptGlossaryTarget("魔术", " Magecraft ", target) { _, _ ->
                error("English must not use Chinese conversion")
            })
            assertEquals("", selectPromptGlossaryTarget("魔术", " ", target) { _, _ ->
                error("A missing English target must not fall back to Chinese")
            })
        }
    }

    @Test
    fun `pronoun targets and annotations follow the Chinese target without changing source forms`() {
        val source = "拙僧は貴様を守る。"
        for ((target, first, second, note) in listOf(
            listOf("zh-Hans", "贫僧", "你这家伙", "称"),
            listOf("zh-Hant", "貧僧", "你這傢伙", "稱")
        )) {
            val context = builder.buildPromptContext(PromptOutputFormat.PLAIN_TEXT, source, targetLanguage = target)
            val entries = TranslationGlossaryBuilder.build(source, context, emptyList(), "", false)
            val bySource = entries.associateBy { it.source }
            assertEquals(first, bySource.getValue("拙僧").target)
            assertEquals("第一人$note", bySource.getValue("拙僧").note)
            assertEquals(second, bySource.getValue("貴様").target)
            assertEquals("第二人$note", bySource.getValue("貴様").note)
        }
        val context = builder.buildPromptContext(PromptOutputFormat.PLAIN_TEXT, source, targetLanguage = "en")
        assertTrue(TranslationGlossaryBuilder.build(source, context, emptyList(), "", false).isEmpty())
    }

    @Test
    fun `prepared RAG reaches general request shapes while Japanese ruby and player literals remain intact`() {
        val source = "身体〈からだ〉の魔術。\n九条京香"
        val entries = listOf(
            TranslationGlossaryEntry("魔術", convertedTerm("zh-Hant")),
            TranslationGlossaryEntry("九条京香", "九条京香", "玩家名")
        )
        val prompts = listOf(
            buildGeneralSingleUserPrompt(builder, source, emptyList(), glossaryEntries = entries),
            buildGeneralSingleUserPrompt(builder, source, emptyList(), translateAsName = true, glossaryEntries = entries),
            buildGeneralSingleUserPrompt(builder, source, emptyList(), translateAsChoices = true, glossaryEntries = entries),
            buildGeneralBatchUserPrompt(listOf(source), glossaryEntries = entries),
            buildGeneralBatchUserPrompt(listOf(source), translateAsChoices = true, glossaryEntries = entries),
            builder.buildCropUserPrompt(source, entries),
            builder.buildDialogueSubtitleRowsPrompt(source.lines(), entries)
        )
        for (prompt in prompts) {
            assertTrue(prompt.contains("魔術->魔術"))
            assertFalse(prompt.contains("魔术"))
            assertTrue(prompt.contains("身体〈からだ〉"))
            assertTrue(prompt.contains("九条京香->九条京香 #玩家名"))
            assertFalse(prompt.contains("身體〈からだ〉"))
            assertFalse(prompt.contains("九條京香"))
        }
        for (profile in TranslationPromptProfile.entries) {
            val context = builder.buildPromptContext(
                PromptOutputFormat.PLAIN_TEXT, source, targetLanguage = "zh-Hant", promptProfile = profile
            )
            val system = builder.buildSystemPrompt(context)
            assertTrue(system.contains("Traditional Chinese"))
            val user = builder.buildUserPrompt(source, emptyList(), entries)
            assertTrue(user.contains("魔術->魔術"))
            assertTrue(user.contains("Current Japanese:\n$source"))
        }
    }

    @Test
    fun `repair reuses the prepared Hant glossary without converting its Japanese or old draft`() {
        val source = "身体〈からだ〉の魔術。"
        val initial = listOf(
            ChatMessage("system", builder.buildSystemPrompt(PromptContext(targetLanguage = "zh-Hant", hasRuby = true))),
            ChatMessage("user", builder.buildUserPrompt(source, emptyList(),
                listOf(TranslationGlossaryEntry("魔術", convertedTerm("zh-Hant")))))
        )
        val repaired = buildGeneralStrictRepairMessages(initial, "zh-Hant", "这是一份旧译文。")
        assertEquals(initial[0], repaired[0])
        assertTrue(repaired[1].content.startsWith(initial[1].content))
        assertTrue(repaired[1].content.contains("魔術->魔術"))
        assertTrue(repaired[1].content.contains("Current Japanese:\n$source"))
        assertTrue(repaired[1].content.contains("这是一份旧译文。"))
        assertTrue(repaired[1].content.contains("Use Traditional Chinese."))
    }

    @Test
    fun `prepared glossary diagnostics show actual targets and normalize the language label`() {
        val entries = listOf(TranslationGlossaryEntry("魔術", convertedTerm("zh-Hant")))
        assertEquals("Prompt glossary target=zh-Hant: 1 entry(s) [魔術->魔術]",
            formatGeneralPromptGlossaryLog(entries, "zh-Hant-HK"))
        assertFalse(formatGeneralPromptGlossaryLog(entries, "zh-Hant").contains("魔术"))
        assertEquals("Prompt glossary target=en: 0 entry(s) []", formatGeneralPromptGlossaryLog(emptyList(), "en"))
    }
}
