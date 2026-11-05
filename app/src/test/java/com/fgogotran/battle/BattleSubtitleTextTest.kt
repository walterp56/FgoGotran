package com.fgogotran.battle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BattleSubtitleTextTest {
    private fun line(text: String, left: Float = 300f, top: Float = 104f, bottom: Float = 146f,
                     right: Float = 1_300f, confidence: Float = 0.95f) =
        BattleTextLine(text, left, top, right, bottom, confidence)

    @Test fun `accepts supplied single line dialogue including portrait cut-ins`() {
        listOf(
            "「じゃじゃーん、驚いた？」",
            "「よーしマスター、アタシにガンガン盛っちゃって！」",
            "「それは許可できない。無法にも限度はある」",
            "「言うまでもなく違法行為だ。真面目に、真剣に生きろ」",
            "「やっと来た！ さあ、ブッ殺しの時間だよ！」",
            "「まずは小手調べ♪」",
            "「フォウ？？」", "『……！！？？』", "“いいよ”"
        ).forEach { assertEquals(it, BattleSubtitleText.extract(listOf(line(it))), it) }
    }

    @Test fun `preserves two physical rows and original punctuation`() {
        val first = "「じゃ、キリエライト。お互いこれでスッキリだね。"
        val second = "殺し合おうなんて言わないよ。一方的にブチ殺すからさぁ！」"
        assertEquals("$first\n$second", BattleSubtitleText.extract(listOf(
            line(second), line(first, top = 20f, bottom = 62f)
        )))
    }

    @Test fun `same row OCR fragments are joined in horizontal order`() {
        assertEquals("「それは許可できない。無法にも限度はある」", BattleSubtitleText.extract(listOf(
            line("無法にも限度はある」", left = 700f, top = 106f),
            line("「それは許可できない。", left = 150f)
        )))
    }

    @Test fun `does not require OCR to detect both outer quotes`() {
        listOf(
            "それは許可できない。無法にも限度はある」",
            "「それは許可できない。",
            "それは許可できない。",
            "フォウ？？",
            "くそ……くそ……ちくしょう……!",
            "いいよ",
            "大丈夫",
            "フォウ"
        )
            .forEach { assertEquals(it, BattleSubtitleText.extract(listOf(line(it)))) }
    }

    @Test fun `quotes do not allow non-dialogue HUD text through`() {
        listOf("「NP 100%」", "「Buster」")
            .forEach { assertNull(BattleSubtitleText.extract(listOf(line(it))), it) }
    }

    @Test fun `retains small adjacent punctuation boxes but ignores unrelated icons`() {
        assertEquals("「フォウ？？」", BattleSubtitleText.extract(listOf(
            line("フォウ", left = 500f, right = 625f),
            line("「", left = 483f, right = 494f, top = 107f, bottom = 124f),
            line("？？」", left = 630f, right = 680f, top = 125f, bottom = 144f),
            line("!", left = 100f, right = 110f, top = 150f, bottom = 165f)
        )))
    }

    @Test fun `rejects common skill status damage and card labels`() {
        listOf("不屈の盾", "希望のカリスマ B Lv.10", "化身増殖", "憎悪駆動", "霊基変速・闘争純化",
            "カウンター発動", "宝具威力アップ", "NP 100%", "Buster", "1,234", "Sub Member")
            .forEach { assertNull(BattleSubtitleText.extract(listOf(line(it))), it) }
    }

    @Test fun `rejects low confidence wrong baseline and tiny icons`() {
        val text = "「大丈夫？」"
        assertNull(BattleSubtitleText.extract(listOf(line(text, confidence = 0.2f))))
        assertNull(BattleSubtitleText.extract(listOf(line(text, top = 10f, bottom = 52f))))
        assertNull(BattleSubtitleText.extract(listOf(line(text, top = 130f, bottom = 146f))))
    }

    @Test fun `selects the two baseline subtitle rows instead of rejecting an extra OCR row`() {
        assertEquals("二行目\n三行目", BattleSubtitleText.extract(listOf(
            line("一行目", top = 0f, bottom = 40f),
            line("二行目", top = 50f, bottom = 90f),
            line("三行目")
        )))
    }

    @Test fun `candidate carries aggregate OCR quality without changing its source`() {
        val candidate = assertNotNull(BattleSubtitleText.extractCandidate(listOf(
            line("「大丈夫", left = 500f, right = 680f, confidence = 0.8f),
            line("？」", left = 685f, right = 740f, confidence = 0.9f)
        )))
        assertEquals("「大丈夫？」", candidate.text)
        assertEquals(1, candidate.rowCount)
        assertTrue(candidate.confidence in 0.8f..0.9f)
        assertTrue(candidate.horizontalCoverage > 0f)
    }

    @Test fun `rejects empty and excessively long OCR output`() {
        assertNull(BattleSubtitleText.extract(emptyList()))
        val longText = "「" + "あ".repeat(240) + "」"
        assertNull(BattleSubtitleText.extract(listOf(line(longText))))
        assertNull(BattleSubtitleText.extract(listOf(line("「」"))))
    }

    @Test fun `low confidence subtitle is uncertain rather than evidence of absence`() {
        val lines = listOf(line("「大丈夫？」", confidence = 0.2f))
        assertNull(BattleSubtitleText.extract(lines))
        assertTrue(BattleSubtitleText.hasUncertainSubtitle(lines))
    }

    @Test fun `keeps expressive punctuation without relying on quotation marks`() {
        listOf("……", "！！？？", "『……！！？？』").forEach { text ->
            assertEquals(text, BattleSubtitleText.extract(listOf(line(text))), text)
        }
        assertNull(BattleSubtitleText.extract(listOf(line("「」"))))
        assertTrue(BattleSubtitleText.hasUncertainSubtitle(listOf(line("「」"))))
    }

    @Test fun `empty crop HUD labels icons and off baseline text do not count as uncertain subtitles`() {
        assertFalse(BattleSubtitleText.hasUncertainSubtitle(emptyList()))
        listOf("NP 100%", "Buster", "化身増殖", "1,234").forEach {
            assertFalse(BattleSubtitleText.hasUncertainSubtitle(listOf(line(it))), it)
        }
        assertFalse(BattleSubtitleText.hasUncertainSubtitle(listOf(line("「大丈夫？」", top = 10f, bottom = 52f))))
        assertFalse(BattleSubtitleText.hasUncertainSubtitle(listOf(line("!", top = 130f, bottom = 146f))))
    }

    @Test fun `repeated low confidence frames keep the event for later source confirmation`() {
        val tracker = BattleSubtitleTracker()
        val text = "「大丈夫？」"
        tracker.observe(text, 0)
        val event = tracker.observe(text, 120)!!
        listOf(300L, 900L, 1_500L).forEach { capturedAt ->
            val lines = listOf(line(text, confidence = 0.2f))
            val extracted = BattleSubtitleText.extract(lines)
            if (extracted == null && BattleSubtitleText.hasUncertainSubtitle(lines)) {
                tracker.observationUnavailable()
            } else {
                tracker.observe(extracted, capturedAt)
            }
            assertEquals(event, tracker.current)
            assertNull(tracker.ended)
        }
        assertNull(tracker.observe(text, 2_000))
        assertEquals(event, tracker.current)
    }
}
