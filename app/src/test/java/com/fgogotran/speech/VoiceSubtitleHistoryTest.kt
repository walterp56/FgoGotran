package com.fgogotran.speech

import kotlin.test.Test
import kotlin.test.assertEquals

class VoiceSubtitleHistoryTest {
    @Test
    fun `partial updates replace each other instead of accumulating`() {
        val history = VoiceSubtitleHistory()
        history.update("讓我們", isFinal = false)
        history.update("讓我們一起", isFinal = false)
        history.update("讓我們一起出發。", isFinal = false)
        assertEquals("讓我們一起出發。", history.text())
    }

    @Test
    fun `final replaces the current partial without saving it twice`() {
        val history = VoiceSubtitleHistory()
        history.update("大師，請", isFinal = false)
        history.update("御主，請下達命令。", isFinal = true)
        assertEquals("御主，請下達命令。", history.text())
        history.update("我會", isFinal = false)
        assertEquals("御主，請下達命令。\n我會", history.text())
    }

    @Test
    fun `two completed segments remain above the current live segment`() {
        val history = VoiceSubtitleHistory()
        history.update("御主，請下達命令。", isFinal = true)
        history.update("我會保護你。", isFinal = true)
        history.update("讓我們", isFinal = false)
        history.update("讓我們一起……", isFinal = false)
        assertEquals("御主，請下達命令。\n我會保護你。\n讓我們一起……", history.text())
        history.update("讓我們一起出發。", isFinal = true)
        assertEquals("御主，請下達命令。\n我會保護你。\n讓我們一起出發。", history.text())
    }

    @Test
    fun `oldest completed segment is discarded when another segment begins`() {
        val history = VoiceSubtitleHistory()
        history.update("第一段。", isFinal = true)
        history.update("第二段。", isFinal = true)
        history.update("第三段。", isFinal = true)
        history.update("第四段", isFinal = false)
        assertEquals("第二段。\n第三段。\n第四段", history.text())
        history.update("第四段。", isFinal = true)
        history.update("第五段。", isFinal = true)
        assertEquals("第三段。\n第四段。\n第五段。", history.text())
    }

    @Test
    fun `final only events also retain the previous two segments`() {
        val history = VoiceSubtitleHistory()
        for (index in 1..100) history.update("第${index}段。", isFinal = true)
        assertEquals("第98段。\n第99段。\n第100段。", history.text())
    }

    @Test
    fun `legitimate repeated speech is not deduplicated by text`() {
        val history = VoiceSubtitleHistory()
        history.update("御主！", isFinal = true)
        history.update("御主！", isFinal = false)
        history.update("御主！", isFinal = true)
        assertEquals("御主！\n御主！", history.text())
        history.update("御主！", isFinal = true)
        assertEquals("御主！\n御主！\n御主！", history.text())
    }

    @Test
    fun `blank results do not change the current segment or its final status`() {
        val history = VoiceSubtitleHistory()
        history.update("御主。", isFinal = true)
        history.update("  ", isFinal = false)
        assertEquals("御主。", history.text())
        history.update("下一段", isFinal = false)
        history.update("\n\t", isFinal = true)
        history.update("下一段完成。", isFinal = true)
        assertEquals("御主。\n下一段完成。", history.text())
    }

    @Test
    fun `clear discards both completed and partial text`() {
        val history = VoiceSubtitleHistory()
        history.update("舊的御主。", isFinal = true)
        history.update("舊的未完成內容", isFinal = false)
        history.clear()
        assertEquals("", history.text())
        history.update("新的內容", isFinal = false)
        assertEquals("新的內容", history.text())
    }

    @Test
    fun `clear resets final state before final only speech resumes`() {
        val history = VoiceSubtitleHistory()
        history.update("舊段落。", isFinal = true)
        history.clear()
        history.clear()
        history.update("新段落。", isFinal = true)
        assertEquals("新段落。", history.text())
        history.update("下一段。", isFinal = true)
        assertEquals("新段落。\n下一段。", history.text())
    }

    @Test
    fun `English punctuation and internal line breaks are preserved`() {
        val history = VoiceSubtitleHistory()
        history.update("  Master…\nAre you ready?  ", isFinal = true)
        history.update("Let's go—", isFinal = false)
        assertEquals("Master…\nAre you ready?\nLet's go—", history.text())
    }

    @Test
    fun `display history does not apply another terminology replacement`() {
        val history = VoiceSubtitleHistory()
        history.update("大師，主人公在這裡。", isFinal = true)
        history.update("御主，出發吧！", isFinal = true)
        assertEquals("大師，主人公在這裡。\n御主，出發吧！", history.text())
    }
}
