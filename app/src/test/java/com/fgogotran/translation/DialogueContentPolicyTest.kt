package com.fgogotran.translation

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DialogueContentPolicyTest {
    @Test
    fun `punctuation-only dialogue is displayable but needs no word translation`() {
        listOf("……", "……。", "───", "……\n───", "！？", "「……」", "『───』").forEach { text ->
            assertTrue(TextNormalizer.isPunctuationOnlyDialogue(text), text)
            assertTrue(TextNormalizer.hasDialogueContent(text), text)
            assertFalse(TextNormalizer.hasTranslatableContent(text), text)
        }
    }

    @Test
    fun `blank or arbitrary symbols do not become dialogue`() {
        listOf("", " \n ", "@", "©", "☆", "🙂").forEach { text ->
            assertFalse(TextNormalizer.isPunctuationOnlyDialogue(text), text)
            assertFalse(TextNormalizer.hasDialogueContent(text), text)
        }
    }

    @Test
    fun `normal dialogue and Japanese dash-like letters remain translatable`() {
        listOf("一", "ー", "コーヒー", "……本文───", "こんにちは！").forEach { text ->
            assertFalse(TextNormalizer.isPunctuationOnlyDialogue(text), text)
            assertTrue(TextNormalizer.hasTranslatableContent(text), text)
            assertTrue(TextNormalizer.hasDialogueContent(text), text)
        }
    }
}
