package com.fgogotran.text

import kotlin.test.*

class EmphasisTextTest {
    private fun marked(text: String, word: String) = EmphasisText.encode(text,
        listOf(EmphasisRange(text.indexOf(word),text.indexOf(word)+word.length)))

    @Test fun `wire notation has explicit endpoints and public text stays plain`() {
        val packed=marked("これが本当の理由だ。","本当の理由")
        assertEquals("これが⟦本当の理由⟧だ。",EmphasisText.wire(packed))
        val result=EmphasisText.decode(EmphasisText.fromResponse(packed,"這就是⟦真正的理由⟧。"))
        assertEquals("這就是真正的理由。",result.text)
        assertEquals("真正的理由",result.text.substring(result.ranges.single().start,result.ranges.single().endExclusive))
    }
    @Test fun `ordinary brackets are literal without source annotation ownership`() {
        val result=EmphasisText.decode(EmphasisText.fromResponse("普通の文","⟦ordinary brackets⟧"))
        assertEquals("⟦ordinary brackets⟧",result.text);assertTrue(result.ranges.isEmpty())
        assertFalse(EmphasisText.has(marked("これは⟦本物⟧だ。","本物")))
    }
    @Test fun `private transport delimiters round trip as literal source characters`() {
        val raw="x\uE100a\uE101\uE102y"
        assertEquals(raw,EmphasisText.plain(EmphasisText.literal(raw)))
        assertFalse(EmphasisText.has(EmphasisText.literal(raw)))
    }
    @Test fun `broken nested or absent markers retain all translated words`() {
        val source=marked("本当の理由","本当の理由")
        for ((raw,plain) in listOf("⟦真正的理由" to "真正的理由","⟦真正⟦的⟧理由⟧" to "真正的理由",
                "真正的理由⟧" to "真正的理由","真正的理由" to "真正的理由")) {
            val result=EmphasisText.decode(EmphasisText.fromResponse(source,raw))
            assertEquals(plain,result.text);assertTrue(result.ranges.isEmpty())
        }
    }
    @Test fun `translated order and phrase length need not match Japanese`() {
        val source=marked("これが理由だ。","理由")
        val result=EmphasisText.decode(EmphasisText.fromResponse(source,"The ⟦real reason⟧ is this."))
        assertEquals("real reason",result.text.substring(result.ranges.single().start,result.ranges.single().endExclusive))
    }
    @Test fun `conversion uses the complete phrase rather than separate marked fragments`() {
        val source=marked("理发店很好。","发")
        val result=EmphasisText.decode(EmphasisText.transform(source) { text ->
            assertEquals("理发店很好。",text);text.replace("理发店","理髮店")
        })
        assertEquals("理髮店很好。",result.text)
        assertEquals(listOf(EmphasisRange(1,2)),result.ranges)
    }
    @Test fun `punctuation and inserted wrapping preserve character ownership`() {
        val source=marked("This is real reason!","real reason")
        val result=EmphasisText.decode(EmphasisText.transform(source) { it.replace("real reason","real\nreason") })
        assertEquals("real\nreason",result.text.substring(result.ranges.single().start,result.ranges.single().endExclusive))
    }
    @Test fun `ranges split across displayed rows without dangling control symbols`() {
        val source=marked("real\nreason","real\nreason")
        val lines=EmphasisText.lines(source).map(EmphasisText::decode)
        assertEquals(listOf("real","reason"),lines.map { it.text })
        assertEquals(listOf(EmphasisRange(0,4)),lines[0].ranges)
        assertEquals(listOf(EmphasisRange(0,6)),lines[1].ranges)
    }
    @Test fun `surrogate and combining mark boundaries cannot be cut`() {
        assertFalse(EmphasisText.has(EmphasisText.encode("𠮷",listOf(EmphasisRange(0,1)))))
        assertFalse(EmphasisText.has(EmphasisText.encode("e\u0301",listOf(EmphasisRange(0,1)))))
        assertTrue(EmphasisText.has(EmphasisText.encode("e\u0301",listOf(EmphasisRange(0,2)))))
    }
    @Test fun `ambiguous changes drop decoration instead of moving it onto unrelated text`() {
        assertTrue(EmphasisText.remap("aaaa","aaa",listOf(EmphasisRange(1,2))).isEmpty())
    }
    @Test fun `mixed scene cannot add emphasis to an unmarked field or leak its controls`() {
        val parsed=EmphasisText.decode(EmphasisText.fromResponse("待って！","⟦Wait⟧!",annotationRequest=true))
        assertEquals("Wait!",parsed.text);assertTrue(parsed.ranges.isEmpty())
        val literal=EmphasisText.decode(EmphasisText.fromResponse("⟦本物の括弧⟧","⟦literal brackets⟧",annotationRequest=true))
        assertEquals("⟦literal brackets⟧",literal.text);assertTrue(literal.ranges.isEmpty())
    }
}
