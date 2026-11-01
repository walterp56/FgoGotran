package com.fgogotran.overlay

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BilingualChoiceGeometryTest {
    @Test fun `Chinese and English readings gain preferred height without expanding the plane`() {
        for ((bodyTop, lastInkBottom, readingInk) in listOf(
            Triple(19f, 100f, RubyEndAlignment.InkBounds(0f, -17f, 80f, 1f)),
            Triple(20f, 99f, RubyEndAlignment.InkBounds(0f, -15f, 90f, 4f))
        )) {
            val shift = BilingualChoiceGeometry.downwardShift(lastInkBottom, 111f, 4f, 1f)
            val gap = 53f * RubyTextPainter.GAP_RATIO
            fun fitReading(availableHeight: Float) = assertNotNull(RubyEndAlignment.fit(
                21.2f, 200f, 0f, availableHeight
            ) { size ->
                val factor = size / 21.2f
                RubyEndAlignment.InkBounds(readingInk.left * factor, readingInk.top * factor,
                    readingInk.right * factor, readingInk.bottom * factor)
            })
            assertTrue(fitReading(bodyTop - gap).textSize < 21.2f)
            assertEquals(21.2f, fitReading(bodyTop + shift - gap).textSize)
            assertEquals(6f, shift)
            assertTrue(lastInkBottom + shift + 4f <= 111f)
        }
    }

    @Test fun `Japanese descender and shadow limit movement at the existing lower edge`() {
        val shift = BilingualChoiceGeometry.downwardShift(104.5f, 111f, 4f, 1f)
        assertEquals(2.5f, shift)
        assertEquals(111f, 104.5f + shift + 4f)
    }

    @Test fun `no bottom clearance means no additional movement`() {
        assertEquals(0f, BilingualChoiceGeometry.downwardShift(107f, 111f, 4f, 1f))
        assertEquals(0f, BilingualChoiceGeometry.downwardShift(108f, 111f, 4f, 1f))
    }

    @Test fun `both baselines move together and preserve their gap across viewport scales`() {
        for (scale in listOf(2f / 3f, 1f, 2f, 4f)) {
            val shift = BilingualChoiceGeometry.downwardShift(100f * scale, 111f * scale, 4f * scale, scale)
            val translationBaseline = 65f * scale
            val originalBaseline = 100f * scale
            assertEquals(6f * scale, shift, 0.001f)
            assertEquals(35f * scale,
                (originalBaseline + shift) - (translationBaseline + shift), 0.001f)
            val limited = BilingualChoiceGeometry.downwardShift(104.5f * scale, 111f * scale, 4f * scale, scale)
            assertEquals(2.5f * scale, limited, 0.001f)
        }
    }

}
