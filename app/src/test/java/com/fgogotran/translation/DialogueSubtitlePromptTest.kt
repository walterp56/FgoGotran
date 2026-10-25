package com.fgogotran.translation

import java.io.File
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
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
            assertTrue(version.contains("v3-source-repetition"))
        }
    }

    @Test fun `wrapped spoken request translates connected speech with an ordered row contract`() {
        val rows = listOf("これからたくさん、貴方たちの文化を学ばせて", "いただきます♡")
        val request = builder.buildDialogueSubtitleRowsPrompt(rows)
        assertTrue(rows.all(request::contains))
        assertTrue(request.contains("connected speech, not separate sentences"))
        assertTrue(request.contains("exactly 2 nonempty translation strings"))
        assertTrue(request.contains("one corresponding fragment per row"))
        assertTrue(request.contains("including repetition present in the source"))
        assertTrue(request.contains("Do not introduce duplicate content."))
        assertFalse(request.contains("do not repeat overlapping meaning"))
        assertFalse(request.contains("concise"))
        for (target in listOf("zh-Hans", "zh-Hant", "en")) {
            val context = builder.buildPromptContext(PromptOutputFormat.JSON_ARRAY, rows.joinToString("\n"),
                targetLanguage = target, promptProfile = TranslationPromptProfile.DIALOGUE_SUBTITLE)
            val system = builder.buildSystemPrompt(context)
            assertTrue(system.contains("JSON array"))
            assertTrue(system.contains("These are spoken character lines"))
            assertFalse(system.contains("No speaker detected"))
        }
    }

    @Test fun `canonical row output survives cache and rejects missing or invented alignment`() {
        for (rows in listOf(listOf("學習你們的文化", "今後請多指教♡"),
            listOf("学习你们的文化", "今后请多指教♡"), listOf("I will learn about", "your culture♡"))) {
            val json = Json.parseToJsonElement(rows.joinToString(prefix = "[\"", postfix = "\"]", separator = "\",\""))
            val parsed = GeneralTranslationResponseContract.parseStrings(json, 2)
            assertEquals(rows, dialogueSubtitleTranslatedRows(parsed.joinToString("\n"), 2))
            assertEquals(rows, dialogueSubtitleTranslatedRows(parsed.joinToString("\r\n"), 2))
        }
        assertNull(dialogueSubtitleTranslatedRows("One whole translated sentence", 2))
        assertNull(dialogueSubtitleTranslatedRows("one\n\nthree", 3))
        assertNull(dialogueSubtitleTranslatedRows("one\ntwo\nthree", 2))
        assertNull(dialogueSubtitleTranslatedRows("", 1))
        assertEquals(listOf("one wrapped sentence"), dialogueSubtitleTranslatedRows("one\nwrapped sentence", 1))
        assertFailsWith<IllegalArgumentException> {
            GeneralTranslationResponseContract.parseStrings(Json.parseToJsonElement("[\"one\"]"), 2)
        }
    }

    @Test fun `row alignment applies to prompt retry cache and target output without enabling Crop`() {
        val translator = listOf(File("src/main/java/com/fgogotran/translation/Translator.kt"),
            File("app/src/main/java/com/fgogotran/translation/Translator.kt")).first(File::isFile).readText()
        assertTrue(translator.contains("subtitleRowCount = alignedSubtitleRows"))
        assertTrue(translator.contains("sanitizeDialogueSubtitleCacheRows(sourceText, cachedText"))
        assertTrue(translator.contains("sanitizeDialogueSubtitleCacheRows(normalizedText, translatedText"))
        assertTrue(translator.contains(".forTargetLocale(config, punctuationSourceText).withDialogueSubtitleRows(subtitleRowCount)"))
        assertTrue(translator.contains("else if (alignedSubtitleRows == 0)"))
    }
}
