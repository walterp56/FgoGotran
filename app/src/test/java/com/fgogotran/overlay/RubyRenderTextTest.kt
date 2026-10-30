package com.fgogotran.overlay

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RubyRenderTextTest {
    @Test fun `bilingual Japanese original keeps full inline text and no above-base annotations`() {
        val original = "これは私の原型〈アロイハート・オリジナル〉です。"
        val inline = RubyRenderText.parse(original, RubyRenderText.Mode.INLINE)
        assertEquals(original, inline.body)
        assertTrue(inline.annotations.isEmpty())
        assertTrue(inline.markers.isEmpty())
        assertEquals(original, RubyRenderText.body(original, RubyRenderText.Mode.INLINE))
    }

    @Test fun `same notation can be original inline and translated above base without cache leakage`() {
        val text = "異聞帶〈Lostbelt〉。"
        repeat(2) {
            assertEquals("異聞帶。", RubyRenderText.body(text))
            assertEquals(text, RubyRenderText.body(text, RubyRenderText.Mode.INLINE))
            val translation = RubyRenderText.parse(text)
            assertEquals("Lostbelt", translation.annotations.single().reading)
            assertEquals(text, translation.annotate(translation.body))
        }
    }

    @Test fun `inline readings contribute all glyphs to layout and survive wrapped history lines`() {
        val source = "私〈わたくし〉と\n私の原型〈アロイハート・オリジナル〉"
        val inline = RubyRenderText.parse(source, RubyRenderText.Mode.INLINE)
        val above = RubyRenderText.parse(source)
        assertTrue(inline.body.length > above.body.length)
        assertEquals(source.lines(), inline.annotateLines(source.lines()))
        assertEquals(2, above.annotations.size)
    }

    @Test fun `inline mode preserves plain malformed and story bracket text literally`() {
        listOf("これは普通の台詞です。", "マシュ《ナレーター》", "本文〈〉", "本文〈外〈內〉〉", "本文〈讀音", "").forEach {
            val inline = RubyRenderText.parse(it, RubyRenderText.Mode.INLINE)
            assertEquals(it, inline.body)
            assertTrue(inline.annotations.isEmpty())
        }
    }

    @Test fun `inline ellipsis keeps complete readings and drops partially visible readings`() {
        val source = "私〈わたくし〉と原型〈オリジナル〉。"
        val first = source.indexOf('〈')
        val end = source.indexOf('〉') + 1
        for (cut in first + 1 until end) {
            assertEquals("私", RubyRenderText.inlinePrefix(source, cut))
        }
        assertEquals("私〈わたくし〉", RubyRenderText.inlinePrefix(source, end))
        val second = source.lastIndexOf('〈')
        assertEquals(source.take(second), RubyRenderText.inlinePrefix(source, second + 3))
        assertEquals(source, RubyRenderText.inlinePrefix(source, source.length))
    }

    @Test fun `inline truncation keeps UTF16 boundaries and leaves malformed notation literal`() {
        assertEquals("", RubyRenderText.inlinePrefix("𠮷〈よし〉", 1))
        assertEquals("𠮷", RubyRenderText.inlinePrefix("𠮷〈よし〉", 2))
        assertEquals("本文〈讀", RubyRenderText.inlinePrefix("本文〈讀音", 4))
        assertEquals("", RubyRenderText.inlinePrefix("本文", -1))
        assertEquals("本文", RubyRenderText.inlinePrefix("本文", 100))
    }

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
        listOf("〈讀音〉", "本文〈〉", "本文〈 〉", "本文〈讀音", "本文〈外〈內〉〉").forEach {
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

    @Test fun `reported English dialogue keeps Olga Marie above her with normal word spacing`() {
        val source = "After I saw her 〈Olga Marie〉 vanish into the dimensional hole,\n" +
            "I wondered what would become of me when I lost consciousness."
        val parsed = RubyRenderText.parse(source)
        assertEquals(source.replace("her 〈Olga Marie〉", "her"), parsed.body)
        assertEquals("her", parsed.base())
        assertEquals("Olga Marie", parsed.annotations.single().reading)
        assertEquals(source.replace("her 〈", "her〈"), parsed.annotate(parsed.body))
        assertEquals(parsed.body, parsed.applyEdits(source))
    }

    @Test fun `horizontal Unicode separators before and after ruby cannot leave it inline`() {
        for (gap in listOf("", " ", "  ", "\t", "\u3000", "\u00a0", "\u202f", "\u2009", "\u200b", "\u2060", "\ufeff")) {
            val source = "After her${gap}〈Olga Marie〉 vanish."
            val parsed = RubyRenderText.parse(source)
            assertEquals("After her vanish.", parsed.body, gap.toCharArray().contentToString())
            assertEquals("her", parsed.base())
            assertEquals("Olga Marie", parsed.annotations.single().reading)
            assertEquals(parsed.body, parsed.applyEdits(source))
        }
    }

    @Test fun `a separator before ruby still separates following English words without a second space`() {
        val parsed = RubyRenderText.parse("her  〈Olga Marie〉vanishes")
        assertEquals("her vanishes", parsed.body)
        assertEquals("her", parsed.base())
        assertEquals("her〈Olga Marie〉 vanishes", parsed.annotate(parsed.body))
    }

    @Test fun `Chinese ruby separator whitespace closes naturally without changing its inferred base`() {
        for (gap in listOf("", " ", "  ", "\t", "\u3000", "\u00a0")) {
            val source = "我們回到觀星臺${gap}〈迦勒底〉${gap}吧。"
            val parsed = RubyRenderText.parse(source)
            assertEquals("我們回到觀星臺吧。", parsed.body)
            assertEquals("觀星臺", parsed.base())
            assertEquals(parsed.body, parsed.applyEdits(source))
        }
    }

    @Test fun `punctuation and newlines retain their place after removing ruby separators`() {
        for ((source, expected) in listOf("her 〈Olga Marie〉 ," to "her,",
            "她 〈奧爾加瑪麗〉 ，" to "她，", "Spell 〈Sirius Light〉 。" to "Spell。",
            "her 〈Olga Marie〉\nNext" to "her\nNext", "her 〈Olga Marie〉 “Next”" to "her “Next”")) {
            val parsed = RubyRenderText.parse(source)
            assertEquals(expected, parsed.body)
            assertEquals(parsed.body, parsed.applyEdits(source))
        }
    }

    @Test fun `repeated words and several spaced readings keep independent source edits and owners`() {
        val source = "her 〈Olga Marie〉 and her　〈Another Name〉."
        val parsed = RubyRenderText.parse(source)
        assertEquals("her and her.", parsed.body)
        assertEquals(listOf("her", "her"), parsed.annotations.map { parsed.body.substring(it.start, it.end) })
        assertEquals(listOf("Olga Marie", "Another Name"), parsed.annotations.map { it.reading })
        assertEquals(parsed.body, parsed.applyEdits(source))
        assertTrue(parsed.edits.zipWithNext().all { (a,b) -> a.end <= b.start })
    }

    @Test fun `English base boundaries preserve curly contractions compounds and accented graphemes`() {
        for (word in listOf("Master’s", "spirit-like", "Café", "Cafe\u0301", "Urðr", "𠮷")) {
            val parsed = RubyRenderText.parse("Before $word 〈reading words〉 after")
            assertEquals(word, parsed.base(), word)
            val annotation = parsed.annotations.single()
            assertFalse(Character.isLowSurrogate(parsed.body[annotation.start]))
            assertFalse(Character.isHighSurrogate(parsed.body[annotation.end - 1]))
        }
        assertEquals("Spell", RubyRenderText.parse("The Great Command Spell 〈Sirius Light〉.").base())
    }

    @Test fun `orphan malformed and cross line readings remain literal`() {
        for (source in listOf("　〈name〉", "her\n〈Olga Marie〉", "her\r\n 〈Olga Marie〉",
            "her\u2028〈Olga Marie〉", "her. 〈Olga Marie〉", "her 〈〉", "her 〈Olga\nMarie〉")) {
            val parsed = RubyRenderText.parse(source)
            assertEquals(source, parsed.body)
            assertTrue(parsed.annotations.isEmpty())
            assertTrue(parsed.edits.isEmpty())
        }
    }

    @Test fun `a second reading without a new base cannot reuse the previous owner or crash`() {
        val parsed = RubyRenderText.parse("her〈Olga Marie〉〈another〉")
        assertEquals("her〈another〉", parsed.body)
        assertEquals("her", parsed.base())
        assertEquals("Olga Marie", parsed.annotations.single().reading)
    }

    @Test fun `whitespace edits preserve later word dot offsets and ruby wins the overlapping base`() {
        val source = "her 〈Olga Marie〉 is the real reason."
        val start = source.indexOf("real")
        val packed = com.fgogotran.text.EmphasisText.encode(source,
            listOf(com.fgogotran.text.EmphasisRange(start, start + "real reason".length)))
        val parsed = RubyRenderText.parse(packed)
        assertEquals("her is the real reason.", parsed.body)
        assertEquals(listOf("real", "reason"), parsed.annotations.filter { it.emphasis }
            .map { parsed.body.substring(it.start, it.end) })
        assertEquals("her", parsed.base())
    }

    @Test fun `normalized reading survives wrapping and cannot attach to a truncated base`() {
        val parsed = RubyRenderText.parse("The Great Command Spell 〈Sirius Light〉 appears.")
        assertEquals(listOf("The Great Command", "Spell〈Sirius Light〉 appears."),
            parsed.annotateLines(listOf("The Great Command", "Spell appears.")))
        assertEquals("The Great Command Sp…", parsed.annotate("The Great Command Sp…"))
    }

    @Test fun `bilingual original mode never normalizes ruby spaces or introduces display edits`() {
        val source = "星見台 〈カルデア〉 と her　〈Olga Marie〉"
        val inline = RubyRenderText.parse(source, RubyRenderText.Mode.INLINE)
        assertEquals(source, inline.body)
        assertTrue(inline.annotations.isEmpty())
        assertTrue(inline.edits.isEmpty())
    }

    private fun RubyRenderText.Text.base() = annotations.single { !it.emphasis }.let { body.substring(it.start, it.end) }
    private fun RubyRenderText.Text.applyEdits(source: String) = StringBuilder(source).apply {
        edits.asReversed().forEach { replace(it.start, it.end, it.replacement) }
    }.toString()
}
