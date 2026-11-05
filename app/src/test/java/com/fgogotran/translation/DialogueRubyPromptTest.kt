package com.fgogotran.translation

import com.fgogotran.data.SettingsRepository
import org.junit.Assert.*
import org.junit.Test

class DialogueRubyPromptTest {
    private val builder = PromptBuilder()
    private val main = "おかあさん、やっていい？\n頑張ってるごほうびに殺っていい？"
    private val inlineRuby = main.replace("おかあさん", "おかあさん〈マスター〉")

    @Test fun apiSourceKeepsRubyInlineAndPreservesSentenceRows() {
        assertEquals("Current Japanese:\n$inlineRuby", builder.buildUserPrompt(inlineRuby, emptyList()))
    }

    @Test fun inlineReadingsActivateMasterPronounAndTargetRules() {
        listOf(SettingsRepository.TARGET_LANGUAGE_SIMPLIFIED, SettingsRepository.TARGET_LANGUAGE_TRADITIONAL,
            SettingsRepository.TARGET_LANGUAGE_ENGLISH).forEach { language ->
            val context = builder.buildPromptContext(PromptOutputFormat.PLAIN_TEXT, inlineRuby,
                targetLanguage = language)
            assertTrue(context.hasRuby)
            assertTrue(context.hasMasterWord)
            val entries = TranslationGlossaryBuilder.build(inlineRuby, context, emptyList(), "", false)
            assertEquals(if (language == SettingsRepository.TARGET_LANGUAGE_ENGLISH) "Master" else "御主",
                entries.single { it.source == "マスター" }.target)
            assertTrue(builder.buildSystemPrompt(context).contains("base〈ruby〉"))
        }
        val pronoun = builder.buildPromptContext(PromptOutputFormat.PLAIN_TEXT, "己〈うぬ〉を倒せ。")
        assertEquals("うぬ", pronoun.specialSecondPersonMappings.single().sourceForm)
    }

    @Test fun choiceRubyUsesInlineMarkupAndCropAndBattleKeepTheirProfiles() {
        val source = "南瓜狼〈パンプキンウルフ〉"
        val choice = builder.buildPromptContext(PromptOutputFormat.PLAIN_TEXT, source, isChoiceBatch = true)
        assertTrue(choice.hasRuby)
        assertTrue(builder.buildSystemPrompt(choice).contains("base〈ruby〉"))
        val otherProfiles = listOf(
            builder.buildPromptContext(PromptOutputFormat.JSON_ARRAY, source, isCropMode = true),
            builder.buildPromptContext(PromptOutputFormat.PLAIN_TEXT, source,
                promptProfile = TranslationPromptProfile.BATTLE_SUBTITLE)
        )
        otherProfiles.forEach { assertFalse(it.hasRuby) }
    }

    @Test fun noRubyPromptIsUnchanged() {
        assertEquals("Current Japanese:\n───『うるさい』……。", builder.buildUserPrompt("───『うるさい』……。", emptyList()))
        assertFalse(builder.buildPromptContext(PromptOutputFormat.PLAIN_TEXT, main).hasRuby)
    }

    @Test fun sakuraKeepsInlineRubyInItsSourceRowsAndRetainsContextAlignment() {
        val context = builder.buildPromptContext(PromptOutputFormat.PLAIN_TEXT, inlineRuby)
        val previous = SceneDialogueContext(null, null, "前の台詞。", "前句。", "zh-Hans", "previous")
        val request = SakuraPromptBuilder.buildSingleRequest(inlineRuby, listOf(previous), "", context)
        assertEquals(1, request.previousSourceLineCount)
        assertEquals(2, request.currentSourceLineCount)
        assertTrue(request.userPrompt.endsWith("前の台詞。\n$inlineRuby"))
        assertTrue(request.userPrompt.contains("マスター->御主"))
        assertFalse(request.userPrompt.contains("マスター->マスター"))
        assertEquals("媽媽，可以動手嗎？\n作為努力的獎勵，可以殺掉嗎？",
            request.extractCurrentTranslation("前句。\n媽媽，可以動手嗎？\n作為努力的獎勵，可以殺掉嗎？"))
        assertNull(request.extractCurrentTranslation("metadata echo\n前句。\n媽媽\n獎勵"))
        val retry = SakuraPromptBuilder.buildSingleRequest(inlineRuby, listOf(previous), "", context,
            retryStage = 1)
        assertEquals(0, retry.previousSourceLineCount)
        assertEquals(2, retry.currentSourceLineCount)
        assertTrue(retry.userPrompt.endsWith(inlineRuby))
    }

    @Test fun sakuraNoRubyUsesOriginalTrainedPrefix() {
        val context = builder.buildPromptContext(PromptOutputFormat.PLAIN_TEXT, "空は青い。")
        val request = SakuraPromptBuilder.buildSingleRequest("空は青い。", emptyList(), "", context)
        assertEquals("将下面的日文文本翻译成中文：空は青い。", request.userPrompt)
        assertEquals(1, request.currentSourceLineCount)
    }
}
