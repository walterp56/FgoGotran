package com.fgogotran.overlay

import kotlin.test.Test
import kotlin.test.assertEquals

class DialogueRenderTextPolicyTest {
    @Test
    fun `removes translated hard breaks for one source line`() {
        assertEquals(
            "第一部分第二部分",
            DialogueRenderTextPolicy.prepare(
                sourceText = "一行の台詞です。",
                translatedText = "第一部分\n第二部分"
            )
        )
    }

    @Test
    fun `removes all break styles and surrounding horizontal space`() {
        assertEquals(
            "甲乙丙丁",
            DialogueRenderTextPolicy.prepare(
                sourceText = "一行",
                translatedText = "甲 \r\n\t乙\r　丙\n 丁"
            )
        )
    }

    @Test
    fun `preserves translated breaks for a multi-line source`() {
        assertEquals(
            "第一行\n第二行",
            DialogueRenderTextPolicy.prepare(
                sourceText = "一行目\n二行目",
                translatedText = "第一行\n第二行"
            )
        )
    }

    @Test
    fun `treats same-row OCR fragments as one visual source line`() {
        assertEquals(
            "！！『歡迎來到霧都』別說了快點！",
            DialogueRenderTextPolicy.prepare(
                sourceText = "！！\n『霧の都にようこそ』\nいいから早く！",
                translatedText = "！！\n『歡迎來到霧都』\n別說了快點！",
                sourceLineBounds = listOf(
                    DialogueRenderTextPolicy.LineBounds(top = 870, bottom = 934),
                    DialogueRenderTextPolicy.LineBounds(top = 871, bottom = 929),
                    DialogueRenderTextPolicy.LineBounds(top = 873, bottom = 930)
                )
            )
        )
    }

    @Test
    fun `preserves breaks for vertically separate source rows`() {
        assertEquals(
            "第一行\n第二行",
            DialogueRenderTextPolicy.prepare(
                sourceText = "一行目\n二行目",
                translatedText = "第一行\n第二行",
                sourceLineBounds = listOf(
                    DialogueRenderTextPolicy.LineBounds(top = 864, bottom = 945),
                    DialogueRenderTextPolicy.LineBounds(top = 903, bottom = 964)
                )
            )
        )
    }

    @Test
    fun `ignores blank source rows when detecting one logical line`() {
        assertEquals(
            "第一部分第二部分",
            DialogueRenderTextPolicy.prepare(
                sourceText = "\n 一行の台詞です。 \r\n",
                translatedText = "第一部分\n第二部分"
            )
        )
    }
}
