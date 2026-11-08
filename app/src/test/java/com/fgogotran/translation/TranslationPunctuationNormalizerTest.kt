package com.fgogotran.translation

import com.fgogotran.text.EmphasisRange
import com.fgogotran.text.EmphasisText
import com.fgogotran.overlay.RubyRenderText
import com.fgogotran.ui.overlay.HistorySpeakerDialogueFormatter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class TranslationPunctuationNormalizerTest {
    private fun norm(text: String, locale: String = "zh-Hant", source: String? = null) =
        TranslationPunctuationNormalizer.normalize(text, locale, source)

    @Test fun minimumAndLongerPauses() {
        for (locale in listOf("zh-Hans", "zh-Hant")) {
            for (text in listOf("等等…!", "等等..!", "等等...!", "等等.....!", "等等......!"))
                assertEquals("等等……！", norm(text, locale))
            assertEquals("等等………！？", norm("等等.........!?", locale))
            assertEquals("……", norm("…", locale))
        }
        assertEquals("Wait...!", norm("Wait..!", "en"))
        assertEquals("Wait...!", norm("Wait……！", "en"))
        assertEquals("Wait......?", norm("Wait......?", "en"))
        assertEquals("Wait......?", norm("Wait...?", "en", "待って…………？"))
        assertEquals("等等…………？", norm("等等…?", source = "待って…………？"))
        assertEquals("Wait...!", norm("Wait!", "en", "待って……！"))
    }

    @Test fun leadingStandaloneAndIndependentPauses() {
        assertEquals("……\n真的嗎？", norm("真的嗎?", source = "……\n本当？"))
        assertEquals("......Wait...", norm("......Wait...", "en", "……待て……"))
        assertEquals("......Wait...", norm("...Wait...", "en", "…………待て……"))
        assertEquals("Wait......!", norm("Wait......!", "en", "待って！"))
        assertEquals("Wait......", norm("Wait......", "en", "待って。"))
        assertEquals("Wait!......", norm("Wait!......", "en", "待って！……"))
        assertEquals("", norm("", "en", "待って！"))
        // Two interior pauses cannot reliably be aligned merely by their indexes.
        assertEquals("A... B...", norm("A... B...", "en", "甲…………乙……"))
    }

    @Test fun dashesKeepMinimumAndSourceLength() {
        assertEquals("───等等", norm("--等等"))
        assertEquals("──────等等", norm("──等等", source = "──────待て"))
        assertEquals("——Wait", norm("—Wait", "en", "──────待て"))
        assertEquals("——Wait", norm("——Wait", "en"))
        assertEquals("—Wait", norm("--Wait", "en"))
        assertEquals("普通一個字ー仍保留", norm("普通一個字ー仍保留"))
    }

    @Test fun quotesFollowChinesePreferenceOnly() {
        val text = "«甲»「乙」《丙》『丁』“戊”"
        assertEquals("『甲』『乙』『丙』『丁』“戊”", norm(text))
        assertEquals(text, norm(text, "en"))
        assertEquals("『甲，『乙』。』", norm("「甲，«乙»。」"))
    }

    @Test fun malformedTailsAndEmotionalOrder() {
        assertEquals("你好。", norm("你好,。"))
        assertEquals("你好。", norm("你好，。"))
        assertEquals("『你好。』", norm("「你好,。」"))
        assertEquals("Hello.", norm("Hello,.", "en"))
        assertEquals("你好？！", norm("你好，？！"))
        assertEquals("Hello?!", norm("Hello,.?!", "en"))
        assertEquals("你好……！？", norm("你好...!?"))
        assertEquals("What!!!???", norm("What!!!???", "en"))
        assertEquals("走吧！？", norm("走吧?", source = "行こう！？"))
    }

    @Test fun rubyNotationAndTechnicalLiteralsAreOpaque() {
        val literals = listOf("<ruby...!?《讀音》>", "[ruby..,。«讀音»]", "〈ruby…!《讀音》〉", "<unfinished...!?", "[unfinished,。")
        for (literal in literals) for (locale in listOf("zh-Hans", "zh-Hant", "en"))
            assertEquals(literal, norm(literal, locale))
        val technical = "奧爾嘉瑪麗．亞寧姆斯菲亞 3.14 v2.4.0 well-known -12 --help https://example.com/a?x=1"
        assertEquals(technical, norm(technical))
        assertEquals("Dr.!", norm("Dr.", "en", "先生！"))
        assertEquals("U.S.!", norm("U.S.!", "en"))
        assertEquals("奧爾嘉瑪麗．亞寧姆斯菲亞。", norm("奧爾嘉瑪麗．亞寧姆斯菲亞."))
        assertEquals("『正文』〈《讀音》...!?〉……！", norm("「正文」〈《讀音》...!?〉...!"))
        assertEquals("[[«reading...»]]", norm("[[«reading...»]]"))
    }

    @Test fun emphasisAndRubyStillAttachToTheSameWords() {
        val text = "等等...，迦勒底〈Chaldea〉很好!"
        val start = text.indexOf("很好")
        val encoded = EmphasisText.encode(text, listOf(EmphasisRange(start, start + 2)))
        val normalized = norm(encoded)
        val decoded = EmphasisText.decode(normalized)
        assertEquals("很好", decoded.ranges.single().let { decoded.text.substring(it.start, it.endExclusive) })
        val parsed = RubyRenderText.parse(normalized)
        assertEquals("Chaldea", parsed.annotations.first { !it.emphasis }.reading)
        assertTrue(parsed.annotations.any { it.emphasis })
    }

    @Test fun repeatedApplicationDoesNotLengthenOrRemovePunctuation() {
        for (locale in listOf("zh-Hans", "zh-Hant", "en")) for (text in listOf(
            "等等.....!", "...!?", "──────走吧", "Hello……?", "『甲〈reading〉』,。", "«A» [a..!]", "Name．Name"
        )) {
            val once = norm(text, locale)
            assertEquals(once, norm(once, locale), "$locale $text")
        }
    }

    @Test fun sourceSignatureKeepsLengthsButIgnoresRubyAndWhitespace() {
        assertNotEquals(TranslationPunctuationNormalizer.sourceSignature("甲..."), TranslationPunctuationNormalizer.sourceSignature("甲......"))
        assertEquals(TranslationPunctuationNormalizer.sourceSignature("甲..."), TranslationPunctuationNormalizer.sourceSignature(" 甲〈...----〉... "))
    }

    @Test fun historyUsesTargetQuotesWithoutAddingADuplicatePair() {
        assertEquals("『", HistorySpeakerDialogueFormatter.prepare("『內容』", "zh-Hant").openingQuote)
        assertEquals("內容", HistorySpeakerDialogueFormatter.prepare("『內容』", "zh-Hant").body)
        assertEquals("甲『乙』", HistorySpeakerDialogueFormatter.prepare("『甲『乙』』", "zh-Hant").body)
        assertEquals("\"", HistorySpeakerDialogueFormatter.prepare("Hello...", "en").openingQuote)
        assertEquals("「", HistorySpeakerDialogueFormatter.prepare("原文").openingQuote)
    }
}
