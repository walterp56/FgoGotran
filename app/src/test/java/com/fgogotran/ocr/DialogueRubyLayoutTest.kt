package com.fgogotran.ocr

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DialogueRubyLayoutTest {
    private fun box(left: Int, top: Int, width: Int, height: Int) =
        DialogueRubyLayout.Box(left, top, left + width, top + height)

    @Test
    fun `fixed ruby bands are scaled relative to the exact crop`() {
        assertEquals(listOf(DialogueRubyLayout.Box(0, 15, 700, 48), DialogueRubyLayout.Box(0, 98, 700, 134)),
            DialogueRubyLayout.rubyBands(700, 219))
        assertEquals(listOf(DialogueRubyLayout.Box(0, 30, 1400, 96), DialogueRubyLayout.Box(0, 196, 1400, 268)),
            DialogueRubyLayout.rubyBands(1400, 438))
        assertNull(DialogueRubyLayout.rubyRow(box(30, 44, 26, 48), 700, 219))
    }

    @Test
    fun `recognizer positions attach after the full base and before its punctuation`() {
        val text = "我が小源にて起動せよ、大令呪。"
        val positions = text.mapIndexed { index, char -> OcrCharacterPosition(char.toString(), 20f + index * 40f) }
        assertEquals(4, DialogueRubyLayout.rubyInsertIndex(text, positions, box(90, 15, 60, 20)))
        assertEquals(14, DialogueRubyLayout.rubyInsertIndex(text, positions, box(490, 15, 110, 20)))
    }

    @Test
    fun `each second-row reading uses only that row's character positions`() {
        val text = "現の彼方、宙に等しき星の瞬。"
        val positions = text.mapIndexed { index, char -> OcrCharacterPosition(char.toString(), 20f + index * 40f) }
        assertEquals(1, DialogueRubyLayout.rubyInsertIndex(text, positions, box(0, 98, 35, 20)))
        assertEquals(6, DialogueRubyLayout.rubyInsertIndex(text, positions, box(210, 98, 35, 20)))
        assertEquals(13, DialogueRubyLayout.rubyInsertIndex(text, positions, box(490, 98, 35, 20)))
    }

    @Test
    fun `position offsets count punctuation tokens without inserting after them`() {
        val text = "……大令呪。───"
        val positions = listOf(OcrCharacterPosition("……", 20f), OcrCharacterPosition("大", 70f),
            OcrCharacterPosition("令", 110f), OcrCharacterPosition("呪", 150f),
            OcrCharacterPosition("。", 190f), OcrCharacterPosition("───", 240f))
        assertEquals(5, DialogueRubyLayout.rubyInsertIndex(text, positions, box(60, 15, 140, 20)))
        assertNull(DialogueRubyLayout.rubyInsertIndex(text, positions.dropLast(1), box(60, 15, 140, 20)))
    }

    @Test
    fun `many small readings cannot outweigh a single main row`() {
        val ruby = (0 until 9).map { box(20 + it * 35, 40, 20, 16) }
        assertEquals(44, DialogueRubyLayout.heightReference(ruby + box(10, 64, 620, 44)))
    }

    @Test
    fun `supports short lower words as well as long main lines`() {
        val boxes = listOf(box(20, 40, 75, 16), box(10, 62, 45, 44))
        assertEquals(44, DialogueRubyLayout.heightReference(boxes))
    }

    @Test
    fun `coherent main rows outweigh a tall merged outlier`() {
        val boxes = listOf(
            box(20, 40, 80, 16), box(10, 64, 540, 44),
            box(20, 125, 80, 16), box(10, 150, 540, 46),
            box(700, 40, 30, 16), box(690, 62, 80, 85)
        )
        assertEquals(46, DialogueRubyLayout.heightReference(boxes))
    }

    @Test
    fun `unrelated small text cannot manufacture a ruby pair`() {
        val boxes = listOf(
            box(800, 40, 20, 16), box(840, 41, 20, 16), box(10, 64, 200, 44)
        )
        assertEquals(44, DialogueRubyLayout.heightReference(boxes))
    }

    @Test
    fun `wide tall outliers cannot outweigh several coherent short main rows`() {
        val boxes = listOf(
            box(20, 40, 60, 16), box(10, 64, 60, 44),
            box(20, 125, 60, 16), box(10, 150, 60, 46),
            box(700, 40, 30, 16), box(690, 62, 900, 85)
        )
        assertEquals(46, DialogueRubyLayout.heightReference(boxes))
    }

    @Test
    fun `ordinary same-height rows retain the existing reference`() {
        assertEquals(44, DialogueRubyLayout.heightReference(listOf(
            box(10, 30, 620, 44), box(10, 120, 360, 44)
        )))
        assertEquals(44, DialogueRubyLayout.heightReference(listOf(box(10, 30, 180, 44))))
        assertEquals(0, DialogueRubyLayout.heightReference(emptyList()))
    }

    @Test
    fun `orders fragments left to right despite top-coordinate jitter`() {
        val boxes = listOf(box(80, 40, 20, 16), box(20, 43, 20, 16), box(50, 41, 20, 16))
        assertEquals(listOf(listOf(1, 2, 0)), DialogueRubyLayout.orderedRows(boxes, 44))
    }

    @Test
    fun `keeps annotations for different main rows separate`() {
        val boxes = listOf(
            box(80, 40, 20, 16), box(20, 43, 20, 16),
            box(60, 128, 20, 16), box(20, 125, 20, 16)
        )
        assertEquals(listOf(listOf(1, 0), listOf(3, 2)), DialogueRubyLayout.orderedRows(boxes, 44))
    }

    @Test
    fun `ordering retains widely spaced fragments without inventing a joined reading`() {
        val boxes = listOf(box(145, 41, 15, 16), box(20, 40, 15, 16))
        assertEquals(listOf(listOf(1, 0)), DialogueRubyLayout.orderedRows(boxes, 44))
    }

    @Test
    fun `reference and ordering scale with the screenshot instead of fixed Y`() {
        val boxes = listOf(box(80, 40, 20, 16), box(20, 43, 20, 16), box(10, 64, 620, 44))
        val scaled = boxes.map { DialogueRubyLayout.Box(it.left * 2, it.top * 2, it.right * 2, it.bottom * 2) }
        assertEquals(88, DialogueRubyLayout.heightReference(scaled))
        assertEquals(listOf(listOf(1, 0)), DialogueRubyLayout.orderedRows(scaled.take(2), 88))
        val shifted = boxes.map { it.copy(top = it.top + 137, bottom = it.bottom + 137) }
        assertEquals(44, DialogueRubyLayout.heightReference(shifted))
        assertEquals(listOf(listOf(1, 0)), DialogueRubyLayout.orderedRows(shifted.take(2), 44))
    }
}
