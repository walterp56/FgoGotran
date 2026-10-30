package com.fgogotran.translation

import com.fgogotran.text.*
import kotlin.test.*

class EmphasisPromptTest {
    private val builder=PromptBuilder()
    private val source="これが本当の理由だ。"
    private val marked=EmphasisText.encode(source,listOf(EmphasisRange(3,8)))

    @Test fun `general single and choice batches activate only one conditional rule`() {
        assertFalse(buildGeneralSingleUserPrompt(builder,source,emptyList()).contains("visual emphasis"))
        for (prompt in listOf(buildGeneralSingleUserPrompt(builder,marked,emptyList()),
                buildGeneralSingleUserPrompt(builder,marked,emptyList(),translateAsChoices=true),
                buildGeneralBatchUserPrompt(listOf(marked,"はい"),translateAsChoices=true))) {
            assertEquals(1,Regex("marks visual emphasis only").findAll(prompt).count())
            assertTrue(EmphasisText.wire(prompt).contains("⟦本当の理由⟧"))
        }
    }
    @Test fun `Sakura keeps official wording glossary and line extraction`() {
        val context=builder.buildPromptContext(PromptOutputFormat.PLAIN_TEXT,source)
        val plain=SakuraPromptBuilder.buildSingleRequest(source,emptyList(),"",context)
        val candidate=SakuraPromptBuilder.buildSingleRequest(marked,emptyList(),"",context)
        assertEquals("将下面的日文文本翻译成中文：$source",plain.userPrompt)
        assertEquals(plain.currentSourceLineCount,candidate.currentSourceLineCount)
        assertTrue(EmphasisText.wire(candidate.userPrompt).endsWith("これが⟦本当の理由⟧だ。"))
        assertFalse(candidate.userPrompt.contains("visual emphasis"))
        assertEquals("译文",candidate.extractCurrentTranslation("译文"))
    }
    @Test fun `source feature selection is identical after internal annotation encoding`() {
        val plain=builder.buildPromptContext(PromptOutputFormat.PLAIN_TEXT,source)
        val candidate=builder.buildPromptContext(PromptOutputFormat.PLAIN_TEXT,marked)
        assertEquals(plain,candidate)
    }
    @Test fun `ordinary and annotated cache payloads stay isolated without a DB migration`() {
        assertEquals("普通譯文",EmphasisCachePayload.encode(source,"普通譯文"))
        val translated=EmphasisText.fromResponse(marked,"這是⟦真正的理由⟧。")
        val stored=EmphasisCachePayload.encode(marked,translated)
        assertFalse(stored.any { it in '\uE100'..'\uE102' })
        assertEquals(translated,EmphasisCachePayload.decode(marked,stored))
        assertNull(EmphasisCachePayload.decode(marked,"普通舊快取"))
    }
    @Test fun `ruby setting filters readings but preserves emphasis on other words`() {
        val raw="異聞帶〈ロストベルト〉、現実だ。"
        val input=SceneTranslateInput(null,raw,emptyList(),dialogueEmphasisRanges=listOf(
            EmphasisRange(raw.indexOf("現"),raw.indexOf("現")+2)))
        val prepared=TranslationRubyPolicy.prepare(input,false)
        assertEquals("異聞帶、現実だ。",prepared.dialogue)
        val range=prepared.dialogueEmphasisRanges.single()
        assertEquals("現実",prepared.dialogue!!.substring(range.start,range.endExclusive))
    }
    @Test fun `punctuation policy sees full body and returns original decoration`() {
        val value=EmphasisText.fromResponse(marked,"⟦真正的理由⟧!")
        val result=EmphasisText.decode(FgoDialogueSymbols.normalizeTranslatedPunctuation(value))
        assertEquals("真正的理由！",result.text)
        assertEquals(listOf(EmphasisRange(0,5)),result.ranges)
    }
    @Test fun `partial emphasis inside a terminology match cannot split RAG lookup`() {
        val term=com.fgogotran.terminology.TermEntity(jpTerm="カルデア",cnTerm="迦勒底",enTerm="Chaldea",category="term")
        val source=EmphasisText.encode("カルデアに帰ろう。",listOf(EmphasisRange(1,3)))
        assertEquals(listOf(term),builder.extractTermMatches(source,listOf(term),"en"))
    }
    @Test fun `history retains choice and dialogue ranges but provides plain previous context`() {
        SessionTranslationHistory.clear()
        try {
            val range=listOf(EmphasisRange(0,2))
            SessionTranslationHistory.add(SessionTranslationEntry(dialogueText="走吧。",dialogueEmphasisRanges=range,
                choices=listOf("走吧！"),choiceEmphasisRanges=listOf(range),sourceKey="emphasis-scene",
                contextSourceDialogue="行こう。",contextTranslatedDialogue="走吧。",dialogueSourceKey="emphasis-scene"))
            val stored=SessionTranslationHistory.entries.value.single()
            assertEquals(range,stored.dialogueEmphasisRanges)
            assertEquals(listOf(range),stored.choiceEmphasisRanges)
            assertEquals("走吧。",SessionTranslationHistory.lastSceneDialogueContexts().single().translatedDialogue)
        } finally { SessionTranslationHistory.clear() }
    }
}
