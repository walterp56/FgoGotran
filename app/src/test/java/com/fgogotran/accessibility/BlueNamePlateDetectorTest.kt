package com.fgogotran.accessibility

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BlueNamePlateDetectorTest {
    @Test
    fun `gold outlined plate uses blue fill rather than the border colour`() {
        val band = Band()
        for (y in band.nameTop + 4..band.nameTop + 7) band.paintRow(y, 0, 500, GOLD)
        band.paintPlate(500, leadingFrame = 12)
        assertEquals(500, band.detect())
    }

    @Test
    fun `missing border still permits a bounded blue panel`() {
        val band = Band().apply { paintPlate(600) }
        assertEquals(600, band.detect())
    }

    @Test
    fun `supports the actual dark blue JPEG samples without accepting cyan ink`() {
        val samples = listOf(
            rgb(16, 44, 81), rgb(23, 57, 94), rgb(33, 67, 105),
            rgb(48, 83, 121), rgb(43, 77, 115), rgb(17, 61, 122)
        )
        for (sample in samples) {
            val band = Band().apply { paintPlate(500, colour = sample) }
            assertEquals(500, band.detect(), "sample=$sample")
        }
    }

    @Test
    fun `a horizontal blue gradient preserves the endpoint`() {
        val band = Band()
        for (y in band.interiorTop until band.height) {
            for (x in 0 until 500) {
                val red = 16 + x * 27 / 500
                band.paintRow(y, x, x + 1, rgb(red, red + 33, red + 71))
            }
        }
        assertEquals(500, band.detect())
    }

    @Test
    fun `small interruptions do not end the plate`() {
        val band = Band().apply { paintPlate(500) }
        for (y in band.interiorTop until band.height) band.paintRow(y, 280, 285, WHITE)
        assertEquals(500, band.detect())
    }

    @Test
    fun `one row obscured by name ink does not discard the other rows`() {
        val band = Band().apply { paintPlate(500) }
        band.paintRow(band.interiorTop + band.rowStep, 0, band.width, WHITE)
        assertEquals(500, band.detect())
    }

    @Test
    fun `one premature row endpoint does not clip the crop`() {
        val band = Band().apply { paintPlate(500) }
        band.paintRow(band.interiorTop + band.rowStep, 300, 500, ART)
        assertEquals(500, band.detect())
    }

    @Test
    fun `slightly angled frame uses the furthest agreeing row`() {
        val band = Band()
        for (y in band.interiorTop until band.height) {
            band.paintRow(y, 12, 490 + (y - band.interiorTop) / 2, BLUE)
        }
        assertEquals(494, band.detect())
    }

    @Test
    fun `too many obscured rows fail closed`() {
        val band = Band().apply { paintPlate(500) }
        band.paintRow(band.interiorTop, 0, band.width, WHITE)
        band.paintRow(band.interiorTop + band.rowStep, 0, band.width, WHITE)
        assertNull(band.detect())
    }

    @Test
    fun `disagreeing right edges do not produce an oversized crop`() {
        val band = Band()
        for (y in band.interiorTop until band.height) {
            band.paintRow(y, 0, 300 + (y - band.interiorTop) * 30, BLUE)
        }
        assertNull(band.detect())
    }

    @Test
    fun `blue scenery continuing above the name band is not a panel`() {
        val band = Band()
        for (y in 0 until band.height) band.paintRow(y, 0, 500, BLUE)
        assertNull(band.detect())
    }

    @Test
    fun `blue scenery away from the fixed left is not a panel`() {
        val band = Band().apply { paintPlate(500, leadingFrame = 100) }
        assertNull(band.detect())
    }

    @Test
    fun `blue without a visible right transition is rejected`() {
        val band = Band().apply { paintPlate(width) }
        assertNull(band.detect())
    }

    @Test
    fun `a transition clipped by the search limit is rejected`() {
        val band = Band().apply { paintPlate(width - 5) }
        assertNull(band.detect())
    }

    @Test
    fun `isolated short blue patches are rejected`() {
        val band = Band().apply { paintPlate(100) }
        assertNull(band.detect())
    }

    @Test
    fun `text colours and neutral backgrounds cannot substitute for blue fill`() {
        for (colour in listOf(WHITE, GOLD, 0xff50ebeb.toInt(), 0xff8753d1.toInt(),
            0xffb86349.toInt(), 0xffff69b4.toInt(), 0xff797979.toInt())) {
            val band = Band().apply { paintPlate(500, colour = colour) }
            assertNull(band.detect(), "colour=$colour")
        }
    }

    @Test
    fun `the band and minimum width scale with the existing name height`() {
        for ((nameHeight, width, right) in listOf(Triple(49, 678, 300), Triple(122, 1695, 750))) {
            val band = Band(nameHeight, width).apply { paintPlate(right) }
            assertEquals(right, band.detect())
            assertTrue(band.height - band.nameTop <= nameHeight / 4)
        }
    }

    @Test
    fun `malformed buffers and bounds are rejected without reading pixels`() {
        assertNull(BlueNamePlateDetector.rightEdge(intArrayOf(), 1130, 25, 82, 5))
        assertNull(BlueNamePlateDetector.rightEdge(intArrayOf(), -1, 25, 82, 5))
        assertNull(BlueNamePlateDetector.rightEdge(intArrayOf(), 1130, 0, 82, 5))
        assertNull(BlueNamePlateDetector.rightEdge(intArrayOf(), 1130, 25, 0, 5))
        assertNull(BlueNamePlateDetector.rightEdge(IntArray(1130 * 25), 1130, 25, 82, -1))
        assertNull(BlueNamePlateDetector.rightEdge(IntArray(1130 * 25), 1130, 25, 82, Int.MAX_VALUE))
    }

    @Test
    fun `the existing cyan detector still measures the original line`() {
        assertEquals(752, CyanNameLineDetector.rightEdge(
            pixelAt = { x, y -> if (x in 252 until 752 && y in 737..739) CYAN_BORDER else ART },
            imageWidth = 2340, imageHeight = 1080,
            left = 252, top = 739, right = 1382, bottom = 821
        ))
    }

    private class Band(val nameHeight: Int = 82, val width: Int = 1130) {
        val nameTop = -BlueNamePlateDetector.bandTopOffset(nameHeight)
        val height = nameTop + BlueNamePlateDetector.bandBottomOffset(nameHeight)
        val interiorTop = nameTop + maxOf(4, nameHeight / 8)
        val rowStep = (nameHeight / 40).coerceAtLeast(1)
        private val pixels = IntArray(width * height) { ART }

        fun paintRow(y: Int, left: Int, right: Int, colour: Int) {
            for (x in left until right) pixels[y * width + x] = colour
        }

        fun paintPlate(right: Int, leadingFrame: Int = 0, colour: Int = BLUE) {
            for (y in interiorTop until height) paintRow(y, leadingFrame, right, colour)
        }

        fun detect(): Int? = BlueNamePlateDetector.rightEdge(pixels, width, height, nameHeight, nameTop)
    }

    companion object {
        private const val BLUE = 0xff214369.toInt()
        private const val ART = 0xff6e8c85.toInt()
        private const val GOLD = 0xffcdb87f.toInt()
        private const val WHITE = 0xfff5f5f0.toInt()
        private const val CYAN_BORDER = 0xff78dce6.toInt()

        private fun rgb(r: Int, g: Int, b: Int): Int = (0xff shl 24) or (r shl 16) or (g shl 8) or b
    }
}
