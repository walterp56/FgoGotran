package com.fgogotran.translation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CombinedCharacterNameTest {
    @Test
    fun `chinese targets keep the full width separator`() {
        assertEquals(
            "玛修＆达·芬奇",
            composeCombinedCharacterName(listOf("玛修", "达·芬奇"), "zh-Hans")
        )
        assertEquals(
            "瑪修＆達·文西",
            composeCombinedCharacterName(listOf("瑪修", "達·文西"), "zh-Hant")
        )
    }

    @Test
    fun `english target uses the ascii separator`() {
        assertEquals(
            "Mash & Da Vinci",
            composeCombinedCharacterName(listOf("Mash", "Da Vinci"), "en")
        )
    }

    @Test
    fun `parts are trimmed and blank parts are rejected`() {
        assertEquals(
            "Mash & Da Vinci",
            composeCombinedCharacterName(listOf("  Mash ", " Da Vinci "), "en")
        )
        assertNull(composeCombinedCharacterName(listOf("Mash", "   "), "en"))
        assertNull(composeCombinedCharacterName(emptyList(), "en"))
    }

    @Test
    fun `overlong combined names are rejected`() {
        val longPart = "A".repeat(40)
        assertNull(composeCombinedCharacterName(listOf(longPart, longPart), "en"))
    }
}
