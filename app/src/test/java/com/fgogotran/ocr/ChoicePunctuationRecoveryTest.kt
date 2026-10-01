package com.fgogotran.ocr

import kotlin.test.Test
import kotlin.test.assertEquals

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
            line("前半後半", 40, 31, 330, 74)
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

        fun drawDots(startX: Int, centerY: Int) {
            repeat(6) { index ->
                val left = startX + index * 8
                drawRect(left, centerY - 2, left + 4, centerY + 2)
            }
        }

        fun drawDash(left: Int, centerY: Int, dashWidth: Int, dashHeight: Int) {
            drawRect(left, centerY - dashHeight / 2, left + dashWidth, centerY - dashHeight / 2 + dashHeight)
        }

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
