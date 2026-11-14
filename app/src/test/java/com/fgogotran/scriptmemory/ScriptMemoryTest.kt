package com.fgogotran.scriptmemory

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScriptMemoryTest {
    @Test
    fun `ruby and punctuation are removed from OCR text`() {
        val normalized = ScriptMemory.normalizeOcr("星見台〈せいけん〉へ、ようこそ！")
        assertEquals("星見台へようこそ", normalized)
    }

    @Test
    fun `fullwidth and halfwidth forms normalize together`() {
        assertEquals(
            ScriptMemory.normalizeOcr("ＡＢＣ１２３"),
            ScriptMemory.normalizeOcr("ABC123")
        )
    }

    @Test
    fun `trigrams cover the whole string`() {
        assertEquals(setOf("abc", "bcd", "cde"), ScriptMemory.trigrams("abcde"))
        assertEquals(setOf("ab"), ScriptMemory.trigrams("ab"))
        assertTrue(ScriptMemory.trigrams("").isEmpty())
    }

    @Test
    fun `java hash matches the JVM string hash`() {
        assertEquals("abc".hashCode(), ScriptMemory.javaHash("abc"))
        assertEquals("あいう".hashCode(), ScriptMemory.javaHash("あいう"))
    }

    @Test
    fun `sha1 is the lowercase hex digest`() {
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", ScriptMemory.sha1("abc"))
        assertFalse(ScriptMemory.sha1("abc").contains("-"))
    }
}
