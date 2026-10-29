package com.fgogotran.translation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class GeneralEvaluatedPromptPolicyTest {
    private val builder = PromptBuilder()
    private val profile = loadBeniEnmaCharacterContextForTest()

    @Test
    fun `no tic source keeps base voice without priming unused tic examples`() {
        for (target in listOf("zh-Hans", "zh-Hant", "en")) {
            for (source in listOf("ここで待っていてください。", "こちらで待っていてください。", "落ち着いてください。……動くな！")) {
                val selected = prepareGeneralCharacterContext(profile, source, target)
                assertTrue(selected.prompt.contains("polite, earnest"))
                assertFalse(selected.prompt.contains("chirp"))
                assertFalse(selected.prompt.contains("啾"))
                assertFalse(selected.prompt.contains("でち"))
                assertTrue(selected.cacheIdentity.contains(selected.prompt))
            }
        }
    }

    @Test
    fun `possible source tic retains the complete existing general guidance`() {
        for (source in listOf("我慢するのでち……！", "まだ食べてはいけまちぇん。", "待つのでちゅ。", "わたくち。", "ﾃﾞﾁ")) {
            for (target in listOf("zh-Hans", "zh-Hant", "en")) {
                assertEquals(profile.promptFor(target, false), prepareGeneralCharacterContext(profile, source, target).prompt)
            }
        }
    }

    @Test
    fun `selected hint content controls cache identity and unrelated profiles use the legacy fallback`() {
        val plain = prepareGeneralCharacterContext(profile, "待ってください。", "en")
        val tic = prepareGeneralCharacterContext(profile, "待つのでち。", "en")
        assertNotEquals(plain.cacheIdentity, tic.cacheIdentity)
        val changed = profile.copy(generalRule = requireNotNull(profile.generalRule).copy(baseEnglish = "Revised base voice."))
        assertNotEquals(plain.cacheIdentity, prepareGeneralCharacterContext(changed, "待ってください。", "en").cacheIdentity)
        val other = profile.copy(speakerId = "Other", generalRule = null)
        val fallback = prepareGeneralCharacterContext(other, "待つのでち。", "en")
        assertEquals(other.promptFor("en", false), fallback.prompt)
        assertEquals(other.cacheIdentityFor("en", false), fallback.cacheIdentity)
    }

    @Test
    fun `merged conditional hints keep the existing cache namespace and prompt material`() {
        val rule = requireNotNull(profile.generalRule)
        for (target in listOf("zh-Hans", "zh-Hant", "en")) {
            val english = target == "en"
            for (source in listOf("待ってください。", "待つのでち。")) {
                val selected = prepareGeneralCharacterContext(profile, source, target)
                val expected = if (source.contains("でち")) profile.promptFor(target, false)
                    else if (english) rule.baseEnglish else rule.baseGeneral
                assertEquals(expected, selected.prompt)
                assertEquals(listOf(profile.speakerId, "general-source-voice-v1",
                    if (english) "english" else "general", expected).joinToString("\u001D"), selected.cacheIdentity)
            }
        }
    }

    @Test
    fun `literal player names get a separate general instruction while shared rendering stays the same`() {
        val entries = listOf(
            TranslationGlossaryEntry("九条京香", "九条京香", "female; player name"),
            TranslationGlossaryEntry("ぐだ子", "ぐだ子", "女性；玩家名"),
            TranslationGlossaryEntry("マシュ", "Mash", "player name"),
            TranslationGlossaryEntry("アヴァロン", "Avalon")
        )
        assertEquals(listOf("九条京香", "ぐだ子"), TranslationGlossaryBuilder.protectedPlayerNameTargets(entries))
        val shared = TranslationGlossaryBuilder.render(entries)
        assertFalse(shared.contains("Protected player names"))
        val general = buildGeneralSingleUserPrompt(builder, "九条京香、頼む。", emptyList(), glossaryEntries = entries)
        assertTrue(general.contains("Protected player names (copy exactly; never translate, transliterate or romanize):\n九条京香\nぐだ子"))
        assertFalse(buildGeneralBatchUserPrompt(listOf("行こう。"), glossaryEntries = entries.takeLast(1)).contains("Protected player names"))
    }

    @Test
    fun `traditional anchor is limited to the ordinary general profile`() {
        val ordinary = builder.buildSystemPrompt(builder.buildPromptContext(PromptOutputFormat.PLAIN_TEXT, "待て。", targetLanguage = "zh-Hant"))
        assertTrue(ordinary.contains("in-game 繁體中文 (Traditional Chinese)"))
        assertTrue(ordinary.contains("Return only the 繁體中文 translation"))
        for (context in listOf(
            builder.buildPromptContext(PromptOutputFormat.PLAIN_TEXT, "待て。", targetLanguage = "zh-Hans"),
            builder.buildPromptContext(PromptOutputFormat.PLAIN_TEXT, "待て。", targetLanguage = "en"),
            builder.buildPromptContext(PromptOutputFormat.PLAIN_TEXT, "待て。", targetLanguage = "zh-Hant", isCropMode = true),
            builder.buildPromptContext(PromptOutputFormat.PLAIN_TEXT, "待て。", targetLanguage = "zh-Hant", promptProfile = TranslationPromptProfile.BATTLE_SUBTITLE)
        )) assertFalse(builder.buildSystemPrompt(context).contains("繁體中文"))
    }
}
