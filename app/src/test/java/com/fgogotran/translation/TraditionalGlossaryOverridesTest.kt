package com.fgogotran.translation

import com.fgogotran.terminology.CharacterNameEntity
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TraditionalGlossaryOverridesTest {
    private val header = "jp_source\tcn_reference\tzh_hant"
    private val row = "シオン\t希翁\t紫苑"
    private fun parse(vararg rows: String) = TraditionalGlossaryOverrides.parse(sequenceOf(header, *rows))

    @Test fun minimalTsvSupportsBomCommentsAndLineEndings() {
        val index = TraditionalGlossaryOverrides.parse(sequenceOf("# reviewed", "\uFEFF$header\r", "$row\r", ""))
        assertEquals("紫苑", index.findTarget(listOf("シオン"), "希翁"))
        assertEquals("紫苑", index.findTarget(listOf("シオン"), "紫苑"))
        assertNull(TraditionalGlossaryOverrides.EMPTY.findTarget(listOf("シオン"), "希翁"))
    }

    @Test fun badColumnsBlankFieldsAndCollidingKeysAreRejected() {
        for (rows in listOf(listOf("jp_source\tzh_hant", "シオン\t紫苑"),
            listOf(header, "シオン\t\t紫苑"), listOf(header, "$row\textra"),
            listOf(header, row, row), listOf(header, "シオン\t希翁\t紫\u0000苑"))) {
            assertFailsWith<IllegalArgumentException> { TraditionalGlossaryOverrides.parse(rows.asSequence()) }
        }
        assertFailsWith<IllegalArgumentException> {
            TraditionalGlossaryOverrides.parse(sequenceOf(header, row, "シオン \t希翁\t紫苑"))
        }
    }

    @Test fun bothJapaneseIdentityAndExpectedReferenceMustMatch() {
        val index = parse(row)
        assertNull(index.findTarget(listOf("魔神グシオン"), "魔神古辛"))
        assertNull(index.findTarget(listOf("シオン"), "a changed translation"))
        assertNull(index.findTarget(listOf("シオン？"), "希翁"))
        assertEquals("紫苑", index.findTarget(listOf("explicit alias", "シオン"), "希翁"))
    }

    @Test fun onlyAlreadyConvertedSpeakerReferencesAcceptTheGlyphBaseline() {
        val index = parse("オベロン\t奥伯龙\t奧伯隆")
        assertNull(index.findTarget(listOf("オベロン"), "奧伯龍"))
        assertEquals("奧伯隆", index.findTarget(listOf("オベロン"), "奧伯龍") { "奧伯龍" })
        assertNull(index.findTarget(listOf("オベロン"), "別人") { "奧伯龍" })
    }

    @Test fun activationIsLimitedToGeneralTraditionalRequests() {
        for (target in listOf("zh-Hant", "zh-Hant-TW", "zh-Hant-HK")) {
            assertTrue(usesTraditionalGlossaryOverrides(target, true))
            assertFalse(usesTraditionalGlossaryOverrides(target, false))
        }
        for (target in listOf("zh-Hans", "zh-CN", "en", "en-US")) {
            assertFalse(usesTraditionalGlossaryOverrides(target, true))
        }
    }

    @Test fun nameBoxAliasesUseCanonicalIdentityWithoutChangingLegacyTargetsOrMatching() {
        val name = CharacterNameEntity(jpName = "シオン", cnName = "希翁", enName = "Sion", aliases = "シオンさん")
        val index = parse(row)
        fun find(text: String, english: Boolean = false, applyOverride: Boolean = false) = NameBoxLookupPolicy.findTranslation(
            text, listOf(name), english,
            selectTarget = if (!applyOverride) null else { matched, target ->
                index.findTarget(listOf(matched.jpName), target) ?: target
            })
        assertEquals("紫苑", find("シオンさん", applyOverride = true))
        assertEquals("希翁", find("シオンさん"))
        assertEquals("Sion", find("シオンさん", english = true))
        assertNull(find("シオン？", applyOverride = true))
        assertNull(find("魔神グシオン", applyOverride = true))
        assertEquals("希翁", name.cnName)
    }

    @Test fun previousSpeakerCanChangeWithoutRewritingOldDialogueOrJapanese() {
        val old = SceneDialogueContext(sourceSpeakerName = "シオン", translatedSpeakerName = "希翁",
            sourceDialogue = "シオンです。", translatedDialogue = "我是希翁。", targetLocale = "zh-Hans",
            dialogueSourceKey = "fixture")
        val index = parse(row)
        val prepared = prepareGeneralSceneDialogueContexts(listOf(old), "zh-Hant",
            { it }, { it }, { text, _ -> text },
            selectSpeakerTarget = { source, text, _ -> index.findTarget(listOf(source), text) ?: text }).single()
        assertEquals("紫苑", prepared.translatedSpeakerName)
        assertEquals(old.sourceSpeakerName, prepared.sourceSpeakerName)
        assertEquals(old.sourceDialogue, prepared.sourceDialogue)
        assertEquals(old.translatedDialogue, prepared.translatedDialogue)
        assertEquals("希翁", old.translatedSpeakerName)
    }

    @Test fun contentIdentityIsStableAndAffectsOnlyGeneralHantCacheMaterial() {
        val a = parse(row, "オベロン\t奥伯龙\t奧伯隆")
        val reordered = parse("オベロン\t奥伯龙\t奧伯隆", row)
        val b = parse("シオン\t希翁\t另一譯名", "オベロン\t奥伯龙\t奧伯隆")
        assertEquals(a.cacheIdentity, reordered.cacheIdentity)
        assertNotEquals(a.cacheIdentity, b.cacheIdentity)
        val legacy = listOf("model", "source")
        fun key(locale: String, general: Boolean, identity: String) = extendGeneralPromptCacheMaterial(
            legacy, general, targetLanguage = locale, traditionalGlossaryIdentity = identity)
        assertNotEquals(key("zh-Hant", true, a.cacheIdentity), key("zh-Hant", true, b.cacheIdentity))
        for (locale in listOf("zh-Hans", "en")) {
            assertEquals(key(locale, true, ""), key(locale, true, a.cacheIdentity))
        }
        assertSame(legacy, key("zh-Hant", false, a.cacheIdentity))
    }

    @Test fun bundledTableContainsCharactersAndTermsInExactlyThreeColumns() {
        val file = listOf(File("src/main/assets/$TRADITIONAL_GLOSSARY_ASSET"),
            File("app/src/main/assets/$TRADITIONAL_GLOSSARY_ASSET")).first(File::isFile)
        val index = file.useLines { TraditionalGlossaryOverrides.parse(it) }
        assertEquals("奧伯隆", index.findTarget(listOf("オベロン"), "奥伯龙"))
        assertEquals("紫苑", index.findTarget(listOf("シオン"), "希翁"))
        assertEquals("雪赫拉莎德", index.findTarget(listOf("シェヘラザード"), "山鲁佐德"))
        assertEquals("海森·羅伯", index.findTarget(listOf("ヘシアン・ロボ"), "黑森·罗伯"))
        assertEquals("海森", index.findTarget(listOf("ヘシアン"), "黑森"))
        assertEquals("紫苑·艾爾特納姆·索卡里斯", index.findTarget(
            listOf("シオン・エルトナム・ソカリス"), "希翁·艾尔特纳姆·索卡里斯"))
        assertEquals("奧伯隆·沃帝根", index.findTarget(listOf("オベロン·ヴォーティガーン"), "奥伯龙·伏提庚"))
        assertEquals("奧林帕斯", index.findTarget(listOf("オリュンポス"), "奥林波斯"))
        assertEquals("無限劍製", index.findTarget(listOf("無限の剣製"), "无限剑制"))
        assertEquals("萬符必行破戒", index.findTarget(listOf("破戒すべき全ての符", "ルールブレイカー"), "万符必应破戒"))
        assertNull(index.findTarget(listOf("ルールブレイカー"), "已更新譯名"))
    }

    @Test fun scheherazadeHasAnExplicitBaseCharacterRowForChineseAndEnglishLookup() {
        val file = listOf(File("../term_builder/character_names.tsv"),
            File("term_builder/character_names.tsv")).first(File::isFile)
        val row = file.readLines(Charsets.UTF_8).drop(1).map { it.split('\t') }
            .single { it.first() == "シェヘラザード" }
        assertEquals("山鲁佐德", row[1])
        assertEquals("Scheherazade", row[2])
        assertEquals("女性", row[4])
        val character = CharacterNameEntity(jpName = row[0], cnName = row[1], enName = row[2])
        assertEquals("山鲁佐德", NameBoxLookupPolicy.findTranslation(row[0], listOf(character), false))
        assertEquals("Scheherazade", NameBoxLookupPolicy.findTranslation(row[0], listOf(character), true))
        assertNull(NameBoxLookupPolicy.findTranslation("不夜城のキャスター", listOf(character), false))
    }

}
