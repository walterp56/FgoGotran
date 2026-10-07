package com.fgogotran.translation

import kotlin.test.Test
import kotlin.test.assertEquals

class ReturnedRubyMarkupTest {
    @Test
    fun `translated duplicates are removed inside a sentence`() {
        val translated = "雖屬僭越〈僭越〉，但正是如此正是如此！主人〈主人〉的六分儀精度也罷，\n我的觀測精度也罷，都十分充足！"

        assertEquals(
            "雖屬僭越，但正是如此正是如此！主人的六分儀精度也罷，\n我的觀測精度也罷，都十分充足！",
            cleanReturnedRubyMarkup(translated)
        )
        assertEquals("ab", cleanReturnedRubyMarkup("ab〈ab〉"))
    }

    @Test
    fun `different readings and original story brackets are preserved`() {
        val translated = "大令咒〈Sirius Light〉。媽媽〈御主〉……現實〈現世〉、天〈空〉。マシュ《ナレーター》"

        assertEquals(translated, cleanReturnedRubyMarkup(translated))
        assertEquals("ab<ab> ab《ab》 ab(ab)", cleanReturnedRubyMarkup("ab<ab> ab《ab》 ab(ab)"))
    }

    @Test
    fun `only a complete immediate case sensitive repeat is removed`() {
        listOf("主人。別人〈主人〉", "主人，〈主人〉", "主人\n〈主人〉", "主人 〈主人〉", "Master〈master〉", "主〈主人〉")
            .forEach { assertEquals(it, cleanReturnedRubyMarkup(it)) }
    }

    @Test
    fun `duplicate readings support spaces and long text`() {
        assertEquals("Invoke Sirius Light!", cleanReturnedRubyMarkup("Invoke Sirius Light〈Sirius Light〉!"))
        val longText = "A".repeat(40)
        assertEquals(longText, cleanReturnedRubyMarkup("$longText〈$longText〉"))
        assertEquals("主人", cleanReturnedRubyMarkup("主人〈 主人 〉"))
    }

    @Test
    fun `cleanup retains existing blank and incomplete markup behaviour`() {
        assertEquals("本文", cleanReturnedRubyMarkup("本文〈 〉"))
        listOf("本文〈〉", "〈 〉", "〈主人〉", "本文〈主人", "……───", "")
            .forEach { assertEquals(it, cleanReturnedRubyMarkup(it)) }
    }
}
