package com.fgogotran.update

import kotlin.test.Test
import kotlin.test.assertEquals

class AppChangelogSelectionTest {
    private val legacy = listOf("中文一", "中文二")
    private val localized = mapOf(
        "zh-Hant" to listOf("繁中一", "繁中二"),
        "en" to listOf("English one", "English two"),
        "zh-Hans" to legacy
    )

    @Test
    fun `traditional ui picks the traditional changelog`() {
        assertEquals(
            listOf("繁中一", "繁中二"),
            selectAppChangelog("zh-Hant", legacy, localized)
        )
    }

    @Test
    fun `english ui picks the english changelog`() {
        assertEquals(
            listOf("English one", "English two"),
            selectAppChangelog("en", legacy, localized)
        )
    }

    @Test
    fun `unknown language falls back to simplified chinese then to legacy`() {
        assertEquals(
            legacy,
            selectAppChangelog("fr-FR", legacy, localized)
        )
        assertEquals(
            legacy,
            selectAppChangelog("en", legacy, emptyMap())
        )
    }

    @Test
    fun `blank entries are removed and an empty translation is ignored`() {
        assertEquals(
            legacy,
            selectAppChangelog("en", legacy, mapOf("en" to listOf("  ", "")))
        )
        assertEquals(
            listOf("Only one"),
            selectAppChangelog("en", emptyList(), mapOf("en" to listOf("  Only one  ")))
        )
    }

    @Test
    fun `empty manifest keeps an empty list`() {
        assertEquals(emptyList(), selectAppChangelog("zh-Hant", emptyList(), emptyMap()))
    }
}
