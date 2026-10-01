package com.fgogotran.ocr

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DialogueAnnotationCleanerTest {
    @Test
    fun `coloured emphasis is removed while coloured main text and pauses remain`() {
        FgoStoryTextPalette.samples.forEach { sample ->
            val fixture = Fixture(ink = sample.renderColor)
            listOf(40, 90, 140, 190).forEach { centerX ->
                fixture.drawRect(centerX - 4, 10, centerX + 4, 18)
                fixture.drawRect(centerX - 16, 26, centerX + 16, 68)
            }
            val result = fixture.clean()
            assertEquals(4, result.maskedComponents)
            assertEquals(sample.renderColor, result.pixels[45 * fixture.width + 40])

            val pause = Fixture(ink = sample.renderColor)
            repeat(6) { index -> pause.drawRect(30 + index * 9, 10, 35 + index * 9, 15) }
            pause.drawRect(24, 39, 210, 78)
            val before = pause.pixels.copyOf()
            assertTrue(before.contentEquals(pause.clean().pixels))
        }
    }

    @Test
    fun `removes emphasis dots and preserves main glyphs`() {
        val fixture = Fixture()
        val dots = listOf(40, 90, 140, 190)
        dots.forEach { centerX ->
            fixture.drawRect(centerX - 4, 10, centerX + 4, 18)
            fixture.drawRect(centerX - 16, 26, centerX + 16, 68)
        }

        val result = fixture.clean()

        assertEquals(4, result.maskedComponents)
        assertEquals(1, result.maskedRows)
        dots.forEach { centerX ->
            assertFalse(result.pixels[14 * fixture.width + centerX].isWhite())
            assertTrue(result.pixels[45 * fixture.width + centerX].isWhite())
        }
    }

    @Test
    fun `keeps a standalone pause row separated from the following line`() {
        val fixture = Fixture()
        repeat(6) { index -> fixture.drawRect(30 + index * 9, 10, 35 + index * 9, 15) }
        fixture.drawRect(24, 39, 210, 78)

        val before = fixture.pixels.copyOf()
        val result = fixture.clean()

        assertEquals(0, result.maskedComponents)
        assertTrue(before.contentEquals(result.pixels))
    }

    @Test
    fun `keeps punctuation in the main text band`() {
        val fixture = Fixture()
        fixture.drawRect(24, 30, 170, 72)
        repeat(6) { index -> fixture.drawRect(182 + index * 8, 48, 186 + index * 8, 52) }

        val before = fixture.pixels.copyOf()
        val result = fixture.clean()

        assertEquals(0, result.maskedComponents)
        assertTrue(before.contentEquals(result.pixels))
    }

    @Test
    fun `keeps readable upper annotation strokes`() {
        val fixture = Fixture()
        repeat(4) { index ->
            val centerX = 40 + index * 50
            fixture.drawRect(centerX - 2, 7, centerX + 2, 20)
            fixture.drawRect(centerX - 16, 28, centerX + 16, 70)
        }

        val before = fixture.pixels.copyOf()
        val result = fixture.clean()

        assertEquals(0, result.maskedComponents)
        assertTrue(before.contentEquals(result.pixels))
    }

    private class Fixture(
        val width: Int = 240,
        private val height: Int = 96,
        private val ink: Int = WHITE
    ) {
        val pixels = IntArray(width * height) { DARK_BLUE }

        fun drawRect(left: Int, top: Int, right: Int, bottom: Int) {
            for (y in top until bottom) {
                for (x in left until right) pixels[y * width + x] = ink
            }
        }

        fun clean(): DialogueAnnotationCleaner.Result =
            DialogueAnnotationCleaner.clean(pixels, width, height)
    }

    private fun Int.isWhite(): Boolean = this == WHITE

    private companion object {
        private const val DARK_BLUE = -0xd8c5a9
        private const val WHITE = -0x1
    }
}
