package com.fgogotran.overlay

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RubyRenderTextTest {
    @Test fun `plain dialogue and original story brackets are unchanged`() {
        listOf("これは普通の台詞です。", "マシュ《ナレーター》", "……───", "").forEach {
            assertEquals(it, RubyRenderText.body(it))
            assertFalse(RubyRenderText.hasRuby(it))
        }
    }

    @Test fun `ruby leaves no inline width or blank space`() {
        val source = "我們回到觀星臺〈迦勒底〉吧。"
        val parsed = RubyRenderText.parse(source)
        assertEquals("我們回到觀星臺吧。", parsed.body)
        assertEquals("觀星臺", parsed.body.substring(parsed.annotations.single().start, parsed.annotations.single().end))
        assertEquals(source, parsed.annotate(parsed.body))
    }

    @Test fun `screenshot readings stay on their preceding Japanese bases`() {
        val first = RubyRenderText.parse("これはぴったり私〈わたくし〉と重なって、")
        val second = RubyRenderText.parse("私の原型〈アロイハート・オリジナル〉とすら呼べそうで、")
        assertEquals("私", first.body.substring(first.annotations.single().start, first.annotations.single().end))
        assertEquals("私の原型", second.body.substring(second.annotations.single().start, second.annotations.single().end))
    }

    @Test fun `wrapping restores readings in source order including repeated words`() {
        val parsed = RubyRenderText.parse("大令咒〈Sirius Light〉。大令咒〈別名〉！")
        assertEquals(listOf("大令咒〈Sirius Light〉。", "大令咒〈別名〉！"),
            parsed.annotateLines(listOf("大令咒。", "大令咒！")))
    }

    @Test fun `spacing and quote changes preserve the reading itself`() {
        val parsed = RubyRenderText.parse("『大令咒〈Sirius Light〉』")
        assertEquals("「大 令 咒〈Sirius Light〉」", parsed.annotate("「大 令 咒」"))
        assertEquals("大令咒〈Sirius Light〉", RubyRenderText.transform("大 令 咒〈Sirius Light〉") {
            it.replace(" ", "")
        })
    }

    @Test fun `ellipsis never attaches a reading to a truncated or unrelated base`() {
        val parsed = RubyRenderText.parse("回到觀星臺〈迦勒底〉吧。")
        assertEquals("回到觀……", parsed.annotate("回到觀……"))
        assertEquals("回到觀星臺〈迦勒底〉……", parsed.annotate("回到觀星臺……"))
        assertEquals("另一個句子", parsed.annotate("另一個句子"))
    }

    @Test fun `malformed orphan and empty markers retain their visible content`() {
        listOf("〈讀音〉", "本文〈〉", "本文〈 〉", "本文〈讀音", "本文〈外〈內〉〉", "本文 〈讀音〉").forEach {
            assertEquals(it, RubyRenderText.body(it))
        }
    }

    @Test fun `display parsing does not confuse original double angle brackets or punctuation`() {
        val parsed = RubyRenderText.parse("《巨英雄》こと〈メガロス〉。マシュ《ナレーター》")
        assertEquals("《巨英雄》こと。マシュ《ナレーター》", parsed.body)
        assertEquals(1, parsed.annotations.size)
        assertEquals("メガロス", parsed.annotations.single().reading)
    }

    @Test fun `supplementary letters and combining marks keep valid UTF16 boundaries`() {
        val parsed = RubyRenderText.parse("𠮷〈よし〉とCafé〈カフェ〉")
        assertEquals("𠮷とCafé", parsed.body)
        parsed.annotations.forEach {
            assertFalse(Character.isLowSurrogate(parsed.body[it.start]))
            assertFalse(it.end < parsed.body.length && Character.isLowSurrogate(parsed.body[it.end]))
            assertTrue(it.end > it.start)
        }
        assertEquals("𠮷〈よし〉とCafé〈カフェ〉", parsed.annotate(parsed.body))
    }
}
