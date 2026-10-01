package com.fgogotran.ocr

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FgoStoryTextPaletteTest {
    @Test
    fun `legacy exact colours and vote indices stay unchanged`() {
        listOf(0xF5F5F0, 0xDC0000, 0x50EBEB, 0xC5E35E).forEachIndexed { index, rgb ->
            assertEquals(index, nearest(rgb))
            assertEquals(rgb or (0xff shl 24), FgoStoryTextPalette.samples[index].renderColor)
        }
    }

    @Test
    fun `all additional source colours vote for themselves and pass the ink gate`() {
        assertEquals(listOf(0xFF69B4, 0x8753D1, 0xB86349),
            FgoStoryTextPalette.samples.drop(4).map { it.rgb })
        FgoStoryTextPalette.samples.drop(4).forEach { sample ->
            assertEquals(sample.rgb, FgoStoryTextPalette.samples[nearest(sample.rgb)].rgb)
            assertTrue(FgoStoryTextPalette.isColoredInk(
                (sample.rgb shr 16) and 0xff, (sample.rgb shr 8) and 0xff, sample.rgb and 0xff
            ))
            assertEquals(sample.family, family(sample.rgb))
        }
    }

    @Test
    fun `moderate screenshot compression preserves the three approved additions`() {
        mapOf(0xF669AF to 0xFF69B4, 0x8357CA to 0x8753D1, 0xB2664D to 0xB86349)
            .forEach { (pixel, expected) ->
                assertEquals(expected, FgoStoryTextPalette.samples[nearest(pixel)].rgb)
                assertFalse(FgoStoryTextPalette.isRedInk(
                    (pixel shr 16) and 0xff, (pixel shr 8) and 0xff, pixel and 0xff
                ))
            }
    }

    @Test
    fun `warm highlights and brown cannot trigger the red fallback`() {
        assertFalse(FgoStoryTextPalette.isRedInk(184, 99, 73))
        assertFalse(FgoStoryTextPalette.isRedInk(229, 188, 124))
        assertFalse(FgoStoryTextPalette.isRedInk(255, 209, 76))
        assertTrue(FgoStoryTextPalette.isRedInk(220, 0, 0))
        assertTrue(FgoStoryTextPalette.isRedInk(110, 50, 50))
    }

    @Test
    fun `brown does not steal the existing red stroke or its antialiased edges`() {
        listOf(0xDC0000, 0xDC3C3C, 0xFF4040).forEach { rgb ->
            assertNull(family(rgb))
            assertEquals(1, nearest(rgb))
            assertEquals(1, nearest(rgb, FgoStoryTextPalette.Scope.CHOICE))
        }
    }

    @Test
    fun `dark antialiased brown stays out of the choice red shortcut`() {
        listOf(0xB86349, 0xFF69B4, 0x8753D1).forEach { rgb ->
            listOf(1f, 0.8f, 0.65f, 0.55f).forEach { opacity ->
                val red = (((rgb shr 16) and 0xff) * opacity).toInt()
                val green = (((rgb shr 8) and 0xff) * opacity).toInt()
                val blue = ((rgb and 0xff) * opacity).toInt()
                assertFalse(FgoStoryTextPalette.isRedInk(red, green, blue))
            }
        }
    }

    @Test
    fun `choices only vote and detect white or red ink`() {
        val scope = FgoStoryTextPalette.Scope.CHOICE
        assertEquals(listOf(0xF5F5F0, 0xDC0000), FgoStoryTextPalette.samplesFor(scope).map { it.rgb })
        assertEquals(0, nearest(0xFFFFFF, scope))
        assertEquals(1, nearest(0xFF0000, scope))
        assertTrue(FgoStoryTextPalette.isChoiceInk(245, 245, 240))
        assertTrue(FgoStoryTextPalette.isChoiceInk(220, 0, 0))
        assertTrue(FgoStoryTextPalette.isChoiceInk(110, 50, 50))
        FgoStoryTextPalette.samples.drop(2).forEach { sample ->
            assertEquals(-1, nearest(sample.rgb, scope))
            assertFalse(FgoStoryTextPalette.isColoredInk(
                (sample.rgb shr 16) and 0xff, (sample.rgb shr 8) and 0xff, sample.rgb and 0xff, scope
            ))
            assertFalse(FgoStoryTextPalette.isChoiceInk(
                (sample.rgb shr 16) and 0xff, (sample.rgb shr 8) and 0xff, sample.rgb and 0xff
            ))
        }
    }

    @Test
    fun `dialogue punctuation and annotation masks accept all seven colours`() {
        FgoStoryTextPalette.samples.forEach { sample ->
            assertTrue(FgoStoryTextPalette.isDialogueInk(
                (sample.rgb shr 16) and 0xff, (sample.rgb shr 8) and 0xff, sample.rgb and 0xff
            ))
        }
        listOf(0x000000, 0x1B3355, 0xFFD14C, 0xE5BC7C, 0x797979).forEach { rgb ->
            assertFalse(FgoStoryTextPalette.isDialogueInk(
                (rgb shr 16) and 0xff, (rgb shr 8) and 0xff, rgb and 0xff
            ))
        }
    }

    @Test
    fun `removed gold orange grey and olive have no additional ink family`() {
        listOf(0xFFD14C, 0xE5BC7C, 0x797979, 0x677B32).forEach { rgb ->
            assertNull(family(rgb))
        }
        assertEquals(7, FgoStoryTextPalette.samples.size)
    }

    @Test
    fun `dark scenery and black pixels remain background`() {
        listOf(0x000000, 0x19467D, 0x14273B).forEach { rgb ->
            assertNull(family(rgb))
        }
    }

    private fun nearest(rgb: Int, scope: FgoStoryTextPalette.Scope = FgoStoryTextPalette.Scope.STORY) =
        FgoStoryTextPalette.nearestIndex(
            (rgb shr 16) and 0xff, (rgb shr 8) and 0xff, rgb and 0xff, scope
        )

    private fun family(rgb: Int) = FgoStoryTextPalette.additionalInkFamily(
        (rgb shr 16) and 0xff, (rgb shr 8) and 0xff, rgb and 0xff
    )
}
