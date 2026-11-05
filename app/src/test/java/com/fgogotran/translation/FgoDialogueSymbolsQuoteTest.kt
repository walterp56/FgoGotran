package com.fgogotran.translation

import kotlin.test.Test
import kotlin.test.assertEquals

class FgoDialogueSymbolsQuoteTest {
    @Test
    fun `normalizes every japanese speech quote in final chinese text`() {
        assertEquals(
            "『前半』『後半』",
            FgoDialogueSymbols.normalizeFinalChineseQuotes("「前半」「後半」")
        )
    }

    @Test
    fun `preserves content punctuation whitespace and line breaks`() {
        assertEquals(
            "第一行：『等等……！』\n第二行",
            FgoDialogueSymbols.normalizeFinalChineseQuotes("第一行：「等等……！」\n第二行")
        )
    }

    @Test
    fun `normalizes unmatched quote characters independently`() {
        assertEquals("他說『等等", FgoDialogueSymbols.normalizeFinalChineseQuotes("他說「等等"))
        assertEquals("就是這樣』", FgoDialogueSymbols.normalizeFinalChineseQuotes("就是這樣」"))
    }

    @Test
    fun `leaves existing inner quotes and ordinary text unchanged`() {
        assertEquals("『內心話』", FgoDialogueSymbols.normalizeFinalChineseQuotes("『內心話』"))
        assertEquals("接受邀請", FgoDialogueSymbols.normalizeFinalChineseQuotes("接受邀請"))
    }
}
