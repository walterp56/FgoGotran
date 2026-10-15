package com.fgogotran.translation

import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionTranslationHistoryTest {

    @Before
    fun setUp() {
        SessionTranslationHistory.clear()
    }

    @After
    fun tearDown() {
        SessionTranslationHistory.clear()
    }

    @Test
    fun `forced render is excluded from bilingual context`() {
        SessionTranslationHistory.add(
            SessionTranslationEntry(
                dialogueText = "まだ日本語",
                contextSourceDialogue = "まだ日本語",
                contextTranslatedDialogue = "まだ日本語",
                sourceKey = "scene-1",
                dialogueSourceKey = "scene-1",
                contextDialogueTranslationTrusted = false
            )
        )

        assertEquals(1, SessionTranslationHistory.entries.value.size)
        assertTrue(SessionTranslationHistory.lastSceneDialogueContexts().isEmpty())
    }

    @Test
    fun `validated render remains available as prompt context`() {
        SessionTranslationHistory.add(
            SessionTranslationEntry(
                dialogueText = "已经翻译",
                contextSourceDialogue = "翻訳済み",
                contextTranslatedDialogue = "已经翻译",
                sourceKey = "scene-2",
                dialogueSourceKey = "scene-2",
                contextDialogueTranslationTrusted = true
            )
        )

        val contexts = SessionTranslationHistory.lastSceneDialogueContexts()

        assertEquals(1, contexts.size)
        assertEquals("翻訳済み", contexts.single().sourceDialogue)
        assertEquals("已经翻译", contexts.single().translatedDialogue)
    }

    @Test
    fun `untrusted scene is skipped while collecting the last two bilingual contexts`() {
        fun addScene(
            sourceKey: String,
            sourceDialogue: String,
            translatedDialogue: String,
            translationTrusted: Boolean
        ) {
            SessionTranslationHistory.add(
                SessionTranslationEntry(
                    dialogueText = translatedDialogue,
                    contextSourceDialogue = sourceDialogue,
                    contextTranslatedDialogue = translatedDialogue,
                    sourceKey = sourceKey,
                    dialogueSourceKey = sourceKey,
                    contextDialogueTranslationTrusted = translationTrusted
                )
            )
        }

        addScene("scene-1", "最初の場面", "最初的场景", translationTrusted = true)
        addScene("scene-2", "次の場面", "不可信的结果", translationTrusted = false)
        addScene("scene-3", "最後の場面", "最后的场景", translationTrusted = true)

        val contexts = SessionTranslationHistory.lastSceneDialogueContexts(limit = 2)

        assertEquals(listOf("最初の場面", "最後の場面"), contexts.map { it.sourceDialogue })
        assertEquals("最初的场景", contexts.first().translatedDialogue)
        assertEquals("最后的场景", contexts.last().translatedDialogue)
    }

    @Test
    fun `manual retranslation replaces same-source dialogue in history and future context`() {
        fun entry(translation: String) = SessionTranslationEntry(
            dialogueText = translation,
            contextSourceDialogue = "努力させる、というのは酷な話ではある。",
            contextTranslatedDialogue = translation,
            sourceKey = "manual-retry-scene",
            dialogueSourceKey = "manual-retry-scene"
        )
        SessionTranslationHistory.add(entry("让他们努力。"))
        val historyId = SessionTranslationHistory.entries.value.single().historyId

        SessionTranslationHistory.add(entry("让他们努力，确实是件残酷的事。"))

        val updated = SessionTranslationHistory.entries.value.single()
        assertEquals(historyId, updated.historyId)
        assertEquals("让他们努力，确实是件残酷的事。", updated.dialogueText)
        assertEquals(
            updated.dialogueText,
            SessionTranslationHistory.lastSceneDialogueContexts().single().translatedDialogue
        )
    }

    @Test
    fun `manual retry excludes its own previous incomplete answer from context`() {
        SessionTranslationHistory.add(
            SessionTranslationEntry(
                dialogueText = "旧的不完整译文。",
                contextSourceDialogue = "現在の場面。",
                contextTranslatedDialogue = "旧的不完整译文。",
                sourceKey = "current-scene",
                dialogueSourceKey = "current-scene"
            )
        )

        assertTrue(
            SessionTranslationHistory.lastSceneDialogueContexts(
                excludeDialogueSourceKey = "current-scene"
            ).isEmpty()
        )
        assertEquals(1, SessionTranslationHistory.entries.value.size)
    }

    @Test
    fun `named scene requires both Japanese and Chinese speaker names`() {
        SessionTranslationHistory.add(
            SessionTranslationEntry(
                contextSourceSpeakerName = "ホームズ",
                contextSourceDialogue = "当然だ。",
                contextTranslatedDialogue = "当然。",
                sourceKey = "scene-4",
                dialogueSourceKey = "scene-4",
                contextDialogueTranslationTrusted = true
            )
        )

        assertTrue(SessionTranslationHistory.lastSceneDialogueContexts().isEmpty())
    }

    @Test
    fun `duplicate refreshes prepared source without changing LOG content or row identity`() {
        val raw = "おかあさん〈マスター〉、いい？"
        val original = SessionTranslationEntry(
            speakerName = "傑克",
            dialogueText = "媽媽，可以嗎？",
            originalDialogueText = raw,
            contextSourceSpeakerName = "ジャック",
            contextTranslatedSpeakerName = "傑克",
            contextSourceDialogue = raw,
            contextTranslatedDialogue = "媽媽，可以嗎？",
            sourceKey = "raw-scene-key",
            dialogueSourceKey = "raw-dialogue-key",
            speakerNameColor = 1,
            dialogueTextColor = 2,
            createdAt = 100L
        )
        SessionTranslationHistory.add(original)
        val existing = SessionTranslationHistory.entries.value.single()

        SessionTranslationHistory.add(
            original.copy(
                generalPreparedSourceDialogue = "おかあさん、いい？",
                sourceKey = "incoming-key",
                speakerNameColor = 3,
                createdAt = 200L
            )
        )

        assertEquals(
            existing.copy(generalPreparedSourceDialogue = "おかあさん、いい？"),
            SessionTranslationHistory.entries.value.single()
        )
        val context = SessionTranslationHistory.lastSceneDialogueContexts().single()
        assertEquals(raw, context.sourceDialogue)
        assertEquals("おかあさん、いい？", context.generalPreparedSourceDialogue)
        assertEquals("raw-dialogue-key", context.dialogueSourceKey)

        SessionTranslationHistory.add(original)

        assertEquals(existing, SessionTranslationHistory.entries.value.single())
        assertNull(SessionTranslationHistory.lastSceneDialogueContexts().single().generalPreparedSourceDialogue)
    }

    @Test
    fun `early duplicate refresh does not suppress dialogue on an existing choice row`() {
        val original = SessionTranslationEntry(
            dialogueText = "媽媽，可以嗎？",
            originalDialogueText = "おかあさん〈マスター〉、いい？",
            contextSourceDialogue = "おかあさん〈マスター〉、いい？",
            contextTranslatedDialogue = "媽媽，可以嗎？",
            choices = listOf("可以。"),
            originalChoices = listOf("いいよ。"),
            sourceKey = "choice-scene",
            dialogueSourceKey = "choice-dialogue"
        )
        SessionTranslationHistory.add(original)
        val existing = SessionTranslationHistory.entries.value.single()

        SessionTranslationHistory.add(original.copy(generalPreparedSourceDialogue = "おかあさん、いい？"))

        assertEquals(
            existing.copy(generalPreparedSourceDialogue = "おかあさん、いい？"),
            SessionTranslationHistory.entries.value.single()
        )
        assertEquals("媽媽，可以嗎？", SessionTranslationHistory.entries.value.single().dialogueText)
    }

    @Test
    fun `normalized duplicate clears stale prepared source on the existing choice row`() {
        val dialogue = SessionTranslationEntry(
            dialogueText = "可以。",
            contextSourceDialogue = "いいよ。",
            contextTranslatedDialogue = "可以。",
            sourceKey = "dialogue-scene",
            dialogueSourceKey = "same-dialogue",
            generalPreparedSourceDialogue = "いいよ。"
        )
        SessionTranslationHistory.add(dialogue)
        SessionTranslationHistory.add(dialogue.copy(choices = listOf("继续。"), sourceKey = "first-choice"))
        val existingChoice = SessionTranslationHistory.entries.value.last()
        SessionTranslationHistory.add(existingChoice.copy(generalPreparedSourceDialogue = "stale provenance"))

        SessionTranslationHistory.add(
            dialogue.copy(choices = listOf("继续。"), sourceKey = "other-choice")
        )

        assertEquals(2, SessionTranslationHistory.entries.value.size)
        assertEquals(existingChoice, SessionTranslationHistory.entries.value.last())
        assertNull(SessionTranslationHistory.entries.value.last().generalPreparedSourceDialogue)
    }
}
