package com.fgogotran.ocr

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChoicePunctuationRecoveryTest {
    @Test
    fun `recovers coloured punctuation with the same row geometry`() {
        FgoStoryTextPalette.samplesFor(FgoStoryTextPalette.Scope.CHOICE).forEach { sample ->
            val fixture = Fixture(ink = sample.renderColor)
            fixture.drawTextBlock(60, 32, 190, 72)
            fixture.drawDots(startX = 208, centerY = 52)
            assertEquals("進む……", fixture.recover(line("進む", 60, 32, 190, 72)).lines.single().text)

            val dashFixture = Fixture(ink = sample.renderColor)
            dashFixture.drawDash(36, 52, 90, 5)
            dashFixture.drawTextBlock(148, 32, 300, 72)
            assertEquals("───進む", dashFixture.recover(line("進む", 148, 32, 300, 72)).lines.single().text)
        }
    }

    @Test
    fun `coloured ruby dots are not promoted to main text punctuation`() {
        FgoStoryTextPalette.samplesFor(FgoStoryTextPalette.Scope.CHOICE).forEach { sample ->
            val fixture = Fixture(ink = sample.renderColor)
            fixture.drawDots(startX = 135, centerY = 21)
            fixture.drawTextBlock(72, 45, 245, 83)
            val result = fixture.recover(
                line("バンプキンウルフ", 116, 10, 230, 28, sourceIndex = 0),
                line("南瓜狼", 72, 45, 245, 83, sourceIndex = 1)
            )
            assertEquals(0, result.recoveredCount)
            assertEquals(listOf("バンプキンウルフ", "南瓜狼"), result.lines.map { it.text })
        }
    }

    @Test
    fun `unsupported choice colours do not invent punctuation`() {
        FgoStoryTextPalette.samples.drop(2).forEach { sample ->
            val fixture = Fixture(ink = sample.renderColor)
            fixture.drawTextBlock(60, 32, 190, 72)
            fixture.drawDots(startX = 208, centerY = 52)
            val result = fixture.recover(line("進む", 60, 32, 190, 72))
            assertEquals(0, result.recoveredCount)
            assertEquals("進む", result.lines.single().text)

            val dash = Fixture(ink = sample.renderColor)
            dash.drawDash(36, 52, 90, 5)
            dash.drawTextBlock(148, 32, 300, 72)
            val dashResult = dash.recover(line("進む", 148, 32, 300, 72))
            assertEquals(0, dashResult.recoveredCount)
            assertEquals("進む", dashResult.lines.single().text)
        }
    }

    @Test
    fun `recovers trailing ellipsis without another OCR pass`() {
        val fixture = Fixture()
        fixture.drawTextBlock(60, 32, 190, 72)
        fixture.drawDots(startX = 208, centerY = 52)

        val result = fixture.recover(
            line("進む", 60, 32, 190, 72)
        )

        assertEquals(1, result.recoveredCount)
        assertEquals("進む……", result.lines.single().text)
    }

    @Test
    fun `recovers leading long dash`() {
        val fixture = Fixture()
        fixture.drawDash(36, 52, 90, 5)
        fixture.drawTextBlock(148, 32, 300, 72)

        val result = fixture.recover(
            line("進む", 148, 32, 300, 72)
        )

        assertEquals(1, result.recoveredCount)
        assertEquals("───進む", result.lines.single().text)
    }

    @Test
    fun `replaces a dash-only OCR confusable`() {
        val fixture = Fixture()
        fixture.drawDash(36, 52, 126, 5)

        val result = fixture.recover(
            line("一", 36, 50, 162, 55)
        )

        assertEquals(1, result.recoveredCount)
        assertEquals("───", result.lines.single().text)
    }

    @Test
    fun `places an internal ellipsis by visual position`() {
        val fixture = Fixture()
        fixture.drawTextBlock(40, 31, 145, 74)
        fixture.drawDots(startX = 160, centerY = 52)
        fixture.drawTextBlock(215, 31, 330, 74)

        val result = fixture.recover(
            line("前半後半", 40, 31, 330, 74).copy(tokens = listOf(
                DialoguePunctuationRecovery.Token("前", 60f), DialoguePunctuationRecovery.Token("半", 110f),
                DialoguePunctuationRecovery.Token("後", 250f), DialoguePunctuationRecovery.Token("半", 300f)
            ))
        )

        assertEquals(1, result.recoveredCount)
        assertEquals("前半……後半", result.lines.single().text)
    }

    @Test
    fun `ruby row is preserved and cannot become main punctuation`() {
        val fixture = Fixture()
        fixture.drawDots(startX = 135, centerY = 21)
        fixture.drawTextBlock(72, 45, 245, 83)

        val result = fixture.recover(
            line("バンプキンウルフ", 116, 10, 230, 28, sourceIndex = 0),
            line("南瓜狼", 72, 45, 245, 83, sourceIndex = 1)
        )

        assertEquals(0, result.recoveredCount)
        assertEquals(listOf("バンプキンウルフ", "南瓜狼"), result.lines.map { it.text })
    }

    @Test
    fun `recovers main ellipsis while keeping ruby separate`() {
        val fixture = Fixture()
        fixture.drawTextBlock(72, 45, 245, 83)
        fixture.drawDots(startX = 266, centerY = 64)

        val result = fixture.recover(
            line("バンプキンウルフ", 116, 10, 230, 28, sourceIndex = 0),
            line("南瓜狼", 72, 45, 245, 83, sourceIndex = 1)
        )

        assertEquals(1, result.recoveredCount)
        assertEquals("バンプキンウルフ", result.lines.first { it.sourceIndex == 0 }.text)
        assertEquals("南瓜狼……", result.lines.first { it.sourceIndex == 1 }.text)
    }

    @Test
    fun `recovers punctuation-only choice`() {
        val fixture = Fixture()
        fixture.drawDots(startX = 164, centerY = 50)

        val result = fixture.recover()

        assertEquals(1, result.recoveredCount)
        assertEquals("……", result.lines.single().text)
    }

    @Test
    fun `normalizes weak punctuation-only OCR without duplicating the row`() {
        val fixture = Fixture()
        fixture.drawDots(startX = 164, centerY = 50)

        val result = fixture.recover(
            line(".", 164, 48, 208, 52)
        )

        assertEquals(1, result.recoveredCount)
        assertEquals("……", result.lines.single().text)
    }

    @Test
    fun `upgrades one recognized ellipsis without duplicating it`() {
        val fixture = Fixture()
        fixture.drawTextBlock(60, 32, 190, 72)
        fixture.drawDots(startX = 208, centerY = 52)

        val result = fixture.recover(
            line("進む…", 60, 32, 255, 72)
        )

        assertEquals(1, result.recoveredCount)
        assertEquals("進む……", result.lines.single().text)
    }

    @Test
    fun `punctuation-only recovery preserves surrounding quotes and final period`() {
        val fixture = Fixture()
        fixture.drawDots(startX = 164, centerY = 50)
        val original = line("「…。」", 148, 32, 234, 72)
        val result = fixture.recover(original)
        assertEquals("「……。」", result.lines.single().text)
        assertEquals(original.bounds, result.lines.single().bounds)
    }

    @Test
    fun `split recognized quote fragments are kept with the recovered pause as one row`() {
        val fixture = Fixture()
        fixture.drawDots(startX = 164, centerY = 50)
        val result = fixture.recover(
            line("「", 148, 32, 158, 72, sourceIndex = 0),
            line("…", 164, 48, 208, 52, sourceIndex = 1),
            line("」", 222, 32, 234, 72, sourceIndex = 2)
        )
        assertEquals("「……」", result.lines.single().text)
    }

    @Test
    fun `ordinary short horizontal stroke is not promoted to a dash run`() {
        val fixture = Fixture()
        fixture.drawTextBlock(60, 32, 190, 72)
        fixture.drawDash(208, 52, 42, 5)

        val result = fixture.recover(
            line("一つ", 60, 32, 250, 72)
        )

        assertEquals(0, result.recoveredCount)
        assertEquals("一つ", result.lines.single().text)
    }

    @Test
    fun `three dots are one ellipsis and four uncertain dots are left alone`() {
        val three = Fixture()
        three.drawTextBlock(60, 32, 190, 72)
        three.drawDots(208, 52, count = 3)
        assertEquals("進む…", three.recover(line("進む", 60, 32, 190, 72)).lines.single().text)
        val four = Fixture()
        four.drawTextBlock(60, 32, 190, 72)
        four.drawDots(208, 52, count = 4)
        assertEquals("進む", four.recover(line("進む", 60, 32, 190, 72)).lines.single().text)
    }

    @Test
    fun `punctuation-only three dots survive without detected words`() {
        val fixture = Fixture()
        fixture.drawDots(164, 50, count = 3)
        assertEquals("…", fixture.recover().lines.single().text)
    }

    @Test
    fun `dash wider than six character heights is still recoverable`() {
        val fixture = Fixture(width = 800)
        fixture.drawTextBlock(30, 32, 160, 72)
        fixture.drawDash(175, 52, 400, 5)
        assertEquals("進む───", fixture.recover(line("進む", 30, 32, 160, 72)).lines.single().text)
    }

    @Test
    fun `internal insertion without matching character positions is unresolved rather than guessed`() {
        val fixture = Fixture()
        fixture.drawTextBlock(40, 31, 145, 74)
        fixture.drawDots(160, 52)
        fixture.drawTextBlock(215, 31, 330, 74)
        val result = fixture.recover(line("前半後半", 40, 31, 330, 74))
        assertEquals("前半後半", result.lines.single().text)
        assertEquals(setOf(0), result.unresolvedSourceIndices)
    }

    @Test
    fun `unequal glyph widths use actual positions and not equal cells`() {
        val fixture = Fixture()
        fixture.drawTextBlock(40, 31, 95, 74)
        fixture.drawDots(112, 52)
        fixture.drawTextBlock(167, 31, 350, 74)
        val result = fixture.recover(line("前Wiii後", 40, 31, 350, 74).copy(tokens = listOf(
            DialoguePunctuationRecovery.Token("前", 65f), DialoguePunctuationRecovery.Token("W", 190f),
            DialoguePunctuationRecovery.Token("i", 230f), DialoguePunctuationRecovery.Token("i", 245f),
            DialoguePunctuationRecovery.Token("i", 260f), DialoguePunctuationRecovery.Token("後", 325f)
        )))
        assertEquals("前……Wiii後", result.lines.single().text)
    }

    @Test
    fun `stale positions cannot split a recognized word`() {
        val fixture = Fixture()
        fixture.drawTextBlock(40, 31, 145, 74)
        fixture.drawDots(160, 52)
        fixture.drawTextBlock(215, 31, 330, 74)
        val result = fixture.recover(line("前半後半", 40, 31, 330, 74).copy(tokens = listOf(
            DialoguePunctuationRecovery.Token("違う", 70f), DialoguePunctuationRecovery.Token("後半", 280f)
        )))
        assertEquals("前半後半", result.lines.single().text)
        assertEquals(setOf(0), result.unresolvedSourceIndices)
    }

    @Test
    fun `punctuation between two main fragments produces one logical choice row`() {
        val fixture = Fixture()
        fixture.drawTextBlock(40, 31, 145, 74)
        fixture.drawDots(160, 52)
        fixture.drawTextBlock(215, 31, 330, 74)
        val result = fixture.recover(
            line("前半", 40, 31, 145, 74, 0).copy(tokens = listOf(
                DialoguePunctuationRecovery.Token("前", 60f), DialoguePunctuationRecovery.Token("半", 110f))),
            line("後半", 215, 31, 330, 74, 1).copy(tokens = listOf(
                DialoguePunctuationRecovery.Token("後", 250f), DialoguePunctuationRecovery.Token("半", 300f)))
        )
        assertEquals(listOf("前半……後半"), result.lines.map { it.text })
    }

    @Test
    fun `missing main OCR does not promote upper ruby dots to choice punctuation`() {
        val fixture = Fixture()
        fixture.drawDots(135, 21)
        fixture.drawTextBlock(72, 45, 245, 83)
        val result = fixture.recover(line("マスター", 116, 10, 230, 28))
        assertEquals(listOf("マスター"), result.lines.map { it.text })
        assertEquals(0, result.recoveredCount)
    }

    @Test
    fun `hollow final period is retained with a punctuation-only pause without duplication`() {
        val fixture = Fixture()
        fixture.drawDots(164, 50)
        fixture.drawRing(215, 55, 8)
        val result = fixture.recover(line("……。", 164, 48, 223, 63))
        assertEquals(listOf("……。"), result.lines.map { it.text })
    }

    @Test
    fun `only clear emphasis dots are removed while nearby ruby and dakuten are protected`() {
        val emphasis = Fixture()
        emphasis.drawTextBlock(50, 45, 250, 85)
        emphasis.drawDots(70, 35, count = 4, gap = 40)
        assertEquals(4, ChoicePunctuationRecovery.emphasisDots(emphasis.analysis()).size)
        val ruby = Fixture()
        ruby.drawTextBlock(50, 45, 250, 85)
        ruby.drawDots(70, 35, count = 4, gap = 40)
        for (x in listOf(79, 119, 159, 199)) ruby.drawTextBlock(x, 29, x + 5, 40)
        assertTrue(ChoicePunctuationRecovery.emphasisDots(ruby.analysis()).isEmpty())
        val dakuten = Fixture()
        dakuten.drawTextBlock(50, 45, 250, 85)
        dakuten.drawDots(70, 35, count = 2, gap = 8)
        assertTrue(ChoicePunctuationRecovery.emphasisDots(dakuten.analysis()).isEmpty())
    }

    @Test
    fun `six choices remain isolated including the lowest punctuation-only button`() {
        val fixture = Fixture(height = 720)
        val buttons = (0..5).map { ChoicePunctuationRecovery.Bounds(0, it * 120, 400, it * 120 + 100) }
        for (index in 0..4) {
            fixture.drawTextBlock(60, index * 120 + 32, 190, index * 120 + 72)
            fixture.drawDots(208, index * 120 + 52)
        }
        fixture.drawDots(164, 650)
        val lines = (0..4).map { line("選択$it", 60, it * 120 + 32, 190, it * 120 + 72, it) }
        val result = fixture.recoverButtons(buttons, lines)
        assertEquals(listOf("選択0……", "選択1……", "選択2……", "選択3……", "選択4……", "……"), result.lines.sortedBy { it.bounds.top }.map { it.text })
    }

    @Test
    fun `border glow and text outside buttons cannot invent punctuation`() {
        val fixture = Fixture()
        fixture.drawTextBlock(60, 32, 190, 72)
        fixture.drawDash(5, 2, 380, 4)
        fixture.drawDots(208, 97)
        assertEquals("進む", fixture.recover(line("進む", 60, 32, 190, 72)).lines.single().text)
    }

    private fun line(
        text: String,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        sourceIndex: Int = 0
    ) = ChoicePunctuationRecovery.Line(
        sourceIndex = sourceIndex,
        text = text,
        bounds = ChoicePunctuationRecovery.Bounds(left, top, right, bottom),
        confidence = 0.95f
    )

    private class Fixture(
        private val width: Int = 400,
        private val height: Int = 100,
        private val ink: Int = WHITE
    ) {
        private val pixels = IntArray(width * height) { BLACK }
        private val button = ChoicePunctuationRecovery.Bounds(0, 0, width, height)

        fun drawTextBlock(left: Int, top: Int, right: Int, bottom: Int) {
            drawRect(left, top, right, bottom)
        }

        fun drawDots(startX: Int, centerY: Int, count: Int = 6, gap: Int = 8) {
            repeat(count) { index ->
                val left = startX + index * gap
                drawRect(left, centerY - 2, left + 4, centerY + 2)
            }
        }

        fun drawDash(left: Int, centerY: Int, dashWidth: Int, dashHeight: Int) {
            drawRect(left, centerY - dashHeight / 2, left + dashWidth, centerY - dashHeight / 2 + dashHeight)
        }

        fun drawRing(left: Int, top: Int, side: Int) {
            for (y in top until top + side) for (x in left until left + side) {
                if (x == left || x == left + side - 1 || y == top || y == top + side - 1) pixels[y * width + x] = ink
            }
        }

        fun analysis() = ChoicePunctuationRecovery.analyze(pixels, width, height, listOf(button))

        fun recoverButtons(buttons: List<ChoicePunctuationRecovery.Bounds>, lines: List<ChoicePunctuationRecovery.Line>) =
            ChoicePunctuationRecovery.recover(pixels, width, height, buttons, lines)

        fun recover(vararg lines: ChoicePunctuationRecovery.Line): ChoicePunctuationRecovery.Result {
            return ChoicePunctuationRecovery.recover(
                pixels = pixels,
                width = width,
                height = height,
                buttons = listOf(button),
                lines = lines.toList()
            )
        }

        private fun drawRect(left: Int, top: Int, right: Int, bottom: Int) {
            for (y in top.coerceAtLeast(0) until bottom.coerceAtMost(height)) {
                val row = y * width
                for (x in left.coerceAtLeast(0) until right.coerceAtMost(width)) {
                    pixels[row + x] = ink
                }
            }
        }
    }

    private companion object {
        private const val BLACK = -0x1000000
        private const val WHITE = -0x1
    }
}
