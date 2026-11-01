package com.fgogotran.translation

import kotlin.test.*

class SessionHistoryRetentionTest {
    @Test fun `summon subtitles preserve distinct source order without entering story context`() {
        val first = SessionTranslationHistory.reserveDialogueSubtitleEntry("dialogue:1", "なんだっけ？", "zh-Hant")
        val second = SessionTranslationHistory.reserveDialogueSubtitleEntry("dialogue:2", "ま、いいですよね。", "zh-Hant")
        assertTrue(SessionTranslationHistory.completeDialogueSubtitleEntry(second, "算了，沒關係吧。", "zh-Hant"))
        assertTrue(SessionTranslationHistory.completeDialogueSubtitleEntry(first, "是什麼來著？", "zh-Hant"))
        val entries = SessionTranslationHistory.entries.value
        assertEquals(listOf("是什麼來著？", "算了，沒關係吧。"), entries.map { it.dialogueText })
        assertTrue(entries.all { it.dialogueSubtitleOccurrence && !it.battleOccurrence })
        assertTrue(SessionTranslationHistory.lastSceneDialogueContexts().isEmpty())
        assertFalse(SessionTranslationHistory.completeDialogueSubtitleEntry(first, "重複", "zh-Hant"))
        SessionTranslationHistory.clear()
        assertFalse(SessionTranslationHistory.completeDialogueSubtitleEntry(first, "舊結果", "zh-Hant"))
    }

    @BeforeTest fun setUp() { SessionTranslationHistory.clear() }
    @AfterTest fun tearDown() { SessionTranslationHistory.clear() }

    @Test fun `later duplicate completing first uses first capture position among other LOG entries`() {
        val first = SessionTranslationHistory.reserveDialogueSubtitleEntry("summon:1", "なんだっけ？", "zh-Hant")
        SessionTranslationHistory.add(SessionTranslationEntry(dialogueText = "Story", sourceKey = "story"))
        val duplicate = SessionTranslationHistory.reserveDialogueSubtitleEntry("summon:2", "なんだっけ？", "zh-Hant")
        val next = SessionTranslationHistory.reserveDialogueSubtitleEntry("summon:3", "よろしく！", "zh-Hant")
        assertEquals(1, SessionTranslationHistory.entries.value.size) // Reservations aren't visible placeholders.
        assertTrue(SessionTranslationHistory.completeDialogueSubtitleEntry(next, "請多指教！", "zh-Hant"))
        assertTrue(SessionTranslationHistory.completeDialogueSubtitleEntry(duplicate, "是什麼來著？", "zh-Hant"))
        val logged = SessionTranslationHistory.entries.value
        assertFalse(SessionTranslationHistory.completeDialogueSubtitleEntry(first, "又是什麼來著？", "zh-Hant"))
        assertSame(logged, SessionTranslationHistory.entries.value)
        assertEquals(listOf("是什麼來著？", "Story", "請多指教！"), logged.map { it.dialogueText })
        assertEquals(first.order, logged.first().historyId)
        assertEquals(first.createdAt, logged.first().createdAt)
        assertEquals(first.sourceKey, logged.first().sourceKey)
    }

    @Test fun `summon failure upgrades to success at the same LOG identity and cannot downgrade`() {
        val first = SessionTranslationHistory.reserveDialogueSubtitleEntry("summon:1", "はじめまして。", "zh-Hant")
        assertTrue(SessionTranslationHistory.completeDialogueSubtitleEntry(first, "network timeout", "zh-Hant", isFailure = true))
        val failed = SessionTranslationHistory.entries.value.single()
        SessionTranslationHistory.add(SessionTranslationEntry(dialogueText = "Story", sourceKey = "story"))
        val recovered = SessionTranslationHistory.reserveDialogueSubtitleEntry("summon:2", "はじめまして。", "zh-Hant")
        assertTrue(SessionTranslationHistory.completeDialogueSubtitleEntry(recovered, "初次見面。", "zh-Hant"))
        val successful = SessionTranslationHistory.entries.value.first()
        assertEquals("初次見面。", successful.dialogueText)
        assertEquals(failed.historyId, successful.historyId)
        assertEquals(failed.createdAt, successful.createdAt)
        assertEquals(failed.sourceKey, successful.sourceKey)
        assertEquals(failed.originalDialogueText, successful.originalDialogueText)
        val anotherFailure = SessionTranslationHistory.reserveDialogueSubtitleEntry("summon:3", "はじめまして。", "zh-Hant")
        val anotherSuccess = SessionTranslationHistory.reserveDialogueSubtitleEntry("summon:4", "はじめまして。", "zh-Hant")
        val logged = SessionTranslationHistory.entries.value
        assertFalse(SessionTranslationHistory.completeDialogueSubtitleEntry(anotherFailure, "another error", "zh-Hant", isFailure = true))
        assertFalse(SessionTranslationHistory.completeDialogueSubtitleEntry(anotherSuccess, "另一個譯法。", "zh-Hant"))
        assertSame(logged, SessionTranslationHistory.entries.value)
    }

    @Test fun `repeated failures keep one row and a completed occurrence cannot complete again`() {
        val first = SessionTranslationHistory.reserveDialogueSubtitleEntry("summon:1", "同じ台詞。", "zh-Hans")
        val second = SessionTranslationHistory.reserveDialogueSubtitleEntry("summon:2", "同じ台詞。", "zh-Hans")
        assertTrue(SessionTranslationHistory.completeDialogueSubtitleEntry(first, "first error", "zh-Hans", isFailure = true))
        val logged = SessionTranslationHistory.entries.value
        assertFalse(SessionTranslationHistory.completeDialogueSubtitleEntry(second, "second error", "zh-Hans", isFailure = true))
        assertSame(logged, SessionTranslationHistory.entries.value)
        assertFalse(SessionTranslationHistory.completeDialogueSubtitleEntry(first, "duplicate callback", "zh-Hans"))
        assertFalse(SessionTranslationHistory.completeDialogueSubtitleEntry(second, "duplicate callback", "zh-Hans"))
        val third = SessionTranslationHistory.reserveDialogueSubtitleEntry("summon:3", "同じ台詞。", "zh-Hans")
        assertTrue(SessionTranslationHistory.completeDialogueSubtitleEntry(third, "正确译文。", "zh-Hans"))
        assertEquals(1, SessionTranslationHistory.entries.value.size)
    }

    @Test fun `summon duplicate comparison ignores formatting but preserves first raw Japanese`() {
        val original = "  初めまして、　\t マスター。 \r\n 私はククルカン。  "
        val first = SessionTranslationHistory.reserveDialogueSubtitleEntry("summon:1", original, "zh-Hant")
        val duplicate = SessionTranslationHistory.reserveDialogueSubtitleEntry("summon:2", "初めまして、 マスター。私はククルカン。", "zh-Hant")
        assertTrue(SessionTranslationHistory.completeDialogueSubtitleEntry(first, "初次見面，御主。我是庫庫爾坎。", "zh-Hant"))
        assertFalse(SessionTranslationHistory.completeDialogueSubtitleEntry(duplicate, "同一台詞的另一個譯法。", "zh-Hant"))
        assertEquals(original, SessionTranslationHistory.entries.value.single().originalDialogueText)
        val wrapped = SessionTranslationHistory.reserveDialogueSubtitleEntry("summon:3", "初めまして、\nマスター。", "zh-Hant")
        val unwrapped = SessionTranslationHistory.reserveDialogueSubtitleEntry("summon:4", "初めまして、マスター。", "zh-Hant")
        assertTrue(SessionTranslationHistory.completeDialogueSubtitleEntry(wrapped, "初次見面，御主。", "zh-Hant"))
        assertFalse(SessionTranslationHistory.completeDialogueSubtitleEntry(unwrapped, "你好，御主。", "zh-Hant"))
        assertEquals(2, SessionTranslationHistory.entries.value.size)
    }

    @Test fun `different Japanese is retained even when target text matches`() {
        val sources = listOf("はい。", "はい？", "全て遠き理想郷〈アヴァロン〉", "全て遠き理想郷",
            "全て遠き理想郷〈別の読み〉", "私は、私は……", "私は……", "Master", "master",
            "まだ", "まだ終わっていない。", "New York", "NewYork")
        sources.forEachIndexed { index, source ->
            val reservation = SessionTranslationHistory.reserveDialogueSubtitleEntry("summon:$index", source, "zh-Hant")
            assertTrue(SessionTranslationHistory.completeDialogueSubtitleEntry(reservation, "同一譯文", "zh-Hant"))
        }
        assertEquals(sources, SessionTranslationHistory.entries.value.map { it.originalDialogueText })
    }

    @Test fun `summon keys use normalized target locales and keep different languages separate`() {
        val reservations = listOf("zh-Hans", "zh-Hant", "en-US").mapIndexed { index, locale ->
            SessionTranslationHistory.reserveDialogueSubtitleEntry("summon:$index", "こんにちは。", locale)
        }
        reservations.zip(listOf("zh-Hans", "zh-Hant-TW", "en")).forEach { (reservation, locale) ->
            assertTrue(SessionTranslationHistory.completeDialogueSubtitleEntry(reservation, "Translation", locale))
        }
        val duplicate = SessionTranslationHistory.reserveDialogueSubtitleEntry("summon:duplicate", "こんにちは。", "zh-Hant-HK")
        assertFalse(SessionTranslationHistory.completeDialogueSubtitleEntry(duplicate, "另一个译文", "zh-Hant"))
        assertEquals(listOf("zh-Hans", "zh-Hant", "en"), SessionTranslationHistory.entries.value.map { it.targetLocale })
    }

    @Test fun `summon completion cannot contaminate another target or consume a Battle reservation`() {
        val summon = SessionTranslationHistory.reserveDialogueSubtitleEntry("summon:1", "台詞。", "zh-Hant")
        assertFalse(SessionTranslationHistory.completeDialogueSubtitleEntry(summon, "Wrong target", "en"))
        assertTrue(SessionTranslationHistory.entries.value.isEmpty())
        assertTrue(SessionTranslationHistory.completeDialogueSubtitleEntry(summon, "台詞。", "zh-Hant"))
        val battle = SessionTranslationHistory.reserveBattleEntry("battle:1", "戦闘。")
        assertFalse(SessionTranslationHistory.completeDialogueSubtitleEntry(battle, "Wrong workflow", "zh-Hant"))
        assertTrue(SessionTranslationHistory.completeBattleEntry(battle, "戰鬥。", "zh-Hant"))
        assertEquals(2, SessionTranslationHistory.entries.value.size)
    }

    @Test fun `summon blank completion and blank source do not create LOG rows`() {
        val summon = SessionTranslationHistory.reserveDialogueSubtitleEntry("summon:1", "はい。", "zh-Hans")
        assertFalse(SessionTranslationHistory.completeDialogueSubtitleEntry(summon, "  ", "zh-Hans"))
        assertTrue(SessionTranslationHistory.completeDialogueSubtitleEntry(summon, "好的。", "zh-Hans"))
        val blank = SessionTranslationHistory.reserveDialogueSubtitleEntry("summon:blank", " \n ", "zh-Hans")
        assertFalse(SessionTranslationHistory.completeDialogueSubtitleEntry(blank, "Unrelated", "zh-Hans"))
        assertEquals(1, SessionTranslationHistory.entries.value.size)
    }

    @Test fun `summon duplicates stay scoped to LOG session rather than Story context or mode resets`() {
        val first = SessionTranslationHistory.reserveDialogueSubtitleEntry("dialogue:1:1", "同じ台詞。", "zh-Hant")
        assertTrue(SessionTranslationHistory.completeDialogueSubtitleEntry(first, "同一台詞。", "zh-Hant"))
        SessionTranslationHistory.clearSceneDialogueContext()
        val repeated = SessionTranslationHistory.reserveDialogueSubtitleEntry("dialogue:2:1", "同じ台詞。", "zh-Hant")
        assertFalse(SessionTranslationHistory.completeDialogueSubtitleEntry(repeated, "同一台詞。", "zh-Hant"))
        val pending = SessionTranslationHistory.reserveDialogueSubtitleEntry("dialogue:2:2", "同じ台詞。", "zh-Hant")
        SessionTranslationHistory.clear()
        val fresh = SessionTranslationHistory.reserveDialogueSubtitleEntry("dialogue:3:1", "同じ台詞。", "zh-Hant")
        assertFalse(SessionTranslationHistory.completeDialogueSubtitleEntry(pending, "Old callback", "zh-Hant"))
        assertTrue(SessionTranslationHistory.completeDialogueSubtitleEntry(fresh, "新的同一台詞。", "zh-Hant"))
        assertEquals(listOf("新的同一台詞。"), SessionTranslationHistory.entries.value.map { it.dialogueText })
    }

    @Test fun `Story and Battle records do not share the summon duplicate index`() {
        val original = "同じ台詞。"
        val summon = SessionTranslationHistory.reserveDialogueSubtitleEntry("summon:1", original, "zh-Hans")
        assertTrue(SessionTranslationHistory.completeDialogueSubtitleEntry(summon, "同一台词。", "zh-Hans"))
        SessionTranslationHistory.add(SessionTranslationEntry(dialogueText = "同一台词。", originalDialogueText = original, sourceKey = "story:1"))
        repeat(2) { index ->
            val battle = SessionTranslationHistory.reserveBattleEntry("battle:$index", original)
            assertTrue(SessionTranslationHistory.completeBattleEntry(battle, "同一台词。", "zh-Hans"))
        }
        val duplicate = SessionTranslationHistory.reserveDialogueSubtitleEntry("summon:2", original, "zh-Hans")
        assertFalse(SessionTranslationHistory.completeDialogueSubtitleEntry(duplicate, "同一台词。", "zh-Hans"))
        val entries = SessionTranslationHistory.entries.value
        assertEquals(4, entries.size)
        assertEquals(1, entries.count { it.dialogueSubtitleOccurrence })
        assertEquals(2, entries.count { it.battleOccurrence })
        assertEquals(1, entries.count { !it.subtitleOccurrence })
    }

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
