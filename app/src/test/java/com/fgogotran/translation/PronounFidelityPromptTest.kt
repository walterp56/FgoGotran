package com.fgogotran.translation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PronounFidelityPromptTest {
    private val promptBuilder = PromptBuilder()
    private val generalTargets = mapOf(
        "zh-Hans" to "Simplified Chinese",
        "zh-Hant" to "Traditional Chinese",
        "en" to "English"
    )

    @Test
    fun `general core preserves uncertainty repetition and unfinished source for every target`() {
        for ((target, label) in generalTargets) {
            val prompt = promptBuilder.buildSystemPrompt(PromptContext(targetLanguage = target))
            val fidelityLine = "Translate into concise, natural in-game $label; keep every " +
                "meaning, role, relationship, uncertainty, ambiguity, repetition, and ellipsis, " +
                "and add nothing."

            assertTrue(
                prompt.contains(
                    "$fidelityLine\nKeep fragments and interrupted sentences unfinished; " +
                        "never summarize or complete the source."
                ),
                target
            )
        }
    }

    @Test
    fun `general user prompt keeps fragments repetition hypothesis punctuation and inline ruby intact`() {
        val sources = listOf(
            "それは、つまり───",
            "違う、違う。\nまだ、まだ……！",
            "もしかしたら、まだ夢の中なのかもしれない……？",
            "……本当に！？",
            "南瓜狼〈パンプキンウルフ〉……？"
        )

        for (target in generalTargets.keys) {
            for (source in sources) {
                val context = promptBuilder.buildPromptContext(
                    outputFormat = PromptOutputFormat.PLAIN_TEXT,
                    sourceText = source,
                    targetLanguage = target
                )

                assertTrue(
                    promptBuilder.buildSystemPrompt(context)
                        .contains("never summarize or complete the source."),
                    target
                )
                assertEquals(
                    "Current Japanese:\n$source",
                    promptBuilder.buildUserPrompt(source, emptyList()),
                    "$target: $source"
                )
            }
        }
    }

    @Test
    fun `general choices names ruby and participant cards remain independently conditional`() {
        for (target in generalTargets.keys) {
            val baseline = PromptContext(targetLanguage = target)
            val cards = listOf(
                "Choices are Master/player replies" to baseline.copy(hasChoices = true),
                "Name-box: preserve every visible title" to baseline.copy(hasName = true),
                "base〈ruby〉" to baseline.copy(hasRuby = true),
                "preserve action direction and possession from Japanese syntax" to
                    baseline.copy(hasBenefactivePassiveCausative = true)
            )
            val plainPrompt = promptBuilder.buildSystemPrompt(baseline)

            for ((marker, context) in cards) {
                assertFalse(plainPrompt.contains(marker), "$target: $marker")
                val prompt = promptBuilder.buildSystemPrompt(context)
                assertTrue(prompt.contains(marker), "$target: $marker")
                for ((otherMarker, _) in cards.filter { it.first != marker }) {
                    assertFalse(prompt.contains(otherMarker), "$target: $otherMarker")
                }
            }
        }
    }

    @Test
    fun `English subjects preserve fragments and short player choices`() {
        val prompt = promptBuilder.buildSystemPrompt(
            PromptContext(targetLanguage = "en", hasChoices = true)
        )

        assertTrue(
            prompt.contains(
                "Use an explicit subject only when English grammar requires one; preserve " +
                    "natural fragments and imperatives, and never invent a person or relationship."
            )
        )
        assertFalse(prompt.contains("English needs an explicit subject;"))
        assertTrue(
            prompt.contains(
                "Translate second-person address as \"you\" (or an English insult when hostile), " +
                    "never a name."
            )
        )
        assertTrue(
            prompt.contains(
                "keep the elliptical English style (\"Yes.\", \"Understood.\") and add \"I\" " +
                    "only when English grammar requires a subject and the choice is clearly " +
                    "the player's own act."
            )
        )
        assertTrue(prompt.contains("do not expand partial or attitude choices into full explanations."))
    }

    @Test
    fun `general array output keeps item count and order for every target`() {
        for (target in generalTargets.keys) {
            val prompt = promptBuilder.buildSystemPrompt(
                PromptContext(targetLanguage = target, outputFormat = PromptOutputFormat.JSON_ARRAY)
            )

            assertTrue(
                prompt.contains("Return a JSON array only; preserve item count and order."),
                target
            )
        }
    }

    @Test
    fun `general cache versions advance while battle versions remain unchanged`() {
        for (target in generalTargets.keys) {
            val english = target == "en"
            assertEquals(
                if (english) "jp-en-fgo-target-v4-plain-layout" else "jp-cn-fgo-target-v96-plain-layout",
                PromptBuilder.promptVersionFor(target, battleSubtitle = false),
                target
            )
            assertEquals(
                if (english) "battle-subtitle-en-v3-plain-layout" else "battle-subtitle-v9-plain-layout",
                PromptBuilder.promptVersionFor(target, battleSubtitle = true),
                target
            )
        }
    }

    @Test
    fun `dialogue core states reference action-role and omission fidelity`() {
        val prompt = promptBuilder.buildSystemPrompt(
            context = PromptContext(isDialogue = true)
        )

        assertTrue(prompt.contains("Preserve stated references and action roles"))
        assertTrue(prompt.contains("never infer them from speaker identity"))
        assertFalse(prompt.contains("Infer omitted participants and possessors"))
    }

    @Test
    fun `choice prompt does not infer first person from player role`() {
        val prompt = promptBuilder.buildSystemPrompt(
            context = PromptContext(
                isDialogue = false,
                hasChoices = true
            )
        )

        assertTrue(
            prompt.contains(
                "this role never licenses a first-person pronoun absent from the choice source"
            )
        )
    }

    @Test
    fun `crop prompt keeps row order and glossary discipline`() {
        val prompt = promptBuilder.buildSystemPrompt(
            context = PromptContext(
                isDialogue = false,
                isCropMode = true
            )
        )

        assertTrue(prompt.contains("row by row"))
        assertTrue(prompt.contains("glossary targets exact"))
        assertTrue(prompt.contains("never merge, split, add, omit, or complete text"))
        assertFalse(prompt.contains("never infer them from speaker identity"))
    }

    @Test
    fun `participant direction card preserves roles without forcing passive`() {
        val prompt = promptBuilder.buildSystemPrompt(
            context = PromptContext(
                isDialogue = true,
                hasBenefactivePassiveCausative = true
            )
        )

        assertTrue(prompt.contains("preserve action direction and possession from Japanese syntax"))
        assertTrue(prompt.contains("do not default to passive"))
    }

    @Test
    fun `previous bilingual context includes trusted JP and CN pairs`() {
        val prompt = buildPreviousSceneBilingualContextPrompt(
            listOf(
                SceneDialogueContext(
                    sourceSpeakerName = "マシュ",
                    translatedSpeakerName = "玛修",
                    sourceDialogue = "行きましょう。",
                    translatedDialogue = "我们走吧。",
                    targetLocale = "zh-CN",
                    dialogueSourceKey = "scene-1"
                )
            )
        )

        assertTrue(prompt.contains("Previous context (reference only)"))
        assertTrue(prompt.contains("never output or continue these scenes"))
        assertTrue(prompt.contains("Speaker JP: マシュ"))
        assertTrue(prompt.contains("Speaker CN: 玛修"))
        assertTrue(prompt.contains("JP: 行きましょう。"))
        assertTrue(prompt.contains("CN: 我们走吧。"))
    }

    @Test
    fun `empty history does not add context instructions`() {
        assertEquals("", buildPreviousSceneBilingualContextPrompt(emptyList()))
    }
}
