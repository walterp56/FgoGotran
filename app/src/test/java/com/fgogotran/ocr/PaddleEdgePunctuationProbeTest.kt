package com.fgogotran.ocr

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PaddleEdgePunctuationProbeTest {
    @Test
    fun `unchanged bright text requires punctuation outside the detector box`() {
        val fixture = Fixture()
        fixture.body(0xF5F5F0)
        assertFalse(fixture.evidence())
        fixture.mark(0xF5F5F0)
        assertTrue(fixture.evidence())
    }

    @Test
    fun `dim approved story colours recover matching edge evidence only in dialogue`() {
        listOf(0xB86349, 0x8753D1).forEach { rgb ->
            val fixture = Fixture()
            fixture.body(rgb)
            fixture.mark(rgb)
            assertFalse(fixture.evidence())
            assertTrue(fixture.evidence(allowStoryColors = true))
        }
    }

    @Test
    fun `story colour evidence still needs a supported body and punctuation geometry`() {
        val noBody = Fixture()
        noBody.mark(0xB86349)
        assertFalse(noBody.evidence(allowStoryColors = true))

        val unrelated = Fixture()
        unrelated.body(0xB86349)
        unrelated.mark(0x8753D1)
        assertFalse(unrelated.evidence(allowStoryColors = true))

        val largeArt = Fixture()
        largeArt.body(0x8753D1)
        largeArt.rect(0, 4, 56, 78, 0x8753D1)
        assertFalse(largeArt.evidence(allowStoryColors = true))

        val belowRow = Fixture()
        belowRow.body(0xB86349)
        belowRow.rect(52, 68, 56, 78, 0xB86349)
        assertFalse(belowRow.evidence(allowStoryColors = true))
    }

    private class Fixture {
        private val width = 240
        private val height = 80
        private val pixels = IntArray(width * height) { 0xFF1B3355.toInt() }

        fun body(rgb: Int) = rect(60, 20, 180, 60, rgb)
        fun mark(rgb: Int) = rect(52, 24, 56, 44, rgb)

        fun rect(left: Int, top: Int, right: Int, bottom: Int, rgb: Int) {
            for (y in top until bottom) for (x in left until right) {
                pixels[y * width + x] = rgb or (0xff shl 24)
            }
        }

        fun evidence(allowStoryColors: Boolean = false): Boolean =
            PaddleEdgePunctuationProbe.hasEvidence(
                pixels, width, height, 60, 20, 180, 60, allowStoryColors
            )
    }
}
