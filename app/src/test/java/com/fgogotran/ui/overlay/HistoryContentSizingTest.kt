package com.fgogotran.ui.overlay

import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals

class HistoryContentSizingTest {
    @Test
    fun `connected normal phone retains its exact 18sp and 14sp pixel sizes`() {
        // Read-only device checks: 2340x1080 landscape, density 480dpi, font_scale 1.0.
        val sizing = HistoryContentSizing.forScreen(2340, 1080)
        val phoneDensity = 480f / 160f
        assertEquals(18f * phoneDensity, sizing.translationTextSizePx)
        assertEquals(14f * phoneDensity, sizing.originalTextSizePx)
    }

    @Test
    fun `all normal phone content paddings margins and line spacing remain unchanged`() {
        val sizing = HistoryContentSizing.forScreen(2340, 1080)
        // Text padding, bilingual margins, speaker margin, row gap and list padding.
        for (baselineDp in listOf(0, 1, 3, 4, 6, 10, 12, 14, 20, 28)) {
            assertEquals((baselineDp * 3f).roundToInt(), sizing.spacingPx(baselineDp))
        }
        assertEquals(54f * 1.7f, sizing.translationTextSizePx * 1.7f)
    }

    @Test
    fun `four K 640dpi emulator uses exactly twice the phone content size`() {
        val phone = HistoryContentSizing.forScreen(2340, 1080)
        val emulator = HistoryContentSizing.forScreen(3840, 2400)
        assertEquals(108f, emulator.translationTextSizePx)
        assertEquals(84f, emulator.originalTextSizePx)
        assertEquals(phone.translationTextSizePx * 2f, emulator.translationTextSizePx)
        assertEquals(phone.originalTextSizePx * 2f, emulator.originalTextSizePx)
        for (spacing in listOf(1, 3, 4, 6, 10, 12, 14, 20, 28)) {
            assertEquals(phone.spacingPx(spacing) * 2, emulator.spacingPx(spacing))
        }
    }

    @Test
    fun `letterbox height does not introduce a second scale`() {
        assertEquals(HistoryContentSizing.forScreen(3840, 2160), HistoryContentSizing.forScreen(3840, 2400))
        assertEquals(HistoryContentSizing.forScreen(1920, 1080), HistoryContentSizing.forScreen(2340, 1080))
        assertEquals(HistoryContentSizing.forScreen(1920, 1080), HistoryContentSizing.forScreen(3840, 1080))
    }

    @Test
    fun `common proportional phone resolutions keep the existing baseline appearance`() {
        for ((width, height, density) in listOf(
            Triple(1560, 720, 2f), Triple(2340, 1080, 3f), Triple(3120, 1440, 4f)
        )) {
            val sizing = HistoryContentSizing.forScreen(width, height)
            assertEquals(18f * density, sizing.translationTextSizePx, 0.0001f)
            assertEquals(14f * density, sizing.originalTextSizePx, 0.0001f)
            assertEquals((20 * density).roundToInt(), sizing.spacingPx(20))
        }
    }

    @Test
    fun `larger viewports scale without the former 1 point 4 cap`() {
        val sizing = HistoryContentSizing.forScreen(7680, 4320)
        assertEquals(216f, sizing.translationTextSizePx)
        assertEquals(168f, sizing.originalTextSizePx)
        assertEquals(240, sizing.spacingPx(20))
    }

    @Test
    fun `fractional viewport scales keep the bilingual font ratio`() {
        for ((width, height) in listOf(1024 to 576, 1280 to 720, 2560 to 1440, 2880 to 1800)) {
            val sizing = HistoryContentSizing.forScreen(width, height)
            assertEquals(14f / 18f, sizing.originalTextSizePx / sizing.translationTextSizePx, 0.0001f)
        }
    }

    @Test
    fun `invalid screen bounds cannot create negative content dimensions`() {
        for ((width, height) in listOf(0 to 1080, 1920 to 0, -1 to 1080, 1920 to -1)) {
            val sizing = HistoryContentSizing.forScreen(width, height)
            assertEquals(0f, sizing.translationTextSizePx)
            assertEquals(0f, sizing.originalTextSizePx)
            assertEquals(0, sizing.spacingPx(20))
        }
    }
}
