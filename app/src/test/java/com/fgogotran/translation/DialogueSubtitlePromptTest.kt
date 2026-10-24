package com.fgogotran.translation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DialogueSubtitlePromptTest {
    private val builder = PromptBuilder()

    @Test fun `unnamed character speech is not classified as narration in any target`() {
        for (target in listOf("zh-Hans", "zh-Hant", "en")) {
            val context = builder.buildPromptContext(PromptOutputFormat.PLAIN_TEXT, "私はククルカン。",
                targetLanguage = target, requestVoiceHint = true,
                promptProfile = TranslationPromptProfile.DIALOGUE_SUBTITLE)
            val prompt = builder.buildSystemPrompt(context)
            assertTrue(prompt.contains("These are spoken character lines"))
            assertTrue(prompt.contains("do not guess the speaker"))
            assertFalse(prompt.contains("No speaker detected"))
            assertFalse(prompt.contains("short and punchy"))
            assertFalse(prompt.contains("battle subtitles"))
            assertFalse(context.requestVoiceHint)
            assertFalse(context.isUnattributedDialogue)
            assertTrue(prompt.contains("Use glossary targets exactly"))
        }
    }

    @Test fun `conditional ruby and grammar rules remain available without battle restrictions`() {
        val context = builder.buildPromptContext(PromptOutputFormat.PLAIN_TEXT, "全て遠き理想郷〈アヴァロン〉を守る。",
            forceRuby = true, promptProfile = TranslationPromptProfile.DIALOGUE_SUBTITLE)
        assertTrue(context.hasRuby)
        assertTrue(builder.buildSystemPrompt(context).contains("base〈ruby〉"))
    }

    @Test fun `source context is short reference only and independent of translation completion`() {
        val references = prepareDialogueSubtitleReferences(listOf("old", "私はククルカン。", "なんだっけ？"))
        assertEquals(listOf("私はククルカン。", "なんだっけ？"), references)
        assertEquals("", buildDialogueSubtitleReferencePrompt(emptyList()))
        assertTrue(buildDialogueSubtitleReferencePrompt(references).contains("translate only the current Japanese"))
        assertEquals(listOf("short"), prepareDialogueSubtitleReferences(listOf("長".repeat(400), "short")))
    }

    @Test fun `spoken cache namespace is independent of battle and general in each target`() {
        for (target in listOf("zh-Hans", "zh-Hant", "en")) {
            val version = PromptBuilder.dialogueSubtitlePromptVersion(target)
            assertNotEquals(PromptBuilder.promptVersionFor(target, false), version)
            assertNotEquals(PromptBuilder.promptVersionFor(target, true), version)
        }
    }
}
