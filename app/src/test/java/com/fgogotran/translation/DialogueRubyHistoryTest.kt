package com.fgogotran.translation

import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Test

class DialogueRubyHistoryTest {
    @Before fun before() = SessionTranslationHistory.clear()
    @After fun after() = SessionTranslationHistory.clear()

    private fun entry(reading: String = "マスター"): SessionTranslationEntry {
        val source = "おかあさん〈$reading〉、いい？"
        return SessionTranslationEntry(
            dialogueText = "媽媽〈御主〉，可以嗎？", originalDialogueText = source,
            contextSourceDialogue = source, contextTranslatedDialogue = "媽媽〈御主〉，可以嗎？",
            sourceKey = "scene:$source", dialogueSourceKey = "dialogue:$source")
    }

    @Test fun historyRetainsInlineRubyAndBilingualContextUsesFinalTranslation() {
        val original = entry()
        SessionTranslationHistory.add(original)
        assertEquals(original.originalDialogueText, SessionTranslationHistory.entries.value.single().originalDialogueText)
        val context = SessionTranslationHistory.lastSceneDialogueContexts().single()
        assertEquals("おかあさん〈マスター〉、いい？", context.sourceDialogue)
        assertEquals("媽媽〈御主〉，可以嗎？", context.translatedDialogue)
        val prompt = buildPreviousSceneBilingualContextPrompt(listOf(context))
        assertTrue(prompt.contains(context.sourceDialogue))
        assertTrue(prompt.contains(context.translatedDialogue!!))
    }

    @Test fun differentInlineRubyDoesNotGetSuppressedWithChoiceDialogue() {
        SessionTranslationHistory.add(entry())
        val different = entry("わたくし").copy(choices = listOf("好的。"))
        SessionTranslationHistory.add(different)
        assertEquals(2, SessionTranslationHistory.entries.value.size)
        assertEquals("媽媽〈御主〉，可以嗎？", SessionTranslationHistory.entries.value.last().dialogueText)
        assertEquals(different.originalDialogueText, SessionTranslationHistory.entries.value.last().originalDialogueText)
    }

    @Test fun repeatedChoiceDialogueClearsItsSourceAndContextWithItsDialogue() {
        val first = entry().copy(generalPreparedSourceDialogue = "おかあさん、いい？")
        SessionTranslationHistory.add(first)
        SessionTranslationHistory.add(first.copy(choices = listOf("好的。"), sourceKey = "choice"))
        val last = SessionTranslationHistory.entries.value.last()
        assertEquals(listOf("好的。"), last.choices)
        assertNull(last.dialogueText)
        assertNull(last.originalDialogueText)
        assertNull(last.contextSourceDialogue)
        assertNull(last.contextTranslatedDialogue)
        assertNull(last.generalPreparedSourceDialogue)
    }
}
