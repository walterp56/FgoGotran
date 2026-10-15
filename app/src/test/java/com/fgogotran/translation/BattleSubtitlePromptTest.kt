package com.fgogotran.translation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BattleSubtitlePromptTest {
    private val promptBuilder = PromptBuilder()

    @Test
    fun `battle prompt is independent and keeps battle style`() {
        val context = promptBuilder.buildPromptContext(
            outputFormat = PromptOutputFormat.PLAIN_TEXT,
            sourceText = "「行くぞ、マスター！！？？」",
            currentSpeaker = "MUST_NOT_APPEAR",
            characterContextPrompt = "MUST_NOT_APPEAR",
            promptProfile = TranslationPromptProfile.BATTLE_SUBTITLE
        )
        val prompt = promptBuilder.buildSystemPrompt(context)

        assertTrue(prompt.contains("Fate/Grand Order battle subtitles"))
        assertTrue(prompt.contains("one concise, natural Simplified Chinese caption"))
        assertTrue(prompt.contains("fragments stay fragments"))
        assertTrue(prompt.contains("Use glossary targets exactly for their specified source meanings"))
        assertTrue(prompt.contains("including kana"))
        assertTrue(prompt.contains("Translate or transliterate all other kana"))
        assertFalse(prompt.contains("MUST_NOT_APPEAR"))
        assertFalse(prompt.contains("previous Japanese context"))
        assertFalse(prompt.contains("base〈ruby〉"))
        assertFalse(prompt.contains("this role never licenses"))
    }

    @Test
    fun `battle prompt keeps placeholders and second person guidance`() {
        val context = promptBuilder.buildPromptContext(
            outputFormat = PromptOutputFormat.PLAIN_TEXT,
            sourceText = "「アテシが__FGO_TERM_0__を守る。てめえ、お前は下がれ！」",
            targetLanguage = "zh-Hans",
            promptProfile = TranslationPromptProfile.BATTLE_SUBTITLE
        )
        val prompt = promptBuilder.buildSystemPrompt(context)

        assertTrue(prompt.contains("Keep every placeholder token starting with __FGO"))
        assertTrue(prompt.contains("Translate Japanese second-person address by tone/relationship"))
        assertFalse(prompt.contains("[FP] アテシ"))
        assertFalse(prompt.contains("[2P] てめえ"))
    }

    @Test
    fun `battle prompt excludes ruby and story-only context`() {
        val context = promptBuilder.buildPromptContext(
            outputFormat = PromptOutputFormat.PLAIN_TEXT,
            sourceText = "第一行〈ルビ〉\n第二行",
            forceRuby = true,
            requestVoiceHint = true,
            characterContextPrompt = "MUST_NOT_APPEAR",
            promptProfile = TranslationPromptProfile.BATTLE_SUBTITLE
        )
        val prompt = promptBuilder.buildSystemPrompt(context)

        assertFalse(context.hasRuby)
        assertFalse(context.requestVoiceHint)
        assertFalse(context.isUnattributedDialogue)
        assertFalse(prompt.contains("base〈ruby〉"))
        assertFalse(prompt.contains("voice_hint describes delivery"))
        assertFalse(prompt.contains("MUST_NOT_APPEAR"))
    }

    @Test
    fun `general dialogue prompt remains separate from battle policy`() {
        val prompt = promptBuilder.buildSystemPrompt(
            context = PromptContext(isDialogue = true)
        )

        assertFalse(prompt.contains("Fate/Grand Order battle subtitles"))
        assertTrue(prompt.contains("in-game Simplified Chinese"))
    }

    @Test
    fun `battle prompt version invalidates old cached policy`() {
        assertEquals("battle-subtitle-v10-plain-layout", PromptBuilder.BATTLE_PROMPT_VERSION)
    }

    @Test
    fun `default battle prompt remains compact`() {
        val context = promptBuilder.buildPromptContext(
            outputFormat = PromptOutputFormat.PLAIN_TEXT,
            sourceText = "行くぞ。",
            promptProfile = TranslationPromptProfile.BATTLE_SUBTITLE
        )
        val prompt = promptBuilder.buildSystemPrompt(context)

        assertTrue(prompt.length < 1_100, "Battle prompt was ${prompt.length} characters")
    }
}
