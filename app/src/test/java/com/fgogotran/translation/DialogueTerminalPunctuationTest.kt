package com.fgogotran.translation

import kotlin.test.Test
import kotlin.test.assertEquals

class DialogueTerminalPunctuationTest {
    @Test
    fun `multiline dialogue normalizes internal punctuation but preserves final comma`() {
        assertEquals(
            "难道说，\n不知道，",
            FgoDialogueSymbols.normalizeTranslatedPunctuation("难道说,\n不知道，")
        )
        assertEquals(
            "第一句\n第二句",
            FgoDialogueSymbols.normalizeTranslatedPunctuation("第一句\n第二句")
        )
    }

    @Test
    fun `single comma style is preserved`() {
        assertEquals("不知道、", FgoDialogueSymbols.normalizeTranslatedPunctuation("不知道、"))
        assertEquals("不知道，", FgoDialogueSymbols.normalizeTranslatedPunctuation("不知道，"))
        assertEquals("不知道,", FgoDialogueSymbols.normalizeTranslatedPunctuation("不知道,"))
    }

    @Test
    fun `duplicate trailing comma equivalents keep their first style`() {
        assertEquals("不知道，", FgoDialogueSymbols.normalizeTranslatedPunctuation("不知道，、"))
        assertEquals("不知道、", FgoDialogueSymbols.normalizeTranslatedPunctuation("不知道、、"))
        assertEquals("不知道,", FgoDialogueSymbols.normalizeTranslatedPunctuation("不知道,,"))
        assertEquals("不知道、", FgoDialogueSymbols.normalizeTranslatedPunctuation("不知道、，,"))
    }

    @Test
    fun `cjk ascii punctuation uses fgo fullwidth style`() {
        assertEquals("你好，世界。", FgoDialogueSymbols.normalizeTranslatedPunctuation("你好,世界."))
        assertEquals("2026，之后", FgoDialogueSymbols.normalizeTranslatedPunctuation("2026,之后"))
        assertEquals("真的吗？", FgoDialogueSymbols.normalizeTranslatedPunctuation("真的吗?"))
        assertEquals("走吧！", FgoDialogueSymbols.normalizeTranslatedPunctuation("走吧!"))
        assertEquals("怎么会！？", FgoDialogueSymbols.normalizeTranslatedPunctuation("怎么会!?"))
        assertEquals("怎么会？！", FgoDialogueSymbols.normalizeTranslatedPunctuation("怎么会?!"))
        assertEquals("等等……！", FgoDialogueSymbols.normalizeTranslatedPunctuation("等等...!"))
        assertEquals("「战斗结束了。」", FgoDialogueSymbols.normalizeTranslatedPunctuation("「战斗结束了.」"))
    }

    @Test
    fun `ordinary and special endings are preserved without additions`() {
        listOf(
            "今天回去",
            "继续；",
            "说明:",
            "难道说……",
            "难道说.......",
            "等等───",
            "是这样吗〜",
            "「战斗结束了」",
            "『不会吧』",
            "（小声）",
            "【任务完成】"
        ).forEach { text ->
            assertEquals(text, FgoDialogueSymbols.normalizeTranslatedPunctuation(text))
        }
    }

    @Test
    fun `common ascii ellipses use fgo style without collapsing long pauses`() {
        assertEquals("等等……", FgoDialogueSymbols.normalizeTranslatedPunctuation("等等..."))
        assertEquals("……等等", FgoDialogueSymbols.normalizeTranslatedPunctuation("...等等"))
        assertEquals("等等…", FgoDialogueSymbols.normalizeTranslatedPunctuation("等等…"))
        assertEquals("等等…………", FgoDialogueSymbols.normalizeTranslatedPunctuation("等等......"))
        assertEquals("等等....", FgoDialogueSymbols.normalizeTranslatedPunctuation("等等...."))
        assertEquals("等等..........", FgoDialogueSymbols.normalizeTranslatedPunctuation("等等.........."))
    }

    @Test
    fun `latin text urls versions and decimals are protected`() {
        listOf(
            "One, two...",
            "Hello!",
            "https://example.com/a?x=1",
            "版本2.4.0",
            "数值3.14"
        ).forEach { text ->
            assertEquals(text, FgoDialogueSymbols.normalizeTranslatedPunctuation(text))
        }
        assertEquals("网站example.com", FgoDialogueSymbols.normalizeTranslatedPunctuation("网站example.com"))
    }

    @Test
    fun `masks separators and expressive symbols are preserved`() {
        listOf(
            "???",
            "？？？",
            "■■■■",
            "■■■■■・■・■■■■",
            "达·芬奇",
            "好耶～♪♡☆",
            "『不会吧』",
            "（小声）",
            "【任务完成】"
        ).forEach { text ->
            assertEquals(text, FgoDialogueSymbols.normalizeTranslatedPunctuation(text))
        }
    }

    @Test
    fun `repairs only confident terminal bracket noise without touching words`() {
        assertEquals(
            "你好（你好）？？",
            FgoDialogueSymbols.normalizeTranslatedPunctuation("你好(你好 )?)?")
        )
        assertEquals(
            "你好（小声）？？",
            FgoDialogueSymbols.normalizeTranslatedPunctuation("你好（小声）？)?,")
        )
        assertEquals(
            "「ok!」！",
            FgoDialogueSymbols.normalizeTranslatedPunctuation("「ok!」!」")
        )
        assertEquals("你好？？", FgoDialogueSymbols.normalizeTranslatedPunctuation("你好）？？"))
        assertEquals("你好（小声？", FgoDialogueSymbols.normalizeTranslatedPunctuation("你好（小声？"))
        assertEquals("前）文", FgoDialogueSymbols.normalizeTranslatedPunctuation("前）文"))
        assertEquals("玛修（玛修）！", FgoDialogueSymbols.normalizeTranslatedPunctuation("玛修（玛修）！"))
    }

    @Test
    fun `preserves every valid emotional cluster count and order`() {
        listOf(
            "真的？",
            "真的？？",
            "真的？？？",
            "真的！！！！",
            "真的！？",
            "真的？！",
            "真的！！？",
            "真的！！？？",
            "真的！？！",
            "真的？？？！！！",
            "真的！？！？？",
            "真的！？！？！？！？！？！？"
        ).forEach { text ->
            assertEquals(text, FgoDialogueSymbols.normalizeTranslatedPunctuation(text))
        }
        assertEquals("怎么回事？？", FgoDialogueSymbols.normalizeTranslatedPunctuation("怎么回事??"))
        assertEquals("不会吧！！？？", FgoDialogueSymbols.normalizeTranslatedPunctuation("不会吧!!??"))
        assertEquals("什么？！？！", FgoDialogueSymbols.normalizeTranslatedPunctuation("什么?!?!"))
        assertEquals("什么！？！？", FgoDialogueSymbols.normalizeTranslatedPunctuation("什么!?!?"))
        assertEquals("什么？？！！", FgoDialogueSymbols.normalizeTranslatedPunctuation("什么?？!！"))
        assertEquals("???", FgoDialogueSymbols.normalizeTranslatedPunctuation("???"))
        assertEquals("!!", FgoDialogueSymbols.normalizeTranslatedPunctuation("!!"))
        assertEquals("ok!?", FgoDialogueSymbols.normalizeTranslatedPunctuation("ok!?"))
    }

    @Test
    fun `preserves genuine unicode ellipsis lengths`() {
        listOf("等等…", "等等……", "等等………", "等等…………", "等等……………").forEach { text ->
            assertEquals(text, FgoDialogueSymbols.normalizeTranslatedPunctuation(text))
        }
        assertEquals("等等……", FgoDialogueSymbols.normalizeTranslatedPunctuation("等等．．．"))
    }

    @Test
    fun `cleans malformed terminal tails but leaves valid internal clusters`() {
        assertEquals("你好。", FgoDialogueSymbols.normalizeTranslatedPunctuation("你好,.,."))
        assertEquals("你好。", FgoDialogueSymbols.normalizeTranslatedPunctuation("你好，。"))
        assertEquals("你好？", FgoDialogueSymbols.normalizeTranslatedPunctuation("你好，？"))
        assertEquals("你好！", FgoDialogueSymbols.normalizeTranslatedPunctuation("你好。！"))
        assertEquals("你好？！", FgoDialogueSymbols.normalizeTranslatedPunctuation("你好?.,!"))
        assertEquals("你好！？", FgoDialogueSymbols.normalizeTranslatedPunctuation("你好!?，"))
        assertEquals("你好，", FgoDialogueSymbols.normalizeTranslatedPunctuation("你好，，"))
        assertEquals("你好、", FgoDialogueSymbols.normalizeTranslatedPunctuation("你好、、"))
        assertEquals(
            "うおお！！、……おおっ！！？？",
            FgoDialogueSymbols.normalizeTranslatedPunctuation("うおお！！、……おおっ！！？？")
        )
    }

    @Test
    fun `source terminal punctuation replaces target terminal atomically`() {
        val cases = listOf(
            Triple("フォウ？", "芙芙", "芙芙？"),
            Triple("フォウ！！？？", "芙芙", "芙芙！！？？"),
            Triple("本当か？？", "真的吗？", "真的吗？？"),
            Triple("なぜだ！！？？", "为什么？？", "为什么！！？？"),
            Triple("なぜだ？！", "为什么！？", "为什么？！"),
            Triple("そうです。", "是的！！", "是的。"),
            Triple("待て───", "等等。", "等等───"),
            Triple("そうか……。", "原来如此。", "原来如此……。"),
            Triple("そうか……………。", "沉默。", "沉默……………。"),
            Triple("（本当か！？）", "（真的吗？）", "（真的吗！？）"),
            Triple("「本当か？？」", "「真的吗？」", "「真的吗？？」"),
            Triple("本当か？？", "「真的吗？」", "「真的吗？？」"),
            Triple("キャアーーーーー！", "尖叫？", "尖叫！"),
            Triple("私はまだ戦える。", "我……还能战斗！！", "我……还能战斗。")
        )
        cases.forEach { (source, target, expected) ->
            assertEquals(expected, FgoDialogueSymbols.reconcileSourcePunctuation(source, target))
        }
    }

    @Test
    fun `balanced outer source wrappers are restored around the complete translation`() {
        assertEquals(
            "“在赤色灾厄追上之前。\n在黑色灾厄吞噬之前。”",
            FgoDialogueSymbols.reconcileSourcePunctuation(
                "“あかい災いが追いつく前に。\nくろい災いが食いつく前に”",
                "在赤色灾厄追上之前。\n在黑色灾厄吞噬之前。"
            )
        )
        assertEquals(
            "“真的吗？？”",
            FgoDialogueSymbols.reconcileSourcePunctuation("“本当か？？”", "真的吗？")
        )
        assertEquals(
            "「真的吗？？」",
            FgoDialogueSymbols.reconcileSourcePunctuation("「本当か？？」", "真的吗？")
        )
        assertEquals(
            "『「真的！？」』",
            FgoDialogueSymbols.reconcileSourcePunctuation("『「本当！？」』", "真的？")
        )
        assertEquals(
            "\"真的吗？\"",
            FgoDialogueSymbols.reconcileSourcePunctuation("\"本当か?\"", "真的吗！")
        )
        assertEquals(
            "『无论选择哪一个都可以。\n做出选择的人会让那个选择成为正确答案』",
            FgoDialogueSymbols.reconcileSourcePunctuation(
                "『どちらを選んでもいい。\n選んだ者が、その選択を正しくする』",
                "无论选择哪一个都可以。\n做出选择的人会让那个选择成为正确答案"
            )
        )
        assertEquals(
            "「真的？」！",
            FgoDialogueSymbols.reconcileSourcePunctuation("「本当？」！", "真的！")
        )
        assertEquals(
            "『「真的！？」』！",
            FgoDialogueSymbols.reconcileSourcePunctuation("『「本当！？」』！", "真的？")
        )
    }

    @Test
    fun `source quote style replaces another quote style without deleting semantic wrappers`() {
        assertEquals(
            "“真的。”",
            FgoDialogueSymbols.reconcileSourcePunctuation("“本当。”", "「真的！」")
        )
        assertEquals(
            "“真的。”",
            FgoDialogueSymbols.reconcileSourcePunctuation("“本当。”", "「真的？」！")
        )
        assertEquals(
            "“真的”！",
            FgoDialogueSymbols.reconcileSourcePunctuation("“本当”", "「真的」！")
        )
        assertEquals(
            "“（真的。）”",
            FgoDialogueSymbols.reconcileSourcePunctuation("“本当。”", "（真的！）")
        )
        assertEquals(
            "「真的！」",
            FgoDialogueSymbols.reconcileSourcePunctuation("本当！", "「真的？」")
        )
    }

    @Test
    fun `source absence or low confidence never removes a valid target tail`() {
        assertEquals(
            "也许吧！！",
            FgoDialogueSymbols.reconcileSourcePunctuation("たぶん", "也许吧！！")
        )
        assertEquals(
            "真的吗？？",
            FgoDialogueSymbols.reconcileSourcePunctuation("本当か,.,.", "真的吗？？")
        )
        assertEquals(
            "真的吗？？",
            FgoDialogueSymbols.reconcileSourcePunctuation("本当か)?)?", "真的吗？？")
        )
    }

    @Test
    fun `leading source pause is restored without disturbing internal punctuation`() {
        assertEquals(
            "……不会吧！？",
            FgoDialogueSymbols.reconcileSourcePunctuation("……まさか！？", "不会吧？")
        )
        assertEquals(
            "「……不会吧！？」",
            FgoDialogueSymbols.reconcileSourcePunctuation("「……まさか！？」", "「不会吧？」")
        )
    }

    @Test
    fun `trusted source signature tracks only leading and terminal punctuation`() {
        assertEquals("", FgoDialogueSymbols.sourcePunctuationStabilitySignature("本当か"))
        assertEquals("L:|T:？？", FgoDialogueSymbols.sourcePunctuationStabilitySignature("本当か??"))
        assertEquals(
            "L:……|T:！？",
            FgoDialogueSymbols.sourcePunctuationStabilitySignature("……まさか！？")
        )
        assertEquals("", FgoDialogueSymbols.sourcePunctuationStabilitySignature("本当か,.,."))
        assertEquals(
            "W:“”@in|L:|T:",
            FgoDialogueSymbols.sourcePunctuationStabilitySignature("“本当か”")
        )
        assertEquals(
            "W:「」@in|L:……|T:！？",
            FgoDialogueSymbols.sourcePunctuationStabilitySignature("「……本当か！？」")
        )
        assertEquals(
            "W:「」@out|L:|T:！",
            FgoDialogueSymbols.sourcePunctuationStabilitySignature("「本当か？」！")
        )
    }

    @Test
    fun `normalization is idempotent`() {
        val normalized = "难道说，\n真的！？……"
        assertEquals(
            normalized,
            FgoDialogueSymbols.normalizeTranslatedPunctuation(
                FgoDialogueSymbols.normalizeTranslatedPunctuation(normalized)
            )
        )

        val reconciled = FgoDialogueSymbols.reconcileSourcePunctuation(
            "（本当か！！？？）",
            "（真的吗？）"
        )
        assertEquals(
            reconciled,
            FgoDialogueSymbols.reconcileSourcePunctuation("（本当か！！？？）", reconciled)
        )

        val quoted = FgoDialogueSymbols.reconcileSourcePunctuation(
            "“本当か？？”",
            "真的吗？"
        )
        assertEquals(
            quoted,
            FgoDialogueSymbols.reconcileSourcePunctuation("“本当か？？”", quoted)
        )
    }
}
