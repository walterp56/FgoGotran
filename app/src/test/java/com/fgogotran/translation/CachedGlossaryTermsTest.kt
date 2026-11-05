package com.fgogotran.translation

import com.fgogotran.terminology.TermEntity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class CachedGlossaryTermsTest {
    @Test
    fun `stored names win over earlier generated parts including intentional blank gender`() {
        val von = term("ヴァン", "冯", "von")
        val gogh = term("ゴッホ", "梵高", "Van Gogh", gender = "女性",
            aliases = "[\"ヴァン・ゴッホ\",\"ヴァンゴッホ\"]")
        val entries = listOf(
            term("ヴァン", "", "van", "character_part", "女性"),
            term("ゴッホ", "", "Gogh", "character_part", "男性"), von, gogh
        )
        for (ordered in listOf(entries, entries.reversed())) {
            val loaded = mergeCachedGlossaryTerms(emptyList(), ordered)
            assertSame(von, loaded.single { it.jpTerm == "ヴァン" })
            assertSame(gogh, loaded.single { it.jpTerm == "ゴッホ" })
        }
    }

    @Test
    fun `existing term priority and unmatched compatibility parts are preserved`() {
        val lexical = term("魔術", "魔术", "Magecraft", "game_term")
        val stored = term("クリュティエ", "克吕提厄", "Clytie", gender = "女性")
        val fallback = term("未登録", "", "Fallback", "character_part")
        val loaded = mergeCachedGlossaryTerms(listOf(lexical), listOf(
            term("魔術", "", "Wrong", "character_part"), fallback, stored,
            term("クリュティエ", "", "Clytie", "character_part")
        ))
        assertEquals(listOf(lexical, stored, fallback), loaded)
    }

    @Test
    fun `RAG resolves gogh aliases clyties true name and Vincent as separate targets`() {
        val gogh = term("ゴッホ", "梵高", "Van Gogh", gender = "女性",
            aliases = "[\"ヴァン・ゴッホ\",\"ヴァンゴッホ\"]")
        val clytie = term("クリュティエ", "克吕提厄", "Clytie", gender = "女性")
        val full = term("クリュティエ＝ヴァン・ゴッホ", "克吕提厄·梵高", "Clytie van Gogh", gender = "女性")
        val painter = term("ヴィンセント・ヴァン・ゴッホ", "文森特·梵高", "Vincent van Gogh", gender = "男性")
        val loaded = mergeCachedGlossaryTerms(emptyList(), listOf(
            term("ゴッホ", "", "Gogh", "character_part"), gogh, clytie, full, painter
        ))
        val builder = PromptBuilder()
        for ((source, expected) in listOf(
            "ゴッホです。" to gogh, "ヴァン・ゴッホです。" to gogh,
            "ヴァンゴッホです。" to gogh, "クリュティエです。" to clytie,
            "クリュティエ＝ヴァン・ゴッホです。" to full,
            "ヴィンセント・ヴァン・ゴッホの絵。" to painter
        )) assertSame(expected, builder.extractTermMatches(source, loaded, "en").first(), source)
    }

    private fun term(jp: String, cn: String, en: String, category: String = "character",
        gender: String = "", aliases: String = "[]") =
        TermEntity(jpTerm = jp, cnTerm = cn, enTerm = en, category = category, gender = gender, aliases = aliases)
}
