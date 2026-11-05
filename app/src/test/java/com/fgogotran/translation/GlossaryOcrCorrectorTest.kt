package com.fgogotran.translation

import com.fgogotran.terminology.CharacterNameEntity
import com.fgogotran.terminology.TermEntity
import com.fgogotran.text.EmphasisRange
import com.fgogotran.text.EmphasisText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class GlossaryOcrCorrectorTest {
    private val examples = listOf("二キチッチ" to "ニキチッチ", "口ビンフッド" to "ロビンフッド",
        "工ミヤ" to "エミヤ", "力ーマ" to "カーマ", "夕マモキャット" to "タマモキャット",
        "アス卜ルフォ" to "アストルフォ", "へクトール" to "ヘクトール")
    private val rows = examples.map { (_, name) -> CharacterNameEntity(jpName = name, cnName = name,
        enName = name, gender = "女性") }
    private fun corrector(characters: List<CharacterNameEntity> = rows) = GlossaryOcrCorrector(characters,
        characters.map { TermEntity(jpTerm = it.jpName, cnTerm = it.cnName, category = "character", aliases = it.aliases) })

    @Test fun `seven one glyph confusions correct complete visible names`() {
        val matcher = corrector()
        for ((bad, good) in examples) {
            assertEquals(good, matcher.correctName(bad).text)
            assertEquals(1, matcher.correctName(bad).edits.size)
            assertFalse(matcher.correctName(good).changed)
        }
    }

    @Test fun `embedded words keep boundaries and conservatively require four characters`() {
        val matcher = corrector()
        assertEquals("ニキチッチさん、ロビンフッド！", matcher.correctText("二キチッチさん、口ビンフッド！").text)
        for (source in listOf("工ミヤ", "力ーマ", "二キチッチャ", "二キチッチ二", "ア二キチッチ")) {
            assertEquals(source, matcher.correctText(source).text)
        }
    }

    @Test fun `whole labels do not drop state punctuation or fabricate parts`() {
        val matcher = corrector()
        for (source in listOf("二キチッチ（偽）", "二キチッチさん", "二キチッチ？", "二キチッチ1", "二キチッチ・何者")) {
            assertEquals(source, matcher.correctName(source).text)
        }
        val fullOnly = corrector(listOf(CharacterNameEntity(jpName = "ドブルイニャ・ニキチッチ", cnName = "全名")))
        assertFalse(fullOnly.correctName("二キチッチ").changed)
        assertEquals("ニキチッチ", matcher.correctName("二キチッチ").text)
    }

    @Test fun `explicit stored forms and local literals always beat correction`() {
        val custom = CharacterNameEntity(jpName = "二キチッチ", cnName = "自訂")
        val matcher = corrector(listOf(custom) + rows)
        assertFalse(matcher.correctName("二キチッチ").changed)
        assertFalse(matcher.correctText("二キチッチです。").changed)
        assertEquals("自訂", NameBoxLookupPolicy.findTranslation("二キチッチ", listOf(custom) + rows, false))
    }

    @Test fun `configured player name remains literal even inside a longer sentence`() {
        assertEquals("二キチッチ", corrector().correctName("二キチッチ", "二キチッチ").text)
        assertEquals("二キチッチさんとニキチッチ。",
            corrector().correctText("二キチッチさんとニキチッチ。", "二キチッチ").text)
    }

    @Test fun `ordinary kanji short labels and two mistakes remain unchanged`() {
        for (source in listOf("二人", "第二", "魔力", "工房", "入口", "二世", "二キチツチ", "工ミャ", "シツソン", "ー一")) {
            assertFalse(corrector().correctText(source).changed, source)
            assertFalse(corrector().correctName(source).changed, source)
        }
    }

    @Test fun `ambiguous aliases are refused and JSON alias commas stay literal`() {
        val a = CharacterNameEntity(jpName = "マシュ", cnName = "甲", aliases = "[\"ニキチッチ\",\"別名,その二\"]")
        val b = CharacterNameEntity(jpName = "ロビンフッド", cnName = "乙", aliases = "ニキチッチ")
        for (matcher in listOf(corrector(listOf(a, b)), corrector(listOf(b, a)))) {
            assertFalse(matcher.correctName("二キチッチ").changed)
            assertFalse(matcher.correctText("二キチッチさん").changed)
        }
        assertFalse(corrector(listOf(a)).correctName("その二").changed)
    }

    @Test fun `ruby reading emphasis ranges newline and raw source survive`() {
        val raw = "二キチッチ〈二キチッチ〉！\n口ビンフッド。"
        val marked = EmphasisText.encode(raw, listOf(EmphasisRange(0, 5)))
        val result = corrector().correctText(marked)
        assertEquals("ニキチッチ〈二キチッチ〉！\nロビンフッド。", EmphasisText.plain(result.text))
        assertEquals(EmphasisText.decode(marked).ranges, EmphasisText.decode(result.text).ranges)
        assertEquals("二キチッチ〈二キチッチ〉！\n口ビンフッド。", raw)
    }

    @Test fun `aliases preserve their surface instead of expanding to full canonical name`() {
        val row = CharacterNameEntity(jpName = "ドブルイニャ・ニキチッチ", cnName = "全名", aliases = "ニキチッチ")
        val result = corrector(listOf(row)).correctName(" 二キチッチ　")
        assertEquals(" ニキチッチ　", result.text)
        assertEquals(row.jpName, result.edits.single().canonical)
    }

    @Test fun `one character emphasis and long annotated inputs retain exact offsets`() {
        for (prefix in listOf("", "あ".repeat(1_100))) {
            val source = prefix + "二キチッチ。"
            val ranges = listOf(EmphasisRange(prefix.length, prefix.length + 1))
            val result = corrector().correctText(EmphasisText.encode(source, ranges))
            assertEquals(prefix + "ニキチッチ。", EmphasisText.plain(result.text))
            assertEquals(ranges, EmphasisText.decode(result.text).ranges)
        }
    }

    @Test fun `cache policy changes only corrected requests`() {
        val material = listOf("existing", "key", "")
        assertSame(material, extendGlossaryOcrCacheMaterial(material, glossaryOcrCachePolicy(corrector().correctName("ニキチッチ"))))
        val policy = glossaryOcrCachePolicy(null, corrector().correctName("二キチッチ"))
        assertEquals("ocr-glossary-v1", policy)
        assertEquals(material + "ocr=ocr-glossary-v1", extendGlossaryOcrCacheMaterial(material, policy))
    }

    @Test fun `snapshot is reused and invalidation reloads local overrides`() = runBlocking {
        var loads = 0
        var active = rows
        val cache = GlossaryOcrCorrectorCache({ loads++; active }, { emptyList() })
        val first = cache.snapshot()
        assertSame(first, cache.snapshot()); assertEquals(1, loads)
        active = listOf(CharacterNameEntity(jpName = "二キチッチ", cnName = "自訂")) + rows
        cache.clear()
        assertFalse(cache.snapshot().correctName("二キチッチ").changed)
        assertEquals(2, loads)
    }

    @Test fun `reload during construction cannot publish the old snapshot`() = runBlocking {
        val started = CompletableDeferred<Unit>(); val resume = CompletableDeferred<Unit>()
        var loads = 0
        val cache = GlossaryOcrCorrectorCache({
            if (++loads == 1) { started.complete(Unit); resume.await(); rows }
            else listOf(CharacterNameEntity(jpName = "二キチッチ", cnName = "自訂")) + rows
        }, { emptyList() })
        val pending = async { cache.snapshot() }
        started.await(); cache.clear(); resume.complete(Unit)
        assertFalse(pending.await().correctName("二キチッチ").changed)
        assertEquals(2, loads)
    }
}
