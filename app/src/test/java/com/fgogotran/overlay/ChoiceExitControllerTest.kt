package com.fgogotran.overlay

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChoiceExitControllerTest {
    @Test fun `covers gradually follow text opacity without an opaque retirement tail`() {
        for ((selected, start, end) in listOf(Triple(false, 100L, 600L), Triple(true, 700L, 1_300L))) {
            var previousText = 255
            var previousCover = 255
            for (time in 0L..end) {
                val text = ChoiceExitController.alpha(selected, time)
                val cover = ChoiceExitController.backgroundAlpha(selected, time)
                val extra = ChoiceExitController.additionalBackgroundAlpha(selected, time)
                assertTrue(text in 0..255 && cover in text..255 && extra in 0..255)
                assertTrue(text <= previousText && cover <= previousCover)
                val composedCover = text + extra * (255 - text) / 255
                assertTrue(kotlin.math.abs(cover - composedCover) <= 1, "cover composition at $selected/$time")
                if (time <= start) assertEquals(0, extra)
                previousText = text; previousCover = cover
            }
            assertEquals(0, ChoiceExitController.backgroundAlpha(selected, end))
            assertEquals(0, ChoiceExitController.additionalBackgroundAlpha(selected, end))
        }
        assertEquals(255, ChoiceExitController.backgroundAlpha(false, 225))
        assertEquals(255, ChoiceExitController.backgroundAlpha(true, 850))
        assertEquals(254, ChoiceExitController.backgroundAlpha(false, 246))
        assertEquals(254, ChoiceExitController.backgroundAlpha(true, 876))
        assertEquals(126, ChoiceExitController.backgroundAlpha(false, 350))
        assertEquals(126, ChoiceExitController.backgroundAlpha(true, 1_000))
        for ((selected, end) in listOf(false to 600L, true to 1_300L)) {
            assertEquals(255, ChoiceExitController.backgroundAlpha(selected, Long.MIN_VALUE))
            assertTrue(ChoiceExitController.backgroundAlpha(selected, end - 100L) < 32)
            assertEquals(0, ChoiceExitController.backgroundAlpha(selected, end - 20L))
            assertEquals(0, ChoiceExitController.backgroundAlpha(selected, Long.MAX_VALUE))
            assertEquals(0, ChoiceExitController.alpha(selected, end - 20L))
            assertEquals(0, ChoiceExitController.additionalBackgroundAlpha(selected, end - 20L),
                "no empty cover once translated text has disappeared")
        }
    }

    @Test fun `selection echo expands and fades without extending the existing retirement`() {
        assertEquals(0, ChoiceExitController.selectionAlpha(-1))
        assertEquals(0, ChoiceExitController.selectionAlpha(0))
        assertEquals(0, ChoiceExitController.selectionAlpha(Long.MIN_VALUE))
        for (time in listOf(1L, 33L, 60L, 100L)) assertEquals(255, ChoiceExitController.selectionAlpha(time))
        assertEquals(191, ChoiceExitController.selectionAlpha(150))
        assertEquals(127, ChoiceExitController.selectionAlpha(200))
        assertEquals(63, ChoiceExitController.selectionAlpha(250))
        assertEquals(1, ChoiceExitController.selectionAlpha(299))
        assertEquals(0, ChoiceExitController.selectionAlpha(300))
        assertEquals(0, ChoiceExitController.selectionAlpha(Long.MAX_VALUE))
        assertEquals(1f, ChoiceExitController.selectionScale(-1))
        assertEquals(1f, ChoiceExitController.selectionScale(0))
        assertTrue(kotlin.math.abs(1.11f - ChoiceExitController.selectionScale(33)) < 0.0001f)
        assertEquals(1.5f, ChoiceExitController.selectionScale(150))
        assertEquals(2f, ChoiceExitController.selectionScale(300))
        assertEquals(2f, ChoiceExitController.selectionScale(Long.MAX_VALUE))
        assertEquals(1_300L, ChoiceExitController.DURATION_MS)
    }

    @Test fun `a single selected row updates its echo then sleeps until the fade`() {
        for (multiple in listOf(false, true)) {
            for (time in listOf(0L, 60L, 150L, 299L)) {
                assertEquals(0L, ChoiceExitController.nextUpdateDelayMs(time, multiple, true))
            }
            assertEquals(100L, ChoiceExitController.nextUpdateDelayMs(600L, multiple, true))
            assertEquals(null, ChoiceExitController.nextUpdateDelayMs(1_300L, multiple, true))
        }
        assertEquals(400L, ChoiceExitController.nextUpdateDelayMs(300L, false, true))
        assertEquals(0L, ChoiceExitController.nextUpdateDelayMs(300L, true, true))
        assertEquals(0L, ChoiceExitController.nextUpdateDelayMs(400L, true, true))
        assertEquals(300L, ChoiceExitController.nextUpdateDelayMs(400L, false, true))
    }

    @Test fun `video calibrated fades lose opacity faster first and retire unselected rows first`() {
        for ((time, selected, unselected) in listOf(
            Triple(0L, 255, 255), Triple(100L, 255, 255), Triple(225L, 255, 143),
            Triple(350L, 255, 63), Triple(475L, 255, 15), Triple(600L, 255, 0),
            Triple(700L, 255, 0), Triple(850L, 143, 0), Triple(1_000L, 63, 0),
            Triple(1_150L, 15, 0), Triple(1_300L, 0, 0), Triple(2_000L, 0, 0)
        )) {
            assertEquals(selected, ChoiceExitController.alpha(true, time), "selected at $time")
            assertEquals(unselected, ChoiceExitController.alpha(false, time), "unselected at $time")
        }
    }

    @Test fun `hold intervals do not request continuous display frames`() {
        assertEquals(100L, ChoiceExitController.nextUpdateDelayMs(0, true))
        assertEquals(0L, ChoiceExitController.nextUpdateDelayMs(250, true))
        assertEquals(0L, ChoiceExitController.nextUpdateDelayMs(400, true))
        assertEquals(100L, ChoiceExitController.nextUpdateDelayMs(600, true))
        assertEquals(0L, ChoiceExitController.nextUpdateDelayMs(1_000, true))
        assertEquals(null, ChoiceExitController.nextUpdateDelayMs(1_300, true))
        assertEquals(700L, ChoiceExitController.nextUpdateDelayMs(0, false))
    }

    @Test fun `late gesture callbacks or slow frames catch up rather than extending the exit`() {
        assertEquals(20L, ChoiceExitController.nextUpdateDelayMs(80, true))
        assertEquals(15, ChoiceExitController.alpha(true, 1_150))
        assertEquals(0, ChoiceExitController.alpha(true, Long.MAX_VALUE))
        assertEquals(255, ChoiceExitController.alpha(true, -100))
        assertEquals(255, ChoiceExitController.alpha(true, Long.MIN_VALUE))
    }
}
