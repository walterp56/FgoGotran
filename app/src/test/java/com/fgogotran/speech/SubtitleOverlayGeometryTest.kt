package com.fgogotran.speech

import com.fgogotran.data.SettingsRepository
import kotlin.test.Test
import kotlin.test.assertEquals

class SubtitleOverlayGeometryTest {
    @Test
    fun `subtitle size is limited to the supported range`() {
        val cases = mapOf(
            1 to 14,
            14 to 14,
            20 to 20,
            32 to 32,
            80 to 32
        )

        cases.forEach { (input, expected) ->
            assertEquals(expected, SettingsRepository.normalizeLiveVoiceSubtitleFontSizeSp(input))
        }
    }

    @Test
    fun `maximum text width uses safe screen width`() {
        assertEquals(
            882,
            SubtitleOverlayGeometry.maximumTextWidth(
                screenWidth = 1_000,
                insetLeft = 10,
                insetRight = 10,
                coverage = 0.9f
            )
        )
    }

    @Test
    fun `default position is centered below top inset`() {
        assertEquals(
            SubtitleOverlayPosition(x = 300, y = 74),
            SubtitleOverlayGeometry.defaultPosition(
                viewWidth = 400,
                viewHeight = 100,
                screenWidth = 1_000,
                screenHeight = 600,
                insetLeft = 10,
                insetTop = 24,
                insetRight = 10,
                insetBottom = 30,
                topMargin = 50
            )
        )
    }

    @Test
    fun `drag position is clamped inside system insets`() {
        val common = SubtitleGeometryCase(
            viewWidth = 300,
            viewHeight = 100,
            screenWidth = 1_000,
            screenHeight = 600,
            insetLeft = 10,
            insetTop = 24,
            insetRight = 20,
            insetBottom = 30
        )

        assertEquals(
            SubtitleOverlayPosition(x = 10, y = 24),
            common.clamp(x = -500, y = -500)
        )
        assertEquals(
            SubtitleOverlayPosition(x = 680, y = 470),
            common.clamp(x = 5_000, y = 5_000)
        )
    }

    @Test
    fun `growing and shrinking captions keep the default reading edge`() {
        for ((screenWidth, screenHeight) in listOf(1_000 to 600, 600 to 1_000)) {
            val anchorWidth = SubtitleOverlayGeometry.maximumTextWidth(screenWidth, 10, 20, 0.9f)
            val expectedX = 10 + (screenWidth - 30 - anchorWidth) / 2
            for (width in listOf(100, anchorWidth, 200, 100)) {
                assertEquals(SubtitleOverlayPosition(expectedX, 74), SubtitleOverlayGeometry.defaultPosition(
                    viewWidth = width, viewHeight = 100, screenWidth = screenWidth, screenHeight = screenHeight,
                    insetLeft = 10, insetTop = 24, insetRight = 20, insetBottom = 30,
                    topMargin = 50, anchorWidth = anchorWidth))
            }
        }
    }

    @Test
    fun `reading anchor never prevents clamping the actual box to the safe screen`() {
        assertEquals(SubtitleOverlayPosition(180, 470), SubtitleOverlayGeometry.defaultPosition(
            viewWidth = 800, viewHeight = 100, screenWidth = 1_000, screenHeight = 600,
            insetLeft = 10, insetTop = 24, insetRight = 20, insetBottom = 30,
            topMargin = 1_000, anchorWidth = 100))
    }

    private data class SubtitleGeometryCase(
        val viewWidth: Int,
        val viewHeight: Int,
        val screenWidth: Int,
        val screenHeight: Int,
        val insetLeft: Int,
        val insetTop: Int,
        val insetRight: Int,
        val insetBottom: Int
    ) {
        fun clamp(x: Int, y: Int): SubtitleOverlayPosition =
            SubtitleOverlayGeometry.clampPosition(
                x = x,
                y = y,
                viewWidth = viewWidth,
                viewHeight = viewHeight,
                screenWidth = screenWidth,
                screenHeight = screenHeight,
                insetLeft = insetLeft,
                insetTop = insetTop,
                insetRight = insetRight,
                insetBottom = insetBottom
            )
    }
}
