package com.fgogotran.ocr

import com.fgogotran.text.EmphasisRange
import kotlin.test.*

class MlKitEmphasisTest {
    private fun symbol(text:String,x:Int,top:Int=30,bottom:Int=70)=MlKitEmphasis.Symbol(text,x-10,top,x+10,bottom)
    private val dots=listOf(OcrEmphasisDot(20f,30f,70f,30f,70f),OcrEmphasisDot(60f,30f,70f,30f,70f))

    @Test fun `native Japanese symbol boxes map verified dots to original text`() {
        assertEquals(listOf(EmphasisRange(0,2)),MlKitEmphasis.ranges("本当。",0,20,140,80,
            listOf(symbol("本",20),symbol("当",60),symbol("。",100)),dots))
    }
    @Test fun `ML Kit element spaces preserve original UTF16 offsets`() {
        val text="本　当 。"
        val ranges=MlKitEmphasis.ranges(text,0,20,140,80,listOf(symbol("本",20),symbol("当",60),symbol("。",100)),dots)
        assertEquals(listOf(EmphasisRange(0,3)),ranges)
        assertEquals("本　当",text.substring(ranges.single().start,ranges.single().endExclusive))
    }
    @Test fun `Latin letters retain separate real symbol centers and no whitespace dots`() {
        val symbols=listOf(symbol("G",20),symbol("o",60),symbol("n",140),symbol("o",180),symbol("w",220),symbol("!",260))
        val points=listOf(20,60,140,180,220).map { OcrEmphasisDot(it.toFloat(),30f,70f,30f,70f) }
        assertEquals(listOf(EmphasisRange(0,6)),MlKitEmphasis.ranges("Go now!",0,20,300,80,symbols,points))
    }
    @Test fun `missing or estimated whole word boxes cannot invent character positions`() {
        val real=listOf(symbol("本",20),symbol("当",60),symbol("。",100))
        assertTrue(MlKitEmphasis.ranges("本当。",0,20,140,80,real.drop(1),dots).isEmpty())
        assertTrue(MlKitEmphasis.ranges("本当。",0,20,140,80,listOf(symbol("本当",40),symbol("。",100)),dots).isEmpty())
        assertTrue(MlKitEmphasis.ranges("本当。",0,20,140,80,emptyList(),dots).isEmpty())
    }
    @Test fun `ruby symbols on another row do not contaminate main anchors`() {
        val symbols=listOf(symbol("本",20),symbol("当",60),symbol("。",100),symbol("ほ",20,0,12))
        assertEquals(listOf(EmphasisRange(0,2)),MlKitEmphasis.ranges("本当。",0,20,140,80,symbols,dots))
        assertTrue(MlKitEmphasis.ranges("本当。",0,120,140,180,symbols,dots).isEmpty())
    }
    @Test fun `combining characters and supplementary characters stay whole`() {
        assertEquals(listOf(EmphasisRange(0,4)),MlKitEmphasis.ranges("e\u0301𠮷",0,20,100,80,
            listOf(symbol("e\u0301",20),symbol("𠮷",60)),dots))
    }
}
