package com.fgogotran.battle

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BattleSubtitlePixelGateTest {
    @Test fun `first frame is read and unchanged frames inside the window are skipped`() {
        val gate = BattleSubtitlePixelGate()
        val pixels = outlinedCaption(300..430)

        assertTrue(gate.shouldRecognize(1920, 1080, pixels, 1_000L, false))
        assertFalse(gate.shouldRecognize(1920, 1080, pixels, 1_100L, false))
        assertFalse(gate.shouldRecognize(1920, 1080, pixels, 1_400L, false))
    }

    @Test fun `periodic safety net rereads an unchanged caption after the window`() {
        val gate = BattleSubtitlePixelGate()
        val pixels = outlinedCaption(300..430)

        assertTrue(gate.shouldRecognize(1920, 1080, pixels, 1_000L, false))
        assertTrue(gate.shouldRecognize(1920, 1080, pixels, 1_500L, false))
    }

    @Test fun `changed caption pattern triggers a read`() {
        val gate = BattleSubtitlePixelGate()
        assertTrue(gate.shouldRecognize(1920, 1080, outlinedCaption(300..430), 1_000L, false))
        assertFalse(gate.shouldRecognize(1920, 1080, outlinedCaption(300..430), 1_100L, false))
        assertTrue(gate.shouldRecognize(1920, 1080, outlinedCaption(900..1100), 1_200L, false))
    }

    @Test fun `confirmation requirement always reads and reset forces a fresh read`() {
        val gate = BattleSubtitlePixelGate()
        val pixels = outlinedCaption(300..430)

        assertTrue(gate.shouldRecognize(1920, 1080, pixels, 1_000L, false))
        assertTrue(gate.shouldRecognize(1920, 1080, pixels, 1_100L, true))

        gate.reset()
        assertTrue(gate.shouldRecognize(1920, 1080, pixels, 1_200L, false))
    }

    @Test fun `blank captions are stable and a later caption triggers a read`() {
        val gate = BattleSubtitlePixelGate()

        assertTrue(gate.shouldRecognize(1920, 1080, blank(), 1_000L, false))
        assertFalse(gate.shouldRecognize(1920, 1080, blank(), 1_100L, false))
        assertTrue(gate.shouldRecognize(1920, 1080, outlinedCaption(300..430), 1_200L, false))
    }

    private fun outlinedCaption(horizontalRange: IntRange): (Int, Int) -> Int = { x, y ->
        when {
            y in 739..765 && x in horizontalRange -> WHITE
            y in 735..769 && x in (horizontalRange.first - 4)..(horizontalRange.last + 4) -> BLACK
            else -> MID_GREY
        }
    }

    private fun blank(): (Int, Int) -> Int = { _, _ -> MID_GREY }

    companion object {
        private const val WHITE = -0x1
        private const val BLACK = -0x1000000
        private const val MID_GREY = -0x777778
    }
}
