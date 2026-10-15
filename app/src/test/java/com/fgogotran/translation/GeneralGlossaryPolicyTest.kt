package com.fgogotran.translation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GeneralGlossaryPolicyTest {
    private val builder = PromptBuilder()
    private val targets = listOf("zh-Hans", "zh-Hant", "en")
    private val masterNote = "player title only; for the verb マスターする and its inflections, " +
        "translate the meaning of mastering or learning instead"

    @Test
    fun `all bases scope glossary meanings and preserve explicit kana literals`() {
        for (target in targets) {
            for (context in listOf(
                PromptContext(targetLanguage = target),
                PromptContext(targetLanguage = target, isCropMode = true, isDialogue = false),
                PromptContext(targetLanguage = target, promptProfile = TranslationPromptProfile.BATTLE_SUBTITLE)
            )) {
                val prompt = builder.buildSystemPrompt(context)
                assertTrue(prompt.contains("Use glossary targets exactly for their specified source meanings"))
                assertTrue(
                    prompt.contains(
                        "Preserve configured player names and explicitly protected literals verbatim, including kana."
                    )
                )
                assertTrue(prompt.contains(if (target == "en") "leave no other kana" else "all other kana"))
                assertFalse(prompt.contains("Use each listed glossary target exactly"))
            }
        }
    }

    @Test
    fun `general master qualification handles RAG entries and remains idempotent`() {
        val entries = listOf(
            TranslationGlossaryEntry("マスター", "Master", "female; RAG"),
            TranslationGlossaryEntry("マスター", "御主", "女性"),
            TranslationGlossaryEntry("マスター", "Master"),
            TranslationGlossaryEntry("グランドマスター", "Master"),
            TranslationGlossaryEntry("マスター", "マスター", "exact player name"),
            TranslationGlossaryEntry("マスター", "Captain")
        )
        val qualified = TranslationGlossaryBuilder.qualifyGeneral(entries)
        assertEquals(entries.map { it.source }, qualified.map { it.source })
        assertEquals(entries.map { it.target }, qualified.map { it.target })
        qualified.take(3).forEach { assertTrue(it.note.contains(masterNote)) }
        assertEquals("female; RAG; $masterNote", qualified[0].note)
        assertEquals(entries.drop(3), qualified.drop(3))
        assertEquals(qualified, TranslationGlossaryBuilder.qualifyGeneral(qualified))
    }

    @Test
    fun `shared build and render retain legacy mappings and annotations`() {
        for (target in targets) {
            val context = builder.buildPromptContext(
                outputFormat = PromptOutputFormat.PLAIN_TEXT,
                sourceText = "マスター、技をマスターした？",
                targetLanguage = target,
                playerGender = "female"
            )
            val expectedTarget = if (target == "en") "Master" else "御主"
            val raw = TranslationGlossaryBuilder.build(
                "マスター、技をマスターした？", context,
                listOf(TranslationGlossaryEntry("マスター", expectedTarget, "RAG")), "", false
            )
            assertEquals(expectedTarget, raw.single().target)
            assertFalse(raw.single().note.contains(masterNote))
            val legacyPrompt = buildString { appendTranslationGlossarySection(raw) }
            assertTrue(legacyPrompt.startsWith("Glossary (mandatory — use each target exactly as listed):"))
            assertFalse(legacyPrompt.contains(masterNote))
            val generalPrompt = buildString { appendGeneralTranslationGlossarySection(raw) }
            assertTrue(generalPrompt.contains(masterNote))
            assertTrue(generalPrompt.contains("マスター->$expectedTarget"))
        }
        val raw = listOf(TranslationGlossaryEntry("マスター", "Master", "female"))
        assertEquals("マスター->Master #female", TranslationGlossaryBuilder.render(raw))
    }

    @Test
    fun `general glossary instructions prohibit insertion and omitted-reference gender inference`() {
        val entry = TranslationGlossaryEntry("マシュ", "Mash", "female")
        val prompt = builder.buildUserPrompt("行こう。", emptyList(), listOf(entry))
        assertTrue(prompt.contains("Glossary (exact targets for matching source meanings):"))
        assertTrue(prompt.contains("Use a listed target exactly when the source occurrence has its specified meaning."))
        assertTrue(prompt.contains("Do not insert an entry merely because it is listed."))
        assertTrue(
            prompt.contains(
                "Gender notes apply only to explicit references to that person, never to omitted subjects or possessors."
            )
        )
        assertTrue(prompt.contains("マシュ->Mash #female"))
        assertFalse(prompt.contains("mandatory — use each target exactly as listed"))
        assertEquals("", buildString { appendGeneralTranslationGlossarySection(emptyList()) })
    }

    @Test
    fun `English shi and tono guidance is conditional and infers neither gender nor rank`() {
        val sources = mapOf(
            "ロマニ氏" to ("氏 expresses respectful reference" to "殿 expresses formal address"),
            "マシュ殿" to ("殿 expresses formal address" to "氏 expresses respectful reference")
        )
        for ((source, markers) in sources) {
            val context = builder.buildPromptContext(PromptOutputFormat.PLAIN_TEXT, source, targetLanguage = "en")
            val prompt = builder.buildSystemPrompt(context)
            assertTrue(prompt.contains(markers.first))
            assertFalse(prompt.contains(markers.second))
            assertTrue(prompt.contains("Preserve that respect without inferring gender or noble rank from the suffix."))
            assertTrue(prompt.contains("Use an English title only when Japanese or explicit context supports it."))
            assertFalse(prompt.contains("XX氏->Mr. XX"))
            assertFalse(prompt.contains("XX殿->Lord XX"))
        }
        for (source in listOf("彼氏", "神殿", "行こう。")) {
            val context = builder.buildPromptContext(PromptOutputFormat.PLAIN_TEXT, source, targetLanguage = "en")
            assertFalse(builder.buildSystemPrompt(context).contains("without inferring gender or noble rank"))
        }
    }
}
