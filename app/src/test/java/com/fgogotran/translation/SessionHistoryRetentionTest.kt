package com.fgogotran.translation

import kotlin.test.*

class SessionHistoryRetentionTest {
    @BeforeTest fun setUp() { SessionTranslationHistory.clear() }
    @AfterTest fun tearDown() { SessionTranslationHistory.clear() }

    @Test fun `all entries beyond 100 survive during a service session`() {
        repeat(1_000) { index ->
            SessionTranslationHistory.add(SessionTranslationEntry(dialogueText = "译文" + index, sourceKey = "story:" + index))
        }
        val entries = SessionTranslationHistory.entries.value
        assertEquals(1_000, entries.size)
        assertEquals("译文0", entries.first().dialogueText)
        assertEquals("译文999", entries.last().dialogueText)
        assertEquals(1_000, entries.map { it.historyId }.distinct().size)
    }

    @Test fun `successful battle result is logged without waiting for overlay and without speaker context`() {
        val token = SessionTranslationHistory.reserveBattleEntry("battle:1", "「まずは小手調べ♪」")
        assertTrue(SessionTranslationHistory.entries.value.isEmpty())
        assertTrue(SessionTranslationHistory.completeBattleEntry(token, "先小试一下♪", "zh-Hans"))
        val entry = SessionTranslationHistory.entries.value.single()
        assertNull(entry.speakerName)
        assertEquals("先小试一下♪", entry.dialogueText)
        assertEquals("「まずは小手調べ♪」", entry.originalDialogueText)
        assertTrue(entry.choices.isEmpty())
        assertTrue(entry.battleOccurrence)
        assertTrue(SessionTranslationHistory.lastSceneDialogueContexts().isEmpty())
    }

    @Test fun `battle occurrence is never prompt context even if context fields are populated`() {
        SessionTranslationHistory.add(SessionTranslationEntry(
            dialogueText = "戰鬥譯文",
            contextSourceDialogue = "MUST_NOT_APPEAR",
            contextTranslatedDialogue = "禁止使用",
            sourceKey = "battle:defensive-check",
            battleOccurrence = true
        ))

        assertEquals(1, SessionTranslationHistory.entries.value.size)
        assertTrue(SessionTranslationHistory.lastSceneDialogueContexts().isEmpty())
    }

    @Test fun `out of order battle completion preserves source order among story records`() {
        val first = SessionTranslationHistory.reserveBattleEntry("battle:1", "A")
        SessionTranslationHistory.add(SessionTranslationEntry(dialogueText = "story", sourceKey = "story"))
        val second = SessionTranslationHistory.reserveBattleEntry("battle:2", "B")
        SessionTranslationHistory.completeBattleEntry(second, "B译文", "zh-Hans")
        SessionTranslationHistory.completeBattleEntry(first, "A译文", "zh-Hans")
        assertEquals(listOf("A译文", "story", "B译文"), SessionTranslationHistory.entries.value.map { it.dialogueText })
    }

    @Test fun `same occurrence is recorded once but repeated sentence is a separate entry`() {
        val first = SessionTranslationHistory.reserveBattleEntry("battle:1", "フォウ？")
        val second = SessionTranslationHistory.reserveBattleEntry("battle:2", "フォウ？")
        assertTrue(SessionTranslationHistory.completeBattleEntry(first, "芙？", "zh-Hans"))
        assertFalse(SessionTranslationHistory.completeBattleEntry(first, "芙？", "zh-Hans"))
        assertTrue(SessionTranslationHistory.completeBattleEntry(second, "芙？", "zh-Hans"))
        assertEquals(2, SessionTranslationHistory.entries.value.size)
    }

    @Test fun `stopping service clears records and rejects previous session late callback`() {
        val old = SessionTranslationHistory.reserveBattleEntry("battle:1", "old")
        SessionTranslationHistory.add(SessionTranslationEntry(dialogueText = "old story"))
        SessionTranslationHistory.clear()
        val current = SessionTranslationHistory.reserveBattleEntry("battle:1", "new")
        assertFalse(SessionTranslationHistory.completeBattleEntry(old, "旧译文", "zh-Hans"))
        assertTrue(SessionTranslationHistory.entries.value.isEmpty())
        assertTrue(SessionTranslationHistory.completeBattleEntry(current, "新译文", "zh-Hans"))
        assertEquals("新译文", SessionTranslationHistory.entries.value.single().dialogueText)
    }

    @Test fun `story duplicate update rules are retained with stable row identity`() {
        val original = SessionTranslationEntry(dialogueText = "第一版", sourceKey = "story:1")
        SessionTranslationHistory.add(original)
        val rowId = SessionTranslationHistory.entries.value.single().historyId
        SessionTranslationHistory.add(original)
        assertEquals(1, SessionTranslationHistory.entries.value.size)
        SessionTranslationHistory.add(original.copy(dialogueText = "修正版"))
        assertEquals(rowId, SessionTranslationHistory.entries.value.single().historyId)
        assertEquals("修正版", SessionTranslationHistory.entries.value.single().dialogueText)
    }

    @Test fun `unlimited LOG does not expand prompt context or include battle translations`() {
        repeat(110) { index ->
            SessionTranslationHistory.add(SessionTranslationEntry(
                dialogueText = "CN" + index,
                contextSourceDialogue = "JP" + index,
                contextTranslatedDialogue = "CN" + index,
                sourceKey = "story:" + index
            ))
        }
        val battle = SessionTranslationHistory.reserveBattleEntry("battle:1", "戦闘")
        SessionTranslationHistory.completeBattleEntry(battle, "战斗", "zh-Hans")
        assertEquals(111, SessionTranslationHistory.entries.value.size)
        assertEquals(listOf("JP108", "JP109"), SessionTranslationHistory.lastSceneDialogueContexts().map { it.sourceDialogue })
    }

    @Test fun `battle boundary clears prompt context without clearing visible LOG`() {
        SessionTranslationHistory.add(SessionTranslationEntry(
            dialogueText = "舊譯文",
            contextSourceDialogue = "古い場面",
            contextTranslatedDialogue = "舊譯文",
            sourceKey = "story:old"
        ))

        SessionTranslationHistory.clearSceneDialogueContext()

        assertEquals(1, SessionTranslationHistory.entries.value.size)
        assertEquals("舊譯文", SessionTranslationHistory.entries.value.single().dialogueText)
        assertTrue(SessionTranslationHistory.lastSceneDialogueContexts().isEmpty())
    }

    @Test fun `new story after battle uses only its own context generation`() {
        SessionTranslationHistory.add(SessionTranslationEntry(
            dialogueText = "戰前",
            contextSourceDialogue = "戦闘前",
            contextTranslatedDialogue = "戰前",
            sourceKey = "story:before"
        ))
        SessionTranslationHistory.clearSceneDialogueContext()
        SessionTranslationHistory.add(SessionTranslationEntry(
            dialogueText = "戰後",
            contextSourceDialogue = "戦闘後",
            contextTranslatedDialogue = "戰後",
            sourceKey = "story:after"
        ))

        assertEquals(2, SessionTranslationHistory.entries.value.size)
        assertEquals(
            listOf("戦闘後"),
            SessionTranslationHistory.lastSceneDialogueContexts().map { it.sourceDialogue }
        )
    }

    @Test fun `late pre-battle story remains logged but cannot re-enter context`() {
        val oldGeneration = SessionTranslationHistory.currentSceneContextGeneration()
        SessionTranslationHistory.clearSceneDialogueContext()
        SessionTranslationHistory.add(SessionTranslationEntry(
            dialogueText = "遲到的舊譯文",
            contextSourceDialogue = "遅れて完了した古い場面",
            sourceKey = "story:late-old",
            sceneContextGeneration = oldGeneration
        ))

        assertEquals(1, SessionTranslationHistory.entries.value.size)
        assertTrue(SessionTranslationHistory.lastSceneDialogueContexts().isEmpty())
    }

    @Test fun `same source after battle creates a new LOG row and fresh context`() {
        val repeated = SessionTranslationEntry(
            dialogueText = "相同台詞",
            contextSourceDialogue = "同じ台詞",
            contextTranslatedDialogue = "相同台詞",
            sourceKey = "story:same",
            dialogueSourceKey = "story:same"
        )
        SessionTranslationHistory.add(repeated)
        SessionTranslationHistory.clearSceneDialogueContext()
        SessionTranslationHistory.add(repeated)

        assertEquals(2, SessionTranslationHistory.entries.value.size)
        assertEquals("同じ台詞", SessionTranslationHistory.lastSceneDialogueContexts().single().sourceDialogue)
    }
}
