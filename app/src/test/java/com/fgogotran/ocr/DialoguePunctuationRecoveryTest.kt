package com.fgogotran.ocr

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DialoguePunctuationRecoveryTest {
    @Test
    fun `leading dots are recovered inside the fixed crop`() {
        val fixture = Fixture()
        fixture.dots(0, 65)
        fixture.glyph(92, 42, 128, 90)
        val result = fixture.recover(line("あ", 90, 42, 130, 90, token("あ", 110f)))
        assertEquals("……あ", result.lines.single().text)
        assertEquals(0, result.lines.single().bounds.left)
    }

    @Test
    fun `trailing dots at the exact right edge are retained`() {
        val fixture = Fixture(width = 180)
        fixture.glyph(25, 42, 61, 90)
        fixture.dots(120, 65)
        val result = fixture.recover(line("あ", 20, 42, 65, 90, token("あ", 45f)))
        assertEquals("あ……", result.lines.single().text)
        assertEquals(180, result.lines.single().bounds.right)
    }

    @Test
    fun `internal punctuation uses actual tokens not equal character widths`() {
        val fixture = Fixture()
        fixture.dots(85, 65)
        val original = line("私ALEX来る", 15, 42, 350, 90,
            token("私", 40f), token("ALEX", 220f), token("来", 280f), token("る", 330f))
        assertEquals("私……ALEX来る", fixture.recover(original).lines.single().text)
    }

    @Test
    fun `internal punctuation without token anchors is not guessed`() {
        val fixture = Fixture()
        fixture.dots(85, 65)
        val original = line("私ALEX来る", 15, 42, 350, 90)
        val result = fixture.recover(original)
        assertEquals(original.text, result.lines.single().text)
        assertEquals(setOf(original.bounds), result.unresolvedBounds)
    }

    @Test
    fun `retry positions can restore internal punctuation without accepting new words`() {
        val fixture = Fixture()
        fixture.dots(85, 65)
        val retry = listOf(token("前", 40f), token("……", 110f), token("後", 220f))
        val positions = DialoguePunctuationRecovery.tokensForText("前後", retry)
        val original = line("前後", 15, 42, 350, 90, *positions.toTypedArray())
        assertEquals("前……後", fixture.recover(original).lines.single().text)
        assertTrue(DialoguePunctuationRecovery.tokensForText("前後", listOf(token("別", 40f), token("後", 220f))).isEmpty())
        assertTrue(DialoguePunctuationRecovery.tokensForText("前後", listOf(token("前", 40f), token("ー", 110f), token("後", 220f))).isEmpty())
    }

    @Test
    fun `word token overlapping an ambiguous candidate is retained`() {
        val fixture = Fixture()
        fixture.dots(85, 65)
        val original = line("本文", 15, 42, 350, 90, token("本", 110f), token("文", 240f))
        val result = fixture.recover(original)
        assertEquals("本文", result.lines.single().text)
        assertTrue(result.unresolvedBounds.isNotEmpty())
    }

    @Test
    fun `all leading internal and trailing pauses survive together`() {
        val fixture = Fixture(width = 620)
        fixture.dots(0, 65)
        fixture.dots(190, 65)
        fixture.dots(380, 65)
        val original = line("前後", 80, 42, 365, 90, token("前", 120f), token("後", 310f))
        assertEquals("……前……後……", fixture.recover(original).lines.single().text)
    }

    @Test
    fun `standalone pause requires no Japanese anchor or OCR boxes`() {
        val fixture = Fixture()
        fixture.dots(0, 65)
        val result = fixture.recover()
        assertEquals("……", result.lines.single().text)
        assertTrue(result.lines.single().bounds.height >= 40)
    }

    @Test
    fun `one ellipsis and intentional odd ellipsis count remain exact`() {
        listOf(3 to "…", 9 to "………").forEach { (count, expected) ->
            val fixture = Fixture()
            fixture.dots(20, 65, count)
            assertEquals(expected, fixture.recover().lines.single().text)
        }
    }

    @Test
    fun `standalone pause with hollow period survives`() {
        val fixture = Fixture()
        fixture.dots(20, 65)
        fixture.ring(91, 73, 10)
        assertEquals("……。", fixture.recover().lines.single().text)
    }

    @Test
    fun `quoted punctuation-only row keeps quotes while completing dots`() {
        val fixture = Fixture()
        fixture.dots(20, 65)
        val original = line("「…」", 12, 42, 100, 90)
        val recovered = fixture.recover(original).lines.single()
        assertEquals("「……」", recovered.text)
        assertEquals(original.bounds.left, recovered.bounds.left)
        assertEquals(original.bounds.right, recovered.bounds.right)
        val positioned = line("『……』", 12, 42, 100, 90,
            token("『", 15f), token("…", 35f), token("…", 65f), token("』", 95f))
        assertEquals("『……』", fixture.recover(positioned).lines.single().text)
    }

    @Test
    fun `trailing period does not push punctuation into another logical row`() {
        val fixture = Fixture()
        fixture.dots(20, 65)
        fixture.glyph(120, 64, 260, 69)
        fixture.dots(300, 65)
        fixture.ring(371, 73, 10)
        assertEquals("……───……。", fixture.recover().lines.single().text)
    }

    @Test
    fun `standalone pause above Japanese line remains its own row`() {
        val fixture = Fixture()
        fixture.dots(25, 30)
        fixture.glyph(25, 106, 60, 154)
        val result = fixture.recover(line("本文", 20, 106, 160, 154))
        assertEquals(listOf("……", "本文"), result.lines.map { it.text })
    }

    @Test
    fun `standalone dash requires no Japanese anchor`() {
        val fixture = Fixture()
        fixture.glyph(0, 64, 145, 69)
        assertEquals("───", fixture.recover().lines.single().text)
    }

    @Test
    fun `long dash is not rejected by an artificial width limit`() {
        val fixture = Fixture(width = 1000)
        fixture.glyph(0, 64, 900, 69)
        assertEquals("───", fixture.recover().lines.single().text)
    }

    @Test
    fun `leading internal trailing dashes are recovered`() {
        listOf(0, 190, 385).forEach { start ->
            val fixture = Fixture(width = 620)
            fixture.glyph(start, 64, start + 120, 69)
            val original = when (start) {
                0 -> line("本文", 140, 42, 360, 90, token("本", 180f), token("文", 320f))
                190 -> line("本文", 20, 42, 370, 90, token("本", 60f), token("文", 350f))
                else -> line("本文", 20, 42, 370, 90, token("本", 60f), token("文", 350f))
            }
            val expected = when (start) { 0 -> "───本文"; 190 -> "本───文"; else -> "本文───" }
            assertEquals(expected, fixture.recover(original).lines.single().text)
        }
    }

    @Test
    fun `multiple punctuation-only rows remain separate`() {
        val fixture = Fixture()
        fixture.dots(20, 30)
        fixture.glyph(20, 134, 165, 139)
        assertEquals(listOf("……", "───"), fixture.recover().lines.map { it.text })
    }

    @Test
    fun `pause and dash on same standalone row are joined in spatial order`() {
        val fixture = Fixture()
        fixture.dots(20, 65)
        fixture.glyph(120, 64, 265, 69)
        assertEquals("……───", fixture.recover().lines.single().text)
    }

    @Test
    fun `existing punctuation is not duplicated and second pass is idempotent`() {
        val fixture = Fixture()
        fixture.dots(20, 65)
        val original = line("……本文", 15, 42, 350, 90, token("…", 30f), token("…", 70f), token("本", 150f), token("文", 280f))
        val first = fixture.recover(original)
        val second = fixture.recover(*first.lines.toTypedArray())
        assertEquals("……本文", first.lines.single().text)
        assertEquals(first.lines, second.lines)
        assertEquals(0, first.recoveredCount)
    }

    @Test
    fun `shortened existing pause is completed from the visible dot count`() {
        val fixture = Fixture()
        fixture.dots(20, 65)
        val original = line("…本文", 15, 42, 350, 90, token("…", 40f), token("本", 150f), token("文", 280f))
        assertEquals("……本文", fixture.recover(original).lines.single().text)
    }

    @Test
    fun `detached pause is consumed only after attaching to main line`() {
        val fixture = Fixture()
        fixture.dots(20, 65)
        val original = line("本文", 110, 42, 240, 90)
        val fragment = line("……", 20, 62, 80, 68)
        assertEquals("……本文", fixture.recover(fragment, original).lines.single().text)
    }

    @Test
    fun `punctuation between separate detected fragments stays on one logical row`() {
        val fixture = Fixture()
        fixture.dots(85, 65)
        val left = line("前", 20, 42, 70, 90, token("前", 45f))
        val right = line("後", 160, 42, 210, 90, token("後", 185f))
        assertEquals("前……後", fixture.recover(left, right).lines.single().text)
    }

    @Test
    fun `emphasis dots are removed before punctuation detection`() {
        val fixture = Fixture()
        listOf(40, 90, 140, 190).forEach { center ->
            fixture.glyph(center - 4, 12, center + 4, 20)
            fixture.glyph(center - 16, 28, center + 16, 76)
        }
        val result = fixture.recover(line("うるさい", 20, 28, 215, 76))
        assertEquals("うるさい", result.lines.single().text)
        assertEquals(0, result.recoveredCount)
    }

    @Test
    fun `readable ruby and upper punctuation are not duplicated in main text`() {
        val fixture = Fixture()
        fixture.dots(30, 25)
        fixture.glyph(22, 43, 65, 91)
        val main = line("本文", 20, 43, 180, 91)
        val ruby = line("よみ", 25, 12, 100, 30)
        val result = fixture.recover(ruby, main)
        assertEquals(listOf("よみ", "本文"), result.lines.map { it.text })
        assertEquals(0, result.recoveredCount)
    }

    @Test
    fun `all seven story colours recover punctuation`() {
        FgoStoryTextPalette.samples.forEach { sample ->
            val fixture = Fixture(ink = sample.renderColor)
            fixture.dots(20, 65)
            assertEquals("……", fixture.recover().lines.single().text, sample.family.name)
            val dash = Fixture(ink = sample.renderColor)
            dash.glyph(20, 64, 165, 69)
            assertEquals("───", dash.recover().lines.single().text, sample.family.name)
        }
    }

    @Test
    fun `normal kanji one and prolonged sound marks stay lexical`() {
        listOf("一", "ー").forEach { text ->
            val fixture = Fixture()
            fixture.glyph(30, 64, 75, 69)
            val original = line(text, 20, 42, 85, 90, token(text, 50f))
            assertEquals(listOf(original), fixture.recover(original).lines)
        }
    }

    @Test
    fun `irregular dots and insufficient dot evidence are not invented`() {
        val fixture = Fixture()
        listOf(20, 32, 51, 66, 79).forEach { x -> fixture.glyph(x, 62, x + 5, 68) }
        val original = line("本文", 110, 42, 240, 90)
        assertEquals(listOf(original), fixture.recover(original).lines)
    }

    @Test
    fun `ordinary text and blank crop are unchanged`() {
        val fixture = Fixture()
        fixture.glyph(20, 42, 55, 90)
        val original = line("こんにちは！", 15, 42, 350, 90)
        assertEquals(listOf(original), fixture.recover(original).lines)
        assertTrue(Fixture().recover().lines.isEmpty())
    }

    private fun token(text: String, x: Float) = DialoguePunctuationRecovery.Token(text, x)
    private fun line(text: String, l: Int, t: Int, r: Int, b: Int, vararg tokens: DialoguePunctuationRecovery.Token) =
        DialoguePunctuationRecovery.Line(text, DialoguePunctuationRecovery.Bounds(l, t, r, b), 0.9f, tokens.toList())

    private class Fixture(val width: Int = 480, val height: Int = 220, val ink: Int = -1) {
        val pixels = IntArray(width * height) { 0xff142943.toInt() }
        fun glyph(l: Int, t: Int, r: Int, b: Int) {
            for (y in t until b) for (x in l until r) pixels[y * width + x] = ink
        }
        fun dots(left: Int, centerY: Int, count: Int = 6) {
            repeat(count) { glyph(left + it * 11, centerY - 3, left + it * 11 + 5, centerY + 3) }
        }
        fun ring(l: Int, t: Int, side: Int) {
            for (y in t until t + side) for (x in l until l + side) {
                if (x < l + 2 || x >= l + side - 2 || y < t + 2 || y >= t + side - 2) pixels[y * width + x] = ink
            }
        }
        fun recover(vararg lines: DialoguePunctuationRecovery.Line): DialoguePunctuationRecovery.Result {
            val cleanup = DialogueAnnotationCleaner.clean(pixels, width, height)
            return DialoguePunctuationRecovery.recover(cleanup.pixels, width, height, cleanup.components, lines.toList())
        }
    }
}
