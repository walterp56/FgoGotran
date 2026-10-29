package com.fgogotran.translation

import com.fgogotran.terminology.CharacterNameEntity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class EnglishGlossaryPunctuationTest {
    @Test
    fun `dictionary content retains source emotion order and count in English width`() {
        val cases = listOf(
            Triple("ゴッフ！？", "Goff", "Goff!?"),
            Triple("ゴッフ？！", "Goff", "Goff?!"),
            Triple("マシュ！", "Mash", "Mash!"),
            Triple("マシュ！！？？", "Mash", "Mash!!??"),
            Triple("カルデア？", "Chaldea", "Chaldea?"),
            Triple("ゴッフ……", "Goff", "Goff……"),
            Triple("ゴッフ…", "Goff", "Goff…"),
            Triple("ゴッフ...", "Goff", "Goff..."),
            Triple("ゴッフ．．．", "Goff", "Goff..."),
            Triple("ゴッフ...!", "Goff", "Goff...!"),
            Triple("ゴッフ……！？", "Goff", "Goff……!?"),
            Triple("ゴッフ．．．！", "Goff", "Goff...!")
        )
        for (backend in listOf("character-db", "glossary")) {
            for ((source, translated, expected) in cases) {
                assertEquals(expected, result(translated, backend)
                    .withEnglishGlossaryPunctuation(source, "en").translatedText, "$backend: $source")
            }
        }
    }

    @Test
    fun `repeated finalization is idempotent and replaces expressive suffix atomically`() {
        for (translated in listOf("Goff", "Goff?", "Goff?!", "Goff...", "Goff!?")) {
            val once = result(translated).withEnglishGlossaryPunctuation("ゴッフ！？", "en")
            assertEquals("Goff!?", once.translatedText)
            assertSame(once, once.withEnglishGlossaryPunctuation("ゴッフ！？", "en"))
        }
    }

    @Test
    fun `dictionary spelling lexical punctuation and existing English wrappers stay intact`() {
        val cases = listOf(
            Triple("ウルズ！？", "Urðr", "Urðr!?"),
            Triple("ドクター・ロマン！", "Dr. Roman", "Dr. Roman!"),
            Triple("シービー！？", "C.B.", "C.B.!?"),
            Triple("オブライエン？", "O'Brien", "O'Brien?"),
            Triple("ゴッフ！？", "“Goff”", "“Goff!?”"),
            Triple("（ゴッフ！？）", "(Goff)", "(Goff!?)"),
            Triple("「ゴッフ！？」", "Goff", "Goff!?")
        )
        for ((source, translated, expected) in cases) {
            assertEquals(expected, result(translated)
                .withEnglishGlossaryPunctuation(source, "en").translatedText, source)
        }
    }

    @Test
    fun `plain labels weak punctuation malformed source and empty output are unchanged`() {
        for (source in listOf(null, "", "　", "！？", "ゴッフ", "ゴッフ。", "ゴッフ、", "ゴッフ．！", "ゴッフ！）")) {
            val original = result("Goff")
            assertSame(original, original.withEnglishGlossaryPunctuation(source, "en"), source)
        }
        val blank = result(" ")
        assertSame(blank, blank.withEnglishGlossaryPunctuation("ゴッフ！？", "en"))
    }

    @Test
    fun `API translation cache official memory and failures are never rewritten`() {
        for (backend in listOf("deepseek", "gpt", "gemini", "cache", "official-cn", "none")) {
            val original = result("Goff", backend)
            assertSame(original, original.withEnglishGlossaryPunctuation("ゴッフ！？", "en"), backend)
        }
        val failure = result("Translation failed").copy(isFailure = true)
        assertSame(failure, failure.withEnglishGlossaryPunctuation("ゴッフ！？", "en"))
    }

    @Test
    fun `Chinese targets retain their existing punctuation path and English locales are supported`() {
        for (target in listOf("zh-Hans", "zh-Hant", "zh-Hant-TW")) {
            val original = result("戈夫")
            assertSame(original, original.withEnglishGlossaryPunctuation("ゴッフ！？", target), target)
        }
        for (target in listOf("en", "en-US")) {
            assertEquals("Goff!?", result("Goff")
                .withEnglishGlossaryPunctuation("ゴッフ！？", target).translatedText)
        }
    }

    @Test
    fun `only the visible text changes while result provenance and usage metadata are preserved`() {
        val original = result("Goff").copy(trustedForContext = false, countsAsUsage = false,
            targetLocale = "en", dialogueSubtitleRows = listOf("unchanged"))
        assertEquals(original.copy(translatedText = "Goff!?"),
            original.withEnglishGlossaryPunctuation("ゴッフ！？", "en"))
    }

    @Test
    fun `visible name lookup stays bare while the same dictionary target can restore content emotion`() {
        val row = CharacterNameEntity(jpName = "ゴッフ", cnName = "戈夫", enName = "Goff", gender = "男性")
        val name = NameBoxLookupPolicy.findTranslation("ゴッフ", listOf(row), english = true)
        assertEquals("Goff", name)
        assertEquals("Goff!?", result(requireNotNull(name))
            .withEnglishGlossaryPunctuation("ゴッフ！？", "en").translatedText)
        assertEquals("Goff", row.enName)
        assertEquals("男性", row.gender)
    }

    private fun result(text: String, backend: String = "character-db") =
        TranslateResult(translatedText = text, backend = backend, cached = true)
}
