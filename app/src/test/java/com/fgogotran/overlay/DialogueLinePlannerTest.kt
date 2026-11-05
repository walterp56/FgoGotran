package com.fgogotran.overlay

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class DialogueLinePlannerTest {
    @Test
    fun preservesOneMeaningfulBreak() {
        val candidates = plan(
            "所以，就算我閉上眼睛、邊走邊挖鼻孔，",
            "也完全沒問題。"
        )

        assertEquals(
            "所以，就算我閉上眼睛、邊走邊挖鼻孔，\n也完全沒問題。",
            candidates.single()
        )
    }

    @Test
    fun preservesTheOnlyBreakEvenWhenFirstLineIsPunctuation() {
        val candidates = plan("……", "後一句。")

        assertEquals("……\n後一句。", candidates.single())
    }

    @Test
    fun collapsesThreeSourceLinesIntoTwoWithoutAddingWideSpaces() {
        val sourceLines = listOf(
            "只要進行干涉並完成連結，",
            "無論從何種巨怪手中，",
            "都能回收魂魄呢。"
        )

        val result = DialogueLinePlanner.plan(sourceLines, 24f, ::visualWidth).first()
        val renderedLines = result.lines()

        assertEquals(2, renderedLines.size)
        assertEquals(sourceLines.joinToString(""), renderedLines.joinToString(""))
        assertFalse('\u3000' in result)
        assertEquals(sourceLines.first(), renderedLines.first())
        assertEquals(sourceLines.drop(1).joinToString(""), renderedLines.last())
    }

    @Test
    fun attachesStandaloneConnectorToFollowingLine() {
        val candidates = plan(
            "排除侵入森林的對象，並製作新的魂魄回收者。",
            "還有",
            "唯一不服從我意、處於管理之外的巨大魔物。"
        )

        assertEquals(
            "排除侵入森林的對象，並製作新的魂魄回收者。\n" +
                "還有唯一不服從我意、處於管理之外的巨大魔物。",
            candidates.single()
        )
    }

    @Test
    fun attachesStandalonePunctuationToFollowingText() {
        val candidates = plan("前一句。", "……", "後一句。")

        assertEquals("前一句。\n……後一句。", candidates.single())
    }

    @Test
    fun stillProducesTwoLinesWhenEveryEarlyFragmentIsAConnector() {
        val result = plan("但是", "……", "後一句。").single()

        assertEquals(2, result.lines().size)
        assertEquals("但是……後一句。", result.replace("\n", ""))
    }

    @Test
    fun ignoresEmptySourceLinesWhenPlanning() {
        val candidates = plan("第一句。", "", "  ", "第二句。")

        assertEquals("第一句。\n第二句。", candidates.single())
    }

    @Test
    fun keepsAllContentWhenFourLinesAreCollapsed() {
        val sourceLines = listOf("甲。", "乙，", "丙，", "丁。")

        val result = DialogueLinePlanner.plan(sourceLines, 4f, ::visualWidth).first()

        assertEquals(2, result.lines().size)
        assertEquals(sourceLines.joinToString(""), result.replace("\n", ""))
    }

    @Test
    fun insertsNormalSpaceOnlyBetweenAsciiFragments() {
        val result = DialogueLinePlanner.plan(
            listOf("Use local", "AI model", "for FGO"),
            10f,
            ::visualWidth
        ).first()

        assertEquals("Use local AI model for FGO", result.replace("\n", " "))
        assertFalse('\u3000' in result)
    }

    private fun plan(vararg lines: String): List<String> {
        return DialogueLinePlanner.plan(lines.toList(), 80f, ::visualWidth)
    }

    private fun visualWidth(text: String): Float {
        return text.sumOf { char -> if (char.code <= 0x7f) 0.55 else 1.0 }.toFloat()
    }
}
