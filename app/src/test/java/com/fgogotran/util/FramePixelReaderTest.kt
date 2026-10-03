package com.fgogotran.util

import com.fgogotran.util.FramePixelReader.Bounds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class FramePixelReaderTest {
    @Test
    fun `offset ROI retains exact screen coordinates and stride`() {
        val fixture = Fixture(17, 13)
        val bounds = Bounds(3, 4, 14, 11)
        val region = fixture.reader.read(bounds)
        for (y in bounds.top until bounds.bottom) for (x in bounds.left until bounds.right) {
            assertEquals(fixture.pixel(x, y), region.getPixel(x, y))
        }
        assertEquals(listOf(bounds), fixture.copies)
    }

    @Test
    fun `contained ROIs share one bulk read without shifting their sample grid`() {
        val fixture = Fixture(17, 13)
        val parent = fixture.reader.read(Bounds(2, 2, 15, 12))
        val childBounds = Bounds(5, 3, 13, 9)
        val child = fixture.reader.read(childBounds)
        assertSame(parent, child)
        for (y in childBounds.top until childBounds.bottom step 3) {
            for (x in childBounds.left until childBounds.right step 3) {
                assertEquals(fixture.pixel(x, y), child.getPixel(x, y))
            }
        }
        assertEquals(1, fixture.copies.size)
    }

    @Test
    fun `full ROI supersedes discovery strips and serves both strips afterward`() {
        val fixture = Fixture(17, 13)
        val left = Bounds(1, 2, 4, 12)
        val right = Bounds(13, 2, 16, 12)
        fixture.reader.read(left)
        fixture.reader.read(right)
        val full = fixture.reader.read(Bounds(1, 1, 16, 13))
        assertSame(full, fixture.reader.read(left))
        assertSame(full, fixture.reader.read(right))
        assertEquals(3, fixture.copies.size)
    }

    @Test
    fun `clips every screen edge before bulk copying`() {
        val fixture = Fixture(17, 13)
        val region = fixture.reader.read(Bounds(-12, -20, 30, 50))
        assertEquals(Bounds(0, 0, 17, 13), region.bounds)
        assertEquals(fixture.pixel(0, 0), region.getPixel(0, 0))
        assertEquals(fixture.pixel(16, 12), region.getPixel(16, 12))
    }

    @Test
    fun `empty and wholly out of screen ROIs do not copy pixels`() {
        val fixture = Fixture(17, 13)
        listOf(Bounds(0, 0, 0, 5), Bounds(20, 2, 25, 8), Bounds(3, 15, 9, 20),
            Bounds(-10, 0, -3, 10), Bounds(8, 8, 4, 4)).forEach { fixture.reader.read(it) }
        assertEquals(0, fixture.copies.size)
    }

    @Test
    fun `different captures never share a buffer`() {
        val first = Fixture(17, 13, 0xFF000000.toInt())
        val next = Fixture(17, 13, 0xFFFFFFFF.toInt())
        val bounds = Bounds(3, 4, 9, 10)
        assertEquals(first.pixel(5, 7), first.reader.read(bounds).getPixel(5, 7))
        assertEquals(next.pixel(5, 7), next.reader.read(bounds).getPixel(5, 7))
        assertEquals(1, first.copies.size)
        assertEquals(1, next.copies.size)
    }

    @Test
    fun `clear releases cached reads before reuse`() {
        val fixture = Fixture(17, 13)
        val bounds = Bounds(3, 4, 9, 10)
        fixture.reader.read(bounds)
        fixture.reader.clear()
        fixture.reader.read(bounds)
        assertEquals(2, fixture.copies.size)
    }

    @Test
    fun `zero size frame needs no allocation`() {
        val fixture = Fixture(0, 0)
        fixture.reader.read(Bounds(-1, -1, 10, 10))
        assertEquals(0, fixture.copies.size)
    }

    private class Fixture(width: Int, height: Int, private val salt: Int = 0) {
        val copies = mutableListOf<Bounds>()
        val reader = FramePixelReader(width, height) { data, bounds ->
            copies.add(bounds)
            for (y in bounds.top until bounds.bottom) for (x in bounds.left until bounds.right) {
                data[(y - bounds.top) * bounds.width + x - bounds.left] = pixel(x, y)
            }
        }
        fun pixel(x: Int, y: Int) = salt xor (x + y * 101)
    }
}
