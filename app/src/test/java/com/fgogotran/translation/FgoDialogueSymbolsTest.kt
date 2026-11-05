package com.fgogotran.translation

import kotlin.test.Test
import kotlin.test.assertEquals

class FgoDialogueSymbolsTest {
    @Test
    fun `render pause normalization keeps expressive length`() {
        assertEquals("等等……", FgoDialogueSymbols.normalizeForRender("等等..."))
        assertEquals("等等…", FgoDialogueSymbols.normalizeForRender("等等…"))
        assertEquals("等等…………", FgoDialogueSymbols.normalizeForRender("等等......"))
        assertEquals("等等..........", FgoDialogueSymbols.normalizeForRender("等等.........."))
        assertEquals("等等………………", FgoDialogueSymbols.normalizeForRender("等等………………"))
        assertEquals("One, two...", FgoDialogueSymbols.normalizeForRender("One, two..."))
    }

    @Test
    fun normalizesLeadingOcrHyphenBeforeJapaneseOrMask() {
        assertEquals("───あの■■■", FgoDialogueSymbols.normalizeLeadingOcrDash("-あの■■■"))
        assertEquals("「───どうして", FgoDialogueSymbols.normalizeLeadingOcrDash("「－どうして"))
        assertEquals("───■■■", FgoDialogueSymbols.normalizeLeadingOcrDash("-■■■"))
    }

    @Test
    fun preservesNonDialogueHyphensAndJapaneseLongVowelMark() {
        assertEquals("-10", FgoDialogueSymbols.normalizeLeadingOcrDash("-10"))
        assertEquals("A-B", FgoDialogueSymbols.normalizeLeadingOcrDash("A-B"))
        assertEquals("メール", FgoDialogueSymbols.normalizeLeadingOcrDash("メール"))
        assertEquals("ーそうなの？", FgoDialogueSymbols.normalizeLeadingOcrDash("ーそうなの？"))
    }

    @Test
    fun `dash normalization never changes lexical long vowel or ascii tokens`() {
        assertEquals("等一下───", FgoDialogueSymbols.normalizeDashRuns("等一下---"))
        assertEquals("等一下───", FgoDialogueSymbols.normalizeDashRuns("等一下―――"))
        assertEquals("等一下───", FgoDialogueSymbols.normalizeDashRuns("等一下———"))
        assertEquals("キャアーーーーー！", FgoDialogueSymbols.normalizeDashRuns("キャアーーーーー！"))
        assertEquals(
            "意味が分からないんですけどーーー！？",
            FgoDialogueSymbols.normalizeDashRuns("意味が分からないんですけどーーー！？")
        )
        assertEquals("A--B", FgoDialogueSymbols.normalizeDashRuns("A--B"))
        assertEquals("--help", FgoDialogueSymbols.normalizeDashRuns("--help"))
        assertEquals("https://x/a--b", FgoDialogueSymbols.normalizeDashRuns("https://x/a--b"))
        assertEquals("第一一章", FgoDialogueSymbols.normalizeDashRuns("第一一章"))
    }

    @Test
    fun `render normalization is idempotent for canonical fgo punctuation`() {
        val canonical = "「玛修（小声）……！！？？」"
        assertEquals(canonical, FgoDialogueSymbols.normalizeForRender(canonical))
        assertEquals(
            canonical,
            FgoDialogueSymbols.normalizeForRender(FgoDialogueSymbols.normalizeForRender(canonical))
        )
    }
}
