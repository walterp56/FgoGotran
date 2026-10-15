package com.fgogotran.translation

import com.fgogotran.data.SettingsRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GeneralSceneContextTest {
    private val hans = SettingsRepository.TARGET_LANGUAGE_SIMPLIFIED
    private val hant = SettingsRepository.TARGET_LANGUAGE_TRADITIONAL
    private val english = SettingsRepository.TARGET_LANGUAGE_ENGLISH

    @Before
    fun setUp() = SessionTranslationHistory.clear()

    @After
    fun tearDown() = SessionTranslationHistory.clear()

    private fun context(
        source: String = "前の台詞。",
        translation: String? = "前一句台詞。",
        locale: String = hans,
        preparedSource: String? = null,
        speaker: String? = null,
        translatedSpeaker: String? = null,
        key: String = source
    ) = SceneDialogueContext(
        sourceSpeakerName = speaker,
        translatedSpeakerName = translatedSpeaker,
        sourceDialogue = source,
        translatedDialogue = translation,
        targetLocale = locale,
        dialogueSourceKey = key,
        generalPreparedSourceDialogue = preparedSource
    )

    private fun normalize(text: String): String = TextNormalizer.normalizeForTranslation(text)
        .lines().joinToString(" ")

    private fun select(
        contexts: List<SceneDialogueContext>,
        target: String = hans,
        convert: (String, String) -> String = { text, _ -> text }
    ) = prepareGeneralSceneDialogueContexts(contexts, target, ::normalize, ::normalize, convert)

    @Test
    fun `English request excludes Chinese references before any conversion`() {
        val chinese = context(source = "中国語の履歴。")
        val en = context(source = "英語の履歴。", translation = "An English reference.", locale = "en-US")
        val selected = select(listOf(chinese, en), english) { _, _ -> error("English reference conversion") }

        assertEquals(listOf("英語の履歴。"), selected.map { it.sourceDialogue })
        assertEquals(english, selected.single().targetLocale)
        assertEquals("An English reference.", selected.single().translatedDialogue)
        assertEquals("en-US", en.targetLocale)
    }

    @Test
    fun `Chinese request excludes English and converts both Chinese target variants`() {
        val englishContext = context(source = "英語の台詞。", translation = "A light.", locale = english)
        val simplified = context(
            source = "最初の台詞。", translation = "灯。", locale = hans,
            speaker = "母", translatedSpeaker = "妈"
        )
        val traditional = context(source = "次の台詞。", translation = "燈。", locale = hant)
        val conversions = mutableListOf<Pair<String, String>>()
        val selected = select(listOf(simplified, englishContext, traditional), hant) { text, target ->
            conversions += text to target
            text.replace("灯", "燈").replace("妈", "媽")
        }

        assertEquals(listOf("最初の台詞。", "次の台詞。"), selected.map { it.sourceDialogue })
        assertEquals(listOf("燈。", "燈。"), selected.map { it.translatedDialogue })
        assertEquals("媽", selected.first().translatedSpeakerName)
        assertTrue(selected.all { it.targetLocale == hant })
        assertTrue(conversions.all { it.second == hant })
        assertFalse(conversions.any { it.first == "A light." })
        assertEquals("灯。", simplified.translatedDialogue)
        assertEquals(hans, simplified.targetLocale)
    }

    @Test
    fun `traditional reference converts to simplified with its resulting language label`() {
        val selected = select(listOf(context(translation = "燈。", locale = hant)), hans) { text, target ->
            assertEquals(hans, target)
            text.replace("燈", "灯")
        }

        assertEquals("灯。", selected.single().translatedDialogue)
        val prompt = buildGeneralPreviousSceneContextPrompt(selected)
        assertTrue(prompt.contains("Simplified Chinese: 灯。"))
        assertFalse(prompt.contains("Traditional Chinese:"))
    }

    @Test
    fun `prepared source wins over raw history and retains source key`() {
        val original = context(
            source = "おかあさん〈マスター〉、いい？",
            preparedSource = "-おかあさん、いい？",
            key = "raw-key"
        )

        val selected = select(listOf(original)).single()

        assertEquals("───おかあさん、いい？", selected.sourceDialogue)
        assertEquals("raw-key", selected.dialogueSourceKey)
        assertEquals("おかあさん〈マスター〉、いい？", original.sourceDialogue)
        assertFalse(buildGeneralPreviousSceneContextPrompt(listOf(selected)).contains("〈マスター〉"))
    }

    @Test
    fun `legacy ruby without prepared provenance is omitted while plain legacy source is usable`() {
        val selected = select(listOf(
            context(source = "おかあさん〈マスター〉"),
            context(source = "南瓜狼〈" + "カ".repeat(40) + "〉"),
            context(source = "通常空間〈そ　と〉"),
            context(source = "普通の台詞。")
        ))

        assertEquals(listOf("普通の台詞。"), selected.map { it.sourceDialogue })
    }

    @Test
    fun `invalid pairs are excluded after normalization and valid scenes remain chronological`() {
        val selected = select(listOf(
            context(source = "最初。", speaker = " マシュ\n ", translatedSpeaker = " 玛修 "),
            context(source = "名前が欠落。", speaker = "ホームズ", translatedSpeaker = " \n "),
            context(source = "本文が欠落。", translation = " \n "),
            context(source = "raw source", preparedSource = " \n "),
            context(source = "最後。", translation = " 最后。\n続き ")
        ))

        assertEquals(listOf("最初。", "最後。"), selected.map { it.sourceDialogue })
        assertEquals("マシュ", selected.first().sourceSpeakerName)
        assertEquals("玛修", selected.first().translatedSpeakerName)
        assertEquals("最后。 続き", selected.last().translatedDialogue)
    }

    @Test
    fun `untranslated error references and incomplete named pairs are excluded`() {
        assertTrue(select(listOf(
            context(translation = "[Translation failed: timeout]"),
            context(speaker = "マシュ", translatedSpeaker = "[No API key]")
        )).isEmpty())
    }

    @Test
    fun `latest five snapshot is capped before filtering without older backfill`() {
        val olderCompatible = context(source = "古い互換履歴。", locale = english)
        val latestChinese = (1..5).map { context(source = "最新$it。", locale = hans) }

        assertTrue(select(listOf(olderCompatible) + latestChinese, english).isEmpty())
        assertEquals(
            (1..5).map { "最新$it。" },
            select(listOf(context(source = "古い。")) + latestChinese).map { it.sourceDialogue }
        )
    }

    @Test
    fun `provided scene count is not expanded after incompatible pair filtering`() {
        val snapshot = listOf(
            context(source = "英語。", translation = "English.", locale = english),
            context(source = "中国語。", locale = hans)
        )

        assertEquals(listOf("中国語。"), select(snapshot).map { it.sourceDialogue })
    }

    @Test
    fun `prompt labels each prior target language accurately and states Japanese authority`() {
        val prompt = buildGeneralPreviousSceneContextPrompt(listOf(
            context(source = "英語の元。", translation = "English reference.", locale = english,
                speaker = "マシュ", translatedSpeaker = "Mash"),
            context(source = "簡体字の元。", translation = "简体参考。", locale = hans,
                speaker = "ホームズ", translatedSpeaker = "福尔摩斯"),
            context(source = "繁体字の元。", translation = "繁體參考。", locale = hant,
                speaker = "ホームズ", translatedSpeaker = "福爾摩斯")
        ))

        assertTrue(prompt.contains("Speaker JP: マシュ"))
        assertTrue(prompt.contains("Speaker English: Mash"))
        assertTrue(prompt.contains("English: English reference."))
        assertTrue(prompt.contains("Speaker Simplified Chinese: 福尔摩斯"))
        assertTrue(prompt.contains("Simplified Chinese: 简体参考。"))
        assertTrue(prompt.contains("Speaker Traditional Chinese: 福爾摩斯"))
        assertTrue(prompt.contains("Traditional Chinese: 繁體參考。"))
        assertFalse(prompt.contains("CN:"))
        assertTrue(prompt.contains("Use previous Japanese only to interpret the current scene."))
        assertTrue(prompt.contains("Previous translations are fallible reference; if they conflict with Japanese, follow Japanese."))
        assertTrue(prompt.contains("Never output or continue previous scenes, or assume their speaker is speaking now."))
        assertTrue(prompt.indexOf("JP: 英語の元。") < prompt.indexOf("JP: 簡体字の元。"))
        assertEquals("", buildGeneralPreviousSceneContextPrompt(emptyList()))
    }

    @Test
    fun `ruby off then on preserves the prior base-only source translation pair`() {
        SessionTranslationHistory.add(SessionTranslationEntry(
            dialogueText = "Mother, okay?",
            originalDialogueText = "おかあさん〈マスター〉、いい？",
            contextSourceDialogue = "おかあさん〈マスター〉、いい？",
            contextTranslatedDialogue = "Mother, okay?",
            targetLocale = english,
            sourceKey = "ruby-off-scene",
            generalPreparedSourceDialogue = "おかあさん、いい？"
        ))
        val offRequest = TranslationRubyPolicy.prepare(
            SceneTranslateInput(null, "次の台詞。", emptyList(),
                previousDialogueContexts = SessionTranslationHistory.lastSceneDialogueContexts()),
            includeRuby = false
        )
        assertEquals("おかあさん、いい？", select(offRequest.previousDialogueContexts, english).single().sourceDialogue)

        // Switching back on takes a fresh raw history snapshot, not the prior filtered request copy.
        val previous = SessionTranslationHistory.lastSceneDialogueContexts().single()
        val next = SceneTranslateInput(null, "次の台詞。", emptyList(), previousDialogueContexts = listOf(previous))
        val request = TranslationRubyPolicy.prepare(next, includeRuby = true)
        val selected = select(request.previousDialogueContexts, english)

        assertSame(next, request)
        assertEquals("おかあさん、いい？", selected.single().sourceDialogue)
        assertEquals("Mother, okay?", selected.single().translatedDialogue)
        assertEquals("おかあさん〈マスター〉、いい？", previous.sourceDialogue)
    }

    @Test
    fun `ruby on then off strips request sidecar and target while preserving raw history`() {
        val previous = context(
            source = "農人〈ファーマー〉……",
            translation = "Farmer〈Farmer〉……",
            locale = english,
            preparedSource = "農人〈ファーマー〉……"
        )
        val next = SceneTranslateInput(null, "次の台詞。", emptyList(), previousDialogueContexts = listOf(previous))
        val request = TranslationRubyPolicy.prepare(next, includeRuby = false)
        val selected = select(request.previousDialogueContexts, english).single()

        assertEquals("農人……", selected.sourceDialogue)
        assertEquals("Farmer……", selected.translatedDialogue)
        assertEquals("農人……", selected.generalPreparedSourceDialogue)
        assertEquals("農人〈ファーマー〉……", previous.sourceDialogue)
        assertEquals("農人〈ファーマー〉……", previous.generalPreparedSourceDialogue)
        assertEquals("Farmer〈Farmer〉……", previous.translatedDialogue)
    }
}
