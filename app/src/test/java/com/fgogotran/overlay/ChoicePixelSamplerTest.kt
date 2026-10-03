package com.fgogotran.overlay

import com.fgogotran.util.FramePixelReader
import com.fgogotran.util.FramePixelReader.Bounds
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

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
