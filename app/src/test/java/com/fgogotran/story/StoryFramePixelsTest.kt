package com.fgogotran.story

import com.fgogotran.overlay.FgoReferenceRect
import com.fgogotran.util.FramePixelReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class StoryFramePixelsTest {
    private val width = 320
    private val height = 900
    private val buttons = (0 until 6).map { FgoReferenceRect(10, 10 + it * 144, 310, 145 + it * 144) }

    @Test
    fun `choice observations are bit exact for one through six buttons including ruby and dashes`() {
        for (count in 1..6) {
            val fixture = Fixture()
            val bounds = buttons.take(count)
            val direct = StoryTapHandoff.sampleChoices(width, height, bounds, fixture::pixel)
            assertTrue(direct.sameText(fixture.frame.choices(bounds)))
            assertTrue(direct.hasText)
            assertEquals(1, fixture.copies)
        }
    }

    @Test
    fun `same observation reused at render commit rather than sampled twice`() {
        val fixture = Fixture()
        val observed = fixture.frame.choices(buttons.take(2))
        assertSame(observed, fixture.frame.choices(buttons.take(2).reversed()))
        assertEquals(1, fixture.copies)
    }

    @Test
    fun `changing geometry in one frame does not reuse a different choice observation`() {
        val fixture = Fixture()
        val observed = fixture.frame.choices(buttons.take(2))
        val subset = fixture.frame.choices(buttons.take(1))
        assertFalse(observed.sameText(subset))
        assertEquals(1, fixture.copies) // The existing larger pixel buffer still covers the subset.
    }

    @Test
    fun `freshness frame sees changed text despite identical button bounds`() {
        val old = Fixture()
        val next = Fixture(changed = true)
        assertFalse(old.frame.choices(buttons.take(2)).sameText(next.frame.choices(buttons.take(2))))
        assertEquals(1, old.copies)
        assertEquals(1, next.copies)
    }

    @Test
    fun `choice buffers reuse a detector-prepared larger ROI`() {
        val fixture = Fixture()
        fixture.frame.pixels.read(FramePixelReader.Bounds(0, 0, width, height))
        fixture.frame.choices(buttons)
        fixture.frame.choices(buttons)
        assertEquals(1, fixture.copies)
    }

    @Test
    fun `clear removes pixel and choice observation caches`() {
        val fixture = Fixture()
        val before = fixture.frame.choices(buttons.take(2))
        fixture.frame.clear()
        assertTrue(before.sameText(fixture.frame.choices(buttons.take(2))))
        assertEquals(2, fixture.copies)
    }

    @Test
    fun `empty list does not read a full frame`() {
        val fixture = Fixture()
        assertFalse(fixture.frame.choices(emptyList()).hasText)
        assertEquals(0, fixture.copies)
    }

    private inner class Fixture(private val changed: Boolean = false) {
        var copies = 0
        val frame = StoryFramePixels(FramePixelReader(width, height) { data, bounds ->
            copies++
            for (y in bounds.top until bounds.bottom) for (x in bounds.left until bounds.right) {
                data[(y - bounds.top) * bounds.width + x - bounds.left] = pixel(x, y)
            }
        })
        fun pixel(x: Int, y: Int): Int {
            val localY = (y - 10).mod(144)
            // Small upper readings/dots, a one-pixel horizontal dash and ordinary main text.
            val ruby = localY in 16..20 && x in 40..180 && x % 23 < 3
            val dash = localY == 67 && x in 30..240
            val main = localY in 48..88 && x in 35..180 && x % 17 < 5
            val changedInk = changed && localY in 40..96 && x in 190..220
            return if (ruby || dash || main || changedInk) 0xFFFFFFFF.toInt() else 0xFF101010.toInt()
        }
    }
}
