package com.fgogotran.translation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The app-owned ruby representation is `base〈reading〉` (U+3008/U+3009).
 *
 * It must stay separate from the original FGO story text, which uses `《…》`
 * for name annotations such as `マシュ《ナレーター》`.
 * Those are real content and must never be stripped as ruby.
 */
class RubyMarkupRepresentationTest {
    private val promptBuilder = PromptBuilder()

    @Test
    fun `angle bracket ruby is detected and stripped`() {
        val source = "南瓜狼〈ブキンウルフ〉"

        assertTrue(TextNormalizer.hasRubyAnnotations(source))
        assertEquals("南瓜狼", TextNormalizer.stripRubyAnnotations(source))
    }

    @Test
    fun `original double angle brackets are not ruby`() {
        val nameAnnotation = "マシュ《ナレーター》"
        val titleQuote = "《新宿》の住人"

        assertFalse(TextNormalizer.hasRubyAnnotations(nameAnnotation))
        assertFalse(TextNormalizer.hasRubyAnnotations(titleQuote))
        assertEquals(nameAnnotation, TextNormalizer.stripRubyAnnotations(nameAnnotation))
        assertEquals(titleQuote, TextNormalizer.stripRubyAnnotations(titleQuote))
    }

    @Test
    fun `mixed content strips only the ruby pair`() {
        val source = "《巨英雄》こと〈メガロス〉"

        assertTrue(TextNormalizer.hasRubyAnnotations(source))
        assertEquals("《巨英雄》こと", TextNormalizer.stripRubyAnnotations(source))
    }

    @Test
    fun `prompt context flags ruby only for the app marker`() {
        val ruby = promptBuilder.buildPromptContext(
            outputFormat = PromptOutputFormat.PLAIN_TEXT,
            sourceText = "南瓜狼〈ブキンウルフ〉"
        )
        val original = promptBuilder.buildPromptContext(
            outputFormat = PromptOutputFormat.PLAIN_TEXT,
            sourceText = "マシュ《ナレーター》"
        )

        assertTrue(ruby.hasRuby)
        assertFalse(original.hasRuby)
    }

    @Test
    fun `general prompt documents the angle bracket ruby contract`() {
        val context = promptBuilder.buildPromptContext(
            outputFormat = PromptOutputFormat.PLAIN_TEXT,
            sourceText = "南瓜狼〈ブキンウルフ〉"
        )
        val prompt = promptBuilder.buildSystemPrompt(context)

        assertTrue(prompt.contains("base〈ruby〉"))
        assertTrue(prompt.contains("use 〈〉 only", ignoreCase = true))
        assertFalse(prompt.contains("《ruby》"))
    }

    @Test
    fun `punctuation pipeline keeps the ruby marker intact`() {
        val source = "南瓜狼〈ブキンウルフ〉"

        assertEquals(source, FgoDialogueSymbols.normalizeForRender(source))
        assertEquals(source, FgoDialogueSymbols.reconcileSourcePunctuation(source, source))
    }

}
