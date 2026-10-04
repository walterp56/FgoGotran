package com.fgogotran.overlay

import com.fgogotran.util.FramePixelReader
import com.fgogotran.util.FramePixelReader.Bounds
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChoicePixelSamplerTest {
    @Test
    fun `bulk dark and border scores are bit exact with legacy sampling`() {
        val random = Random(304)
        val width = 73
        val height = 49
        val colors = IntArray(width * height) { random.nextInt() }
        val pixels = reader(width, height, colors)
        pixels.read(Bounds(0, 0, width, height))
        val samples = ChoicePixelSampler(pixels)
        repeat(500) {
            val left = random.nextInt(-10, width + 10)
            val top = random.nextInt(-10, height + 10)
            val bounds = Bounds(left, top, left + random.nextInt(0, 90), top + random.nextInt(0, 65))
            assertEquals(legacyRatio(colors, width, height, bounds, 3, ::legacyDark),
                samples.darkRatioInRect(bounds), "dark $bounds")
            assertEquals(legacyRatio(colors, width, height, bounds, 2, ::legacyBorder),
                samples.borderRatioInRect(bounds), "border $bounds")
        }
    }

    @Test
    fun `row discovery preserves odd left edge and every y row`() {
        val width = 73
        val height = 49
        val colors = IntArray(width * height) { if (it % 5 == 0) 0xFF151515.toInt() else 0xFFBBBBBB.toInt() }
        val samples = ChoicePixelSampler(reader(width, height, colors))
        for (y in 0 until height) for (left in listOf(-5, 0, 3, 15, 70, 90)) {
            val bounds = Bounds(left, y, left + 17, y + 1)
            assertEquals(legacyRatio(colors, width, height, bounds, 2, ::legacyDark),
                samples.darkRatioInRow(left, left + 17, y))
        }
    }

    @Test
    fun `threshold colors retain legacy float rounding and border acceptance`() {
        val colors = intArrayOf(0xFF4F4F4F.toInt(), 0xFF505050.toInt(), 0xFF515151.toInt(),
            0xFF7D3232.toInt(), 0xFF508196.toInt(), 0xFF6E96AA.toInt(), 0xFFAFB9BE.toInt(),
            0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFF0000FF.toInt(), 0xFF00FF00.toInt(), 0xFFFF0000.toInt())
        val samples = ChoicePixelSampler(reader(colors.size, 1, colors))
        for (x in colors.indices) {
            val bounds = Bounds(x, 0, x + 1, 1)
            assertEquals(if (legacyDark(colors[x])) 1f else 0f, samples.darkRatioInRect(bounds))
            assertEquals(if (legacyBorder(colors[x])) 1f else 0f, samples.borderRatioInRect(bounds))
        }
    }

    @Test
    fun `repeated clipped scores reuse the full ROI without more reads`() {
        var copies = 0
        val pixels = FramePixelReader(80, 60) { data, _ -> copies++; data.fill(0xFF141414.toInt()) }
        pixels.read(Bounds(0, 0, 80, 60))
        val samples = ChoicePixelSampler(pixels)
        repeat(10) {
            assertEquals(1f, samples.darkRatioInRect(Bounds(-3, 5, 50, 55)))
            assertEquals(1f, samples.darkRatioInRect(Bounds(0, 5, 50, 55)))
            assertEquals(0f, samples.borderRatioInRect(Bounds(3, 5, 50, 55)))
        }
        assertEquals(1, copies)
    }

    @Test
    fun `one through six panels retain body and thin border scores`() {
        val width = 180
        val height = 900
        for (count in 1..6) {
            val colors = IntArray(width * height) { 0xFFFFFFFF.toInt() }
            val panels = (0 until count).map { Bounds(10, 10 + it * 144, 170, 145 + it * 144) }
            for (panel in panels) for (y in panel.top until panel.bottom) {
                for (x in panel.left until panel.right) {
                    colors[y * width + x] = if (y == panel.top || y == panel.bottom - 1)
                        0xFF80C8EE.toInt() else 0xFF101010.toInt()
                }
            }
            val samples = ChoicePixelSampler(reader(width, height, colors))
            for (panel in panels) {
                val body = Bounds(panel.left + 3, panel.top + 12, panel.right - 3, panel.bottom - 12)
                val border = Bounds(panel.left + 5, panel.top, panel.right - 5, panel.top + 5)
                assertEquals(legacyRatio(colors, width, height, body, 3, ::legacyDark), samples.darkRatioInRect(body))
                assertEquals(legacyRatio(colors, width, height, border, 2, ::legacyBorder), samples.borderRatioInRect(border))
            }
        }
    }

    @Test
    fun `absence gate keeps minimum height runs at every offset and allowed gap`() {
        for (minimumHeight in listOf(30, 39, 59, 79)) {
            val height = minimumHeight * 5
            val anchor = Bounds(3, 7, 14, height - 7)
            for (gap in 0..3) for (phase in 0..gap) {
                for (start in anchor.top..anchor.top + minimumHeight * 2) {
                    for (span in listOf(minimumHeight, minimumHeight + 3, minimumHeight * 2)) {
                        val rows = BooleanArray(height) { y ->
                            y in start until start + span &&
                                (y == start || y == start + span - 1 || (y - start + phase) % (gap + 1) == 0)
                        }
                        assertTrue(hasDensePanelRun(rows.copyOfRange(anchor.top, anchor.bottom), minimumHeight, 3))
                        val colors = IntArray(17 * height) { if (rows[it / 17]) 0xFF101010.toInt() else 0xFFFFFFFF.toInt() }
                        assertTrue(ChoicePixelSampler(reader(17, height, colors)).mayContainDarkPanelRows(
                            anchor, minimumHeight, 3, 0.58f
                        ), "minimum=$minimumHeight start=$start span=$span gap=$gap phase=$phase")
                    }
                }
            }
        }
    }

    @Test
    fun `absence gate never skips a dense run across all short row patterns`() {
        val height = 12
        for (pattern in 0 until (1 shl height)) {
            val rows = BooleanArray(height) { pattern and (1 shl it) != 0 }
            val colors = IntArray(height) { if (rows[it]) 0xFF101010.toInt() else 0xFFFFFFFF.toInt() }
            val pixels = reader(1, height, colors)
            val anchor = Bounds(0, 0, 1, height)
            pixels.read(anchor)
            val samples = ChoicePixelSampler(pixels)
            for (minimumHeight in 1..8) for (gap in 0..minOf(3, minimumHeight - 1)) {
                if (hasDensePanelRun(rows, minimumHeight, gap)) {
                    assertTrue(samples.mayContainDarkPanelRows(anchor, minimumHeight, gap, 0.58f),
                        "pattern=$pattern minimum=$minimumHeight gap=$gap")
                }
            }
        }
    }

    @Test
    fun `absence gate preserves primary evidence for all six FGO layouts and scales`() {
        val layouts = listOf(
            listOf(343 to 478),
            listOf(250 to 385, 437 to 572),
            listOf(156 to 291, 343 to 478, 531 to 666),
            listOf(36 to 171, 214 to 349, 392 to 527, 571 to 706),
            listOf(14 to 149, 158 to 293, 302 to 437, 447 to 582, 591 to 726),
            listOf(14 to 149, 158 to 293, 302 to 437, 447 to 582, 591 to 726, 735 to 870)
        )
        for (canvasHeight in listOf(720, 900, 1080, 1440)) for (offset in listOf(0, 17)) {
            val scale = canvasHeight / 1080f
            val height = canvasHeight + offset * 2
            val anchor = Bounds(3, offset + (220 * scale).toInt(), 43, offset + (730 * scale).toInt())
            val minimumHeight = (height * 0.055f).toInt().coerceAtLeast(30)
            for (layout in layouts) {
                val rows = BooleanArray(height) { y -> layout.any { (top, bottom) ->
                    y >= offset + (top * scale).toInt() + 4 &&
                        y < offset + (bottom * scale).toInt() - 4
                } }
                assertTrue(hasDensePanelRun(rows.copyOfRange(anchor.top, anchor.bottom), minimumHeight, 3))
                val colors = IntArray(47 * height) { if (rows[it / 47]) 0xFF101010.toInt() else 0xFFFFFFFF.toInt() }
                assertTrue(ChoicePixelSampler(reader(47, height, colors)).mayContainDarkPanelRows(
                    anchor, minimumHeight, 3, 0.58f
                ), "count=${layout.size} canvas=$canvasHeight offset=$offset")
            }
        }
    }

    @Test
    fun `absence gate preserves exact odd x grid and left anchor threshold`() {
        val width = 108
        val height = 160
        val anchor = Bounds(3, 5, 103, 155)
        for (darkSamples in listOf(28, 29)) {
            val colors = IntArray(width * height) { index ->
                val x = index % width
                // Unsampled odd offsets are all black: they must not change the row score.
                if (x >= anchor.left && ((x - anchor.left) % 2 == 1 || x < anchor.left + darkSamples * 2))
                    0xFF101010.toInt() else 0xFFFFFFFF.toInt()
            }
            val samples = ChoicePixelSampler(reader(width, height, colors))
            assertEquals(darkSamples / 50f, samples.darkRatioInRow(anchor.left, anchor.right, anchor.top))
            assertEquals(darkSamples == 29, samples.mayContainDarkPanelRows(anchor, 59, 3, 0.58f))
        }
    }

    @Test
    fun `absent choices copy only sparse left anchor bands`() {
        val reads = mutableListOf<Bounds>()
        val pixels = FramePixelReader(1920, 1080) { data, bounds ->
            reads.add(bounds)
            data.fill(0xFFFFFFFF.toInt())
        }
        val anchor = Bounds(249, 220, 543, 730)
        assertFalse(ChoicePixelSampler(pixels).mayContainDarkPanelRows(anchor, 59, 3, 0.58f))
        assertEquals(10, reads.size)
        assertEquals(40, reads.sumOf { it.height })
        assertTrue(reads.all { it.left == anchor.left && it.right == anchor.right && it.height == 4 })
        assertTrue(reads.all { anchor.contains(it) })
    }

    @Test
    fun `possible short dark artwork retains full validation and reuses prepared pixels`() {
        var copies = 0
        val pixels = FramePixelReader(40, 180) { data, bounds ->
            copies++
            for (y in bounds.top until bounds.bottom) for (x in bounds.left until bounds.right) {
                data[(y - bounds.top) * bounds.width + x - bounds.left] =
                    if (y == 10) 0xFF101010.toInt() else 0xFFFFFFFF.toInt()
            }
        }
        val anchor = Bounds(3, 10, 37, 160)
        pixels.read(anchor)
        assertTrue(ChoicePixelSampler(pixels).mayContainDarkPanelRows(anchor, 59, 3, 0.58f))
        assertFalse(hasDensePanelRun(BooleanArray(150) { it == 0 }, 59, 3))
        assertEquals(1, copies)
    }

    @Test
    fun `uncertain geometry and parameters retain full detection without pixel reads`() {
        var copies = 0
        val samples = ChoicePixelSampler(FramePixelReader(40, 180) { _, _ -> copies++ })
        val anchor = Bounds(3, 10, 37, 160)
        for (bounds in listOf(Bounds(3, 10, 3, 160), Bounds(-1, 10, 37, 160), Bounds(3, 10, 37, 200))) {
            assertTrue(samples.mayContainDarkPanelRows(bounds, 59, 3, 0.58f))
        }
        assertTrue(samples.mayContainDarkPanelRows(anchor, 0, 3, 0.58f))
        assertTrue(samples.mayContainDarkPanelRows(anchor, 151, 3, 0.58f))
        assertTrue(samples.mayContainDarkPanelRows(anchor, 59, -1, 0.58f))
        assertTrue(samples.mayContainDarkPanelRows(anchor, 59, 59, 0.58f))
        assertTrue(samples.mayContainDarkPanelRows(anchor, 59, 3, Float.NaN))
        assertTrue(samples.mayContainDarkPanelRows(anchor, 59, 3, 1.01f))
        assertEquals(0, copies)
    }

    /** Independent dense row-run oracle, including the original trailing-gap trimming. */
    private fun hasDensePanelRun(rows: BooleanArray, minimumHeight: Int, maximumGapRows: Int): Boolean {
        var start = -1
        var lastDark = -1
        var gap = 0
        for (y in rows.indices) {
            if (rows[y]) {
                if (start < 0) start = y
                lastDark = y
                gap = 0
            } else if (start >= 0 && ++gap > maximumGapRows) {
                if (lastDark - start + 1 >= minimumHeight) return true
                start = -1
                gap = 0
            }
        }
        return start >= 0 && lastDark - start + 1 >= minimumHeight
    }

    private fun reader(width: Int, height: Int, colors: IntArray) = FramePixelReader(width, height) { data, bounds ->
        for (y in bounds.top until bounds.bottom) {
            colors.copyInto(data, (y - bounds.top) * bounds.width, y * width + bounds.left, y * width + bounds.right)
        }
    }

    private fun legacyRatio(colors: IntArray, width: Int, height: Int, rect: Bounds, step: Int,
                            predicate: (Int) -> Boolean): Float {
        // Independent equivalent of the legacy Rect.intersect screen clipping.
        val bounds = Bounds(maxOf(0, rect.left), maxOf(0, rect.top),
            minOf(width, rect.right), minOf(height, rect.bottom))
        if (bounds.left >= bounds.right || bounds.top >= bounds.bottom) return 0f
        var matched = 0
        var total = 0
        for (y in bounds.top until bounds.bottom step step) for (x in bounds.left until bounds.right step step) {
            if (predicate(colors[y * width + x])) matched++
            total++
        }
        return if (total == 0) 0f else matched.toFloat() / total
    }

    private fun legacyDark(pixel: Int): Boolean {
        val r = (pixel shr 16) and 255
        val g = (pixel shr 8) and 255
        val b = pixel and 255
        return (0.299f * r + 0.587f * g + 0.114f * b).toInt() < 80
    }

    private fun legacyBorder(pixel: Int): Boolean {
        val r = (pixel shr 16) and 255
        val g = (pixel shr 8) and 255
        val b = pixel and 255
        val spread = maxOf(r, g, b) - minOf(r, g, b)
        return (r >= 175 && g >= 185 && b >= 190 && spread <= 82) ||
            (r >= 80 && g >= 125 && b >= 150 && b >= r + 24 && g >= r + 12) ||
            (r >= 110 && g >= 150 && b >= 170 && b >= r + 18 && spread <= 115)
    }
}
