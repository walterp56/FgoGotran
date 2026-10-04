package com.fgogotran.translation

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CharacterContextPromptTest {
    private val promptBuilder = PromptBuilder()

    @Test
    fun `dialogue prompt includes matched character context`() {
        val prompt = promptBuilder.buildSystemPrompt(
            context = PromptContext(
                isDialogue = true,
                characterContextPrompt = "Use 啾 only when でち occurs."
            )
        )

        assertTrue(prompt.contains("Use 啾 only when でち occurs."))
        assertTrue(
            prompt.contains(
                "Apply character context only to the current dialogue, never names or choices. " +
                    "It is voice/register guidance, not evidence for unstated participants, possession, " +
                    "or relationships; do not add tics absent from current JP."
            )
        )
        assertTrue(prompt.contains("Preserve stated references and action roles"))
        assertFalse(prompt.contains("Current-speaker character context"))
    }

    @Test
    fun `non dialogue prompt excludes character context`() {
        val prompt = promptBuilder.buildSystemPrompt(
            context = PromptContext(
                isDialogue = false,
                characterContextPrompt = "MUST_NOT_APPEAR"
            )
        )

        assertFalse(prompt.contains("MUST_NOT_APPEAR"))
        assertFalse(prompt.contains("character context"))
    }

    @Test
    fun `English dialogue includes the bundled chirp guidance instead of Chinese guidance`() {
        val profile = loadBeniEnmaCharacterContextForTest()
        val context = promptBuilder.buildPromptContext(
            outputFormat = PromptOutputFormat.PLAIN_TEXT,
            sourceText = "我慢するのでち……！",
            targetLanguage = "en",
            currentSpeaker = "Beni-Enma (JP: 紅閻魔)",
            characterContextPrompt = profile.promptFor("en", isSakuraModel = false)
        )
        val prompt = promptBuilder.buildSystemPrompt(context)

        assertTrue(prompt.contains(profile.englishPrompt))
        assertTrue(prompt.contains("never names or choices"))
        assertTrue(prompt.contains("do not add tics absent from current JP"))
        assertFalse(prompt.contains("啾"))
    }

    @Test
    fun `English name and choice prompts exclude character context`() {
        val profile = loadBeniEnmaCharacterContextForTest()
        for (choiceBatch in listOf(false, true)) {
            val context = promptBuilder.buildPromptContext(
                outputFormat = if (choiceBatch) {
                    PromptOutputFormat.JSON_ARRAY
                } else {
                    PromptOutputFormat.PLAIN_TEXT
                },
                sourceText = if (choiceBatch) "行きましょう" else "紅閻魔",
                targetLanguage = "en",
                isDialogue = false,
                hasName = !choiceBatch,
                isChoiceBatch = choiceBatch,
                characterContextPrompt = profile.englishPrompt
            )

            assertFalse(promptBuilder.buildSystemPrompt(context).contains(profile.englishPrompt))
        }
    }

    @Test
    fun `English crop and battle prompts exclude character context`() {
        val profile = loadBeniEnmaCharacterContextForTest()
        for (battle in listOf(false, true)) {
            val context = promptBuilder.buildPromptContext(
                outputFormat = if (battle) {
                    PromptOutputFormat.PLAIN_TEXT
                } else {
                    PromptOutputFormat.JSON_ARRAY
                },
                sourceText = "我慢するのでち……！",
                targetLanguage = "en",
                isCropMode = !battle,
                characterContextPrompt = profile.englishPrompt,
                promptProfile = if (battle) {
                    TranslationPromptProfile.BATTLE_SUBTITLE
                } else {
                    TranslationPromptProfile.GENERAL
                }
            )

            assertFalse(promptBuilder.buildSystemPrompt(context).contains(profile.englishPrompt))
        }
    }
}
