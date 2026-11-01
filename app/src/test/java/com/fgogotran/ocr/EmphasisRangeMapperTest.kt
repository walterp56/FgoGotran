package com.fgogotran.ocr

import com.fgogotran.text.EmphasisRange
import kotlin.test.*

class EmphasisRangeMapperTest {
    private val positions=listOf(OcrCharacterPosition("本",20f),OcrCharacterPosition("当",60f),OcrCharacterPosition("。",100f))
    @Test fun `dots map to corresponding recognized characters without extra OCR`() {
        assertEquals(listOf(EmphasisRange(0,2)),EmphasisRangeMapper.map("本当。",positions,
            listOf(OcrEmphasisDot(20f,30f,70f),OcrEmphasisDot(60f,30f,70f)),30,70))
    }
    @Test fun `isolated verified dot can map one character`() {
        assertEquals(listOf(EmphasisRange(1,2)),EmphasisRangeMapper.map("本当。",positions,
            listOf(OcrEmphasisDot(60f,30f,70f)),30,70))
    }
    @Test fun `other rows punctuation and ambiguous positions cannot create dots`() {
        assertTrue(EmphasisRangeMapper.map("本当。",positions,
            listOf(OcrEmphasisDot(100f,30f,70f),OcrEmphasisDot(20f,100f,140f)),30,70).isEmpty())
        assertTrue(EmphasisRangeMapper.map("本当。",positions.drop(1),listOf(OcrEmphasisDot(60f,30f,70f)),30,70).isEmpty())
    }
    @Test fun `source ruby insertion shifts emphasis without changing Japanese display`() {
        val formatted="ここは異聞帶〈ロストベルト〉、現実ではない。"
        val line=EmphasisSourceMapper.Line("ここは異聞帶、現実ではない。",listOf(EmphasisRange(7,9)))
        val ranges=EmphasisSourceMapper.map(formatted,listOf(line))
        assertEquals("現実",formatted.substring(ranges.single().start,ranges.single().endExclusive))
    }
    @Test fun `duplicate source lines are not guessed`() {
        assertTrue(EmphasisSourceMapper.map("同じ\n同じ",listOf(
            EmphasisSourceMapper.Line("同じ",listOf(EmphasisRange(0,2))))).isEmpty())
    }
    @Test fun `detached upper stroke of a main kanji is not an emphasis dot`() {
        assertTrue(EmphasisRangeMapper.map("本当。",positions,
            listOf(OcrEmphasisDot(20f,42f,70f)),30,70).isEmpty())
    }
    @Test fun `verified ink row survives detector padding and short kana ink`() {
        val dots=listOf(OcrEmphasisDot(20f,42f,70f,30f,70f),OcrEmphasisDot(60f,40f,68f,30f,70f))
        assertEquals(listOf(EmphasisRange(0,2)),EmphasisRangeMapper.map("本当。",positions,dots,12,82))
        // Without verified multi-dot row evidence, the isolated-stroke protection still applies.
        assertTrue(EmphasisRangeMapper.map("本当。",positions,
            listOf(OcrEmphasisDot(20f,42f,70f)),12,82).isEmpty())
    }
    @Test fun `verified dots cannot transfer to another recognized row`() {
        assertTrue(EmphasisRangeMapper.map("本当。",positions,
            listOf(OcrEmphasisDot(20f,130f,170f,130f,170f)),30,70).isEmpty())
    }
}
