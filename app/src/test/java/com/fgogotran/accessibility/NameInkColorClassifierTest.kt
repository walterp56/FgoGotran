package com.fgogotran.accessibility

import com.fgogotran.ocr.FgoStoryTextPalette
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NameInkColorClassifierTest {
    @Test
    fun `classifies the cyan name palette used by rendering`() {
        assertEquals(NameInkColor.CYAN, classifyNameInkColor(80, 235, 235))
        assertTrue(isFgoCyanTextColor(80, 235, 235))
    }

    @Test
    fun `keeps the existing name palettes distinct`() {
        assertEquals(NameInkColor.NEUTRAL, classifyNameInkColor(245, 245, 240))
        assertEquals(NameInkColor.RED, classifyNameInkColor(220, 0, 0))
        assertEquals(NameInkColor.YELLOW_GREEN, classifyNameInkColor(197, 227, 94))
    }

    @Test
    fun `removed gold and orange are not treated as red name ink`() {
        assertNull(classifyNameInkColor(229, 188, 124))
        assertNull(classifyNameInkColor(255, 209, 76))
    }

    @Test
    fun `all approved additional colours are measurable name ink`() {
        FgoStoryTextPalette.samples.drop(4).forEach { sample ->
            assertEquals(sample.family, classifyNameInkColor(
                (sample.rgb shr 16) and 0xff, (sample.rgb shr 8) and 0xff, sample.rgb and 0xff
            ))
        }
    }

    @Test
    fun `red name edges are not misclassified as brown`() {
        assertEquals(NameInkColor.RED, classifyNameInkColor(220, 60, 60))
        assertEquals(NameInkColor.RED, classifyNameInkColor(255, 64, 64))
    }

    @Test
    fun `rejects dark blue background pixels as cyan name ink`() {
        assertFalse(isFgoCyanTextColor(25, 70, 125))
        assertNull(classifyNameInkColor(25, 70, 125))
    }

    @Test
    fun `uses the same cyan threshold boundary as the text mask`() {
        assertTrue(isFgoCyanTextColor(105, 140, 140))
        assertFalse(isFgoCyanTextColor(106, 140, 140))
        assertFalse(isFgoCyanTextColor(80, 139, 235))
        assertFalse(isFgoCyanTextColor(80, 235, 139))
    }
}
