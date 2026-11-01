package com.fgogotran.overlay

import com.fgogotran.text.*
import kotlin.test.*

class EmphasisRenderTextTest {
    private fun parse(text:String,range:EmphasisRange=EmphasisRange(0,text.length)) =
        RubyRenderText.parse(EmphasisText.encode(text,listOf(range)))

    @Test fun `Chinese dots are centered separately on explicit characters`() {
        val text=parse("真正的理由。")
        assertEquals("真正的理由。",text.body)
        assertEquals(listOf(0,1,2,3,4),text.annotations.map { it.start })
        assertTrue(text.annotations.all { it.emphasis && it.reading=="•" && it.end==it.start+1 })
    }
    @Test fun `English dots are per word and skip spaces and punctuation`() {
        val text=parse("Wi, go!")
        assertEquals(listOf("Wi","go"),text.annotations.map { text.body.substring(it.start,it.end) })
    }
    @Test fun `accented English words get one dot without splitting combining marks`() {
        val text=parse("Adio\u0301s")
        assertEquals(1,text.annotations.size)
        assertEquals("Adio\u0301s",text.body.substring(text.annotations.single().start,text.annotations.single().end))
    }
    @Test fun `ruby wins only on an overlapping base and other dots remain`() {
        val raw="異聞帶〈Lostbelt〉與現實"
        val text=parse(raw)
        assertEquals("異聞帶與現實",text.body)
        assertEquals(1,text.annotations.count { !it.emphasis })
        // A range crossing the reading is conservatively omitted, never relocated.
        val other=parse(raw,EmphasisRange(raw.indexOf("現"),raw.length))
        assertEquals(listOf("現","實"),other.annotations.filter { it.emphasis }.map { other.body.substring(it.start,it.end) })
    }
    @Test fun `wrapping retains all dots and keeps controls out of measured body`() {
        val text=parse("real reason")
        val rows=text.annotateLines(listOf("real","reason")).map(RubyRenderText::parse)
        assertEquals(listOf("real","reason"),rows.map { it.body })
        assertEquals(2,rows.sumOf { it.annotations.count { a->a.emphasis } })
    }
    @Test fun `no dots are inferred from public wire punctuation`() {
        val text=RubyRenderText.parse("⟦This is literal⟧")
        assertEquals("⟦This is literal⟧",text.body);assertTrue(text.annotations.isEmpty())
    }
    @Test fun `English dot reveals when the complete word is visible`() {
        val text=parse("GO!")
        assertEquals(listOf(2),text.annotations.map { it.end })
    }
    @Test fun `world has one full word annotation rather than five letter dots`() {
        val text=parse("world")
        assertEquals(listOf(RubyRenderText.Annotation(0,5,"•",emphasis=true)),text.annotations)
    }
    @Test fun `contractions and hyphenated compounds keep one word dot`() {
        val text=parse("Let's go—it's a well-known, FGO2 story.")
        assertEquals(listOf("Let's","go","it's","a","well-known","FGO2","story"),
            text.annotations.map { text.body.substring(it.start,it.end) })
    }
    @Test fun `Latin words beside Chinese and Japanese do not merge their character dots`() {
        val text=parse("世界world世界、かなWi！")
        assertEquals(listOf("世","界","world","世","界","か","な","Wi"),
            text.annotations.map { text.body.substring(it.start,it.end) })
    }
    @Test fun `separate marked slices of an English word produce only one dot`() {
        val text=RubyRenderText.parse(EmphasisText.encode("world!",listOf(EmphasisRange(0,2),EmphasisRange(3,5))))
        assertEquals(listOf(RubyRenderText.Annotation(0,5,"•",emphasis=true)),text.annotations)
    }
}
