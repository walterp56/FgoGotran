package com.fgogotran.overlay

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RubySpanSpacingTest {
    private fun advance(value: String): Float = value.codePoints().toArray().sumOf { if (it == 32) 2.5 else 10.0 }.toFloat()

    private fun pad(value: String, left: Float = -20f): String = RubySpanSpacing.padLine(
        value, left, 1f, 2.5f, { body, _, end -> advance(body.take(end)) }, { it.length * 2f })

    @Test fun `adjacent long readings get only the missing horizontal space and retain all text`() {
        val source = "甲〈abcdefghijkl〉乙〈mnopqrstuvwx〉。"
        val result = pad(source)
        val parsed = RubyRenderText.parse(result)
        assertEquals("甲乙。", parsed.body.filterNot(Char::isWhitespace))
        assertEquals(listOf("abcdefghijkl", "mnopqrstuvwx"), parsed.annotations.map { it.reading })
        var previous = -20f
        for (annotation in parsed.annotations) {
            val endpoint = advance(parsed.body.take(annotation.end))
            assertTrue(endpoint - annotation.reading.length * 2f >= previous)
            previous = endpoint + 1f
        }
    }

    @Test fun `existing margin is used first and ordinary short readings stay unchanged`() {
        assertEquals("甲〈abc〉。", pad("甲〈abc〉。"))
        val result = pad("甲〈abcdefghijkl〉。", 0f)
        assertTrue(RubyRenderText.body(result).startsWith(' '))
        assertEquals("甲。", RubyRenderText.body(result).trim())
    }

    @Test fun `padding preserves supplementary characters and annotation order`() {
        val source = "𠮷〈abcdefghijkl〉私〈mnopqrstuvwx〉"
        val parsed = RubyRenderText.parse(pad(source))
        assertEquals("𠮷私", parsed.body.filterNot(Char::isWhitespace))
        assertEquals(2, parsed.annotations.size)
        parsed.annotations.forEach { assertFalse(Character.isLowSurrogate(parsed.body[it.start])) }
    }

    @Test fun `plain text and malformed markers retain their exact contents`() {
        for (source in listOf("普通の台詞。", "本文〈讀音", "本文〈〉", "〈孤立〉")) assertEquals(source, pad(source))
    }
}
