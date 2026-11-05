package com.fgogotran.ocr

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PaddleEdgePunctuationMergerTest {
    @Test
    fun `recovers longer leading dramatic pause without changing words`() {
        assertEquals(
            "…………救い出す事はできなかったか。",
            PaddleEdgePunctuationMerger.merge(
                tightText = "救い出す事はできなかったか。",
                paddedText = "…………救い出す事はできなかったか。"
            )
        )
        assertEquals(
            "..........救い出す事はできなかったか。",
            PaddleEdgePunctuationMerger.merge(
                tightText = "..救い出す事はできなかったか。",
                paddedText = "..........救い出す事はできなかったか。"
            )
        )
    }

    @Test
    fun `recovers leading and terminal dash runs`() {
        assertEquals(
            "───まだ終わっていない───",
            PaddleEdgePunctuationMerger.merge(
                tightText = "まだ終わっていない",
                paddedText = "───まだ終わっていない───"
            )
        )
    }

    @Test
    fun `recovers Japanese quotation marks without changing words`() {
        assertEquals(
            "『どちらを選んでもいい。",
            PaddleEdgePunctuationMerger.merge(
                tightText = "どちらを選んでもいい。",
                paddedText = "『どちらを選んでもいい。"
            )
        )
        assertEquals(
            "選んだ者が、その選択を正しくする』",
            PaddleEdgePunctuationMerger.merge(
                tightText = "選んだ者が、その選択を正しくする",
                paddedText = "選んだ者が、その選択を正しくする』"
            )
        )
        assertEquals(
            "「本当！？」",
            PaddleEdgePunctuationMerger.merge(
                tightText = "本当？",
                paddedText = "「本当！？」"
            )
        )
    }

    @Test
    fun `recovers straight and curly double quotation marks`() {
        assertEquals(
            "\"行こう。\"",
            PaddleEdgePunctuationMerger.merge(
                tightText = "行こう。",
                paddedText = "\"行こう。\""
            )
        )
        assertEquals(
            "“行こう。”",
            PaddleEdgePunctuationMerger.merge(
                tightText = "行こう。",
                paddedText = "“行こう。”"
            )
        )
    }

    @Test
    fun `recovers nested quotation edges in their original order`() {
        assertEquals(
            "『「本当！？」』",
            PaddleEdgePunctuationMerger.merge(
                tightText = "本当！？",
                paddedText = "『「本当！？」』"
            )
        )
    }

    @Test
    fun `recovers missing terminal punctuation clusters`() {
        assertEquals(
            "フォウ！！？？",
            PaddleEdgePunctuationMerger.merge(
                tightText = "フォウ？",
                paddedText = "フォウ！！？？"
            )
        )
        assertEquals(
            "そうか……………。",
            PaddleEdgePunctuationMerger.merge(
                tightText = "そうか。",
                paddedText = "そうか……………。"
            )
        )
    }

    @Test
    fun `never accepts a wider result that changes recognized words`() {
        assertEquals(
            "救い出す事はできなかったか。",
            PaddleEdgePunctuationMerger.merge(
                tightText = "救い出す事はできなかったか。",
                paddedText = "…………救い出す事ができなかったか。"
            )
        )
    }

    @Test
    fun `does not treat katakana prolonged sound marks as punctuation dashes`() {
        assertEquals(
            "キャア",
            PaddleEdgePunctuationMerger.merge(
                tightText = "キャア",
                paddedText = "キャアーーーーー"
            )
        )
    }

    @Test
    fun `keeps conflicting recognized terminal punctuation`() {
        assertEquals(
            "本当！",
            PaddleEdgePunctuationMerger.merge(
                tightText = "本当！",
                paddedText = "本当？"
            )
        )
        assertEquals(
            "「本当！」",
            PaddleEdgePunctuationMerger.merge(
                tightText = "「本当！」",
                paddedText = "『本当！』"
            )
        )
    }

    @Test
    fun `rejects quotation marks on the wrong edge`() {
        assertEquals(
            "本当",
            PaddleEdgePunctuationMerger.merge(
                tightText = "本当",
                paddedText = "本当『"
            )
        )
        assertEquals(
            "本当",
            PaddleEdgePunctuationMerger.merge(
                tightText = "本当",
                paddedText = "』本当"
            )
        )
    }

    @Test
    fun `recovers a paired opening quote after one ASCII OCR noise glyph`() {
        val candidate = PaddleEdgePunctuationMerger.findNoisyLeadingQuoteCandidate(
            tightText = "罪なき者はお帰りを。",
            paddedText = "V“罪なき者はお帰りを。"
        )

        assertNotNull(candidate)
        assertEquals('V', candidate.ignoredNoise)
        assertEquals('“', candidate.openingQuote)
        assertEquals("“罪なき者はお帰りを。", candidate.recoveredText)
        assertTrue(
            PaddleEdgePunctuationMerger.hasMatchingClosingQuote(
                candidate = candidate,
                laterTexts = listOf("ここは底なし、女王のお城”")
            )
        )
    }

    @Test
    fun `accepts paired noisy quote styles on the same or a later line`() {
        val corner = assertNotNull(
            PaddleEdgePunctuationMerger.findNoisyLeadingQuoteCandidate(
                tightText = "進みます。",
                paddedText = "B「進みます。"
            )
        )
        assertTrue(
            PaddleEdgePunctuationMerger.hasMatchingClosingQuote(
                candidate = corner,
                laterTexts = listOf("ここから先へ」")
            )
        )

        val japanese = assertNotNull(
            PaddleEdgePunctuationMerger.findNoisyLeadingQuoteCandidate(
                tightText = "選んでもいい。",
                paddedText = "7『選んでもいい。"
            )
        )
        assertTrue(
            PaddleEdgePunctuationMerger.hasMatchingClosingQuote(
                candidate = japanese,
                laterTexts = listOf("選んだ者が、その選択を正しくする』")
            )
        )

        val straight = assertNotNull(
            PaddleEdgePunctuationMerger.findNoisyLeadingQuoteCandidate(
                tightText = "進む\"",
                paddedText = "A\"進む\""
            )
        )
        assertTrue(
            PaddleEdgePunctuationMerger.hasMatchingClosingQuote(
                candidate = straight,
                laterTexts = emptyList()
            )
        )
    }

    @Test
    fun `rejects unsafe noisy quote candidates`() {
        assertNull(
            PaddleEdgePunctuationMerger.findNoisyLeadingQuoteCandidate(
                tightText = "罪なき者はお帰りを。",
                paddedText = "VV“罪なき者はお帰りを。"
            )
        )
        assertNull(
            PaddleEdgePunctuationMerger.findNoisyLeadingQuoteCandidate(
                tightText = "罪なき者はお帰りを。",
                paddedText = "業“罪なき者はお帰りを。"
            )
        )
        assertNull(
            PaddleEdgePunctuationMerger.findNoisyLeadingQuoteCandidate(
                tightText = "罪なき者はお帰りを。",
                paddedText = "V“罪ある者はお帰りを。"
            )
        )
        assertNull(
            PaddleEdgePunctuationMerger.findNoisyLeadingQuoteCandidate(
                tightText = "罪なき者はお帰りを。",
                paddedText = "V”罪なき者はお帰りを。"
            )
        )
    }

    @Test
    fun `does not confirm a noisy quote without its matching closer`() {
        val candidate = assertNotNull(
            PaddleEdgePunctuationMerger.findNoisyLeadingQuoteCandidate(
                tightText = "罪なき者はお帰りを。",
                paddedText = "V“罪なき者はお帰りを。"
            )
        )

        assertFalse(
            PaddleEdgePunctuationMerger.hasMatchingClosingQuote(
                candidate = candidate,
                laterTexts = listOf("ここは底なし、女王のお城", "別の『引用』")
            )
        )
    }

    @Test
    fun `attaches a detached opening quote to the first multiline CJK row`() {
        val merged = PaddleEdgePunctuationMerger.mergeDetachedFragments(
            listOf(
                line(0, "“", 0, 2, 8, 20),
                line(1, "罪なき者はお帰りを。", 12, 0, 230, 34),
                line(2, "ここは底なし、女王のお城”", 12, 42, 250, 76)
            )
        )

        assertEquals(2, merged.size)
        assertEquals("“罪なき者はお帰りを。", merged[0].text)
        assertEquals("ここは底なし、女王のお城”", merged[1].text)
        assertEquals(1, merged[0].sourceIndex)
    }

    @Test
    fun `attaches detached quotes around a choice without moving its geometry`() {
        val merged = PaddleEdgePunctuationMerger.mergeDetachedFragments(
            listOf(
                line(0, "「", 10, 102, 18, 122),
                line(1, "進む", 22, 100, 90, 130),
                line(2, "」", 94, 102, 102, 122)
            )
        )

        assertEquals(1, merged.size)
        assertEquals("「進む」", merged.single().text)
        assertEquals(22, merged.single().left)
        assertEquals(90, merged.single().right)
    }

    @Test
    fun `does not attach distant or different-row punctuation fragments`() {
        val input = listOf(
            line(0, "“", 0, 0, 8, 18),
            line(1, "本当", 200, 0, 280, 32),
            line(2, "』", 282, 100, 290, 118)
        )

        assertEquals(input, PaddleEdgePunctuationMerger.mergeDetachedFragments(input))
    }

    @Test
    fun `deduplicates a detached quote already recovered inside the main line`() {
        val merged = PaddleEdgePunctuationMerger.mergeDetachedFragments(
            listOf(
                line(0, "“", 0, 2, 8, 20),
                line(1, "“罪なき者はお帰りを。", 12, 0, 230, 34),
                line(2, "ここは底なし、女王のお城”", 12, 42, 250, 76),
                line(3, "”", 254, 46, 262, 66)
            )
        )

        assertEquals(2, merged.size)
        assertEquals("“罪なき者はお帰りを。", merged[0].text)
        assertEquals("ここは底なし、女王のお城”", merged[1].text)
    }

    private fun line(
        sourceIndex: Int,
        text: String,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int
    ): PaddleEdgePunctuationMerger.PositionedLine {
        return PaddleEdgePunctuationMerger.PositionedLine(
            sourceIndex = sourceIndex,
            text = text,
            left = left,
            top = top,
            right = right,
            bottom = bottom
        )
    }
}
