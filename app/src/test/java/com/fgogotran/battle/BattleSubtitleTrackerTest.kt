package com.fgogotran.battle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BattleSubtitleTrackerTest {
    private val source = "「じゃじゃーん、驚いた？」"

    @Test fun `requires two agreeing reads and deduplicates the same occurrence`() {
        val tracker = BattleSubtitleTracker()
        assertNull(tracker.observe(source, 0))
        assertNull(tracker.observe(source, 30))
        val event = assertNotNull(tracker.observe(source, 120))
        assertNull(tracker.observe(source, 240))
        assertEquals(event, tracker.current)
        assertNull(tracker.ended)
    }

    @Test fun `replacement ends the old source at its first differing frame`() {
        val tracker = BattleSubtitleTracker()
        tracker.observe(source, 0)
        val old = assertNotNull(tracker.observe(source, 120))
        assertNull(tracker.observe("「まずは小手調べ♪」", 240))
        assertEquals(old, tracker.current)
        assertNull(tracker.ended)
        val next = assertNotNull(tracker.observe("「まずは小手調べ♪」", 360))
        assertEquals(BattleSubtitleEnd(old.id, 240), tracker.ended)
        assertTrue(next.id > old.id)
        tracker.observe(next.source, 480)
        assertNull(tracker.ended)
    }

    @Test fun `confirmed blank ends source without implying translation should be discarded`() {
        val tracker = BattleSubtitleTracker()
        tracker.observe(source, 0)
        val event = assertNotNull(tracker.observe(source, 120))
        tracker.observe(null, 240)
        tracker.observe(null, 300)
        assertEquals(event, tracker.current)
        tracker.observe(null, 500)
        assertNull(tracker.current)
        assertEquals(BattleSubtitleEnd(event.id, 240), tracker.ended)
        tracker.observe(source, 600)
        val repeated = assertNotNull(tracker.observe(source, 720))
        assertTrue(repeated.id > event.id)
    }

    @Test fun `unavailable OCR cancels absence but preserves pending confirmation evidence`() {
        val tracker = BattleSubtitleTracker()
        tracker.observe(source, 0)
        val event = assertNotNull(tracker.observe(source, 120))
        tracker.observe(null, 240)
        tracker.observationUnavailable()
        tracker.observe(null, 1_000)
        assertEquals(event, tracker.current)
        assertNull(tracker.ended)
        tracker.observe("wrong OCR", 1_100)
        tracker.observationUnavailable()
        val replacement = assertNotNull(tracker.observe("wrong OCR", 1_300))
        assertEquals(BattleSubtitleEnd(event.id, 1_100), tracker.ended)
        assertEquals(replacement, tracker.current)
    }

    @Test fun `clear invalidates detection without reusing IDs`() {
        val tracker = BattleSubtitleTracker()
        tracker.observe(source, 0)
        val old = assertNotNull(tracker.observe(source, 120))
        tracker.clear()
        assertNull(tracker.current)
        tracker.observe(source, 240)
        val next = assertNotNull(tracker.observe(source, 360))
        assertTrue(next.id > old.id)
    }

    @Test fun `wrapping differences do not create new events`() {
        val tracker = BattleSubtitleTracker()
        tracker.observe("「こんにちは。\n元気？」", 0)
        val event = assertNotNull(tracker.observe("「こんにちは。 元気？」", 120))
        assertNull(tracker.observe("「こんにちは。元気？」", 240))
        assertEquals(event, tracker.current)
    }

    @Test fun `punctuation differences confirm one occurrence while preserving observed source`() {
        val tracker = BattleSubtitleTracker()
        tracker.observe("「フォウ」", 0)
        val event = assertNotNull(tracker.observe("フォウ？？", 120))
        assertEquals("フォウ？？", event.source)
        assertNull(tracker.observe("『フォウ！』", 240))
        assertEquals(event, tracker.current)
        assertNull(tracker.ended)
    }

    @Test fun `volatile battle punctuation does not prevent confirmation`() {
        val tracker = BattleSubtitleTracker()
        tracker.observe("「くそ……くそ………ちくしょう……!", 0)
        val event = assertNotNull(tracker.observe("「くそ……くそ……ちくしょう……!」", 120))
        assertEquals("「くそ……くそ……ちくしょう……!」", event.source)
    }

    @Test fun `one-character Japanese OCR variation confirms one occurrence`() {
        val tracker = BattleSubtitleTracker()
        tracker.observe("「くそ……くそ……ちくしょう……!」", 0)
        val event = assertNotNull(tracker.observe("「くそ……くそ……ちくしよう……!」", 120))
        assertEquals("「くそ……くそ……ちくしょう……!」", event.source)
        assertNull(tracker.observe("「くそ……くそ……ちくしよう……!」", 240))
        assertEquals(event, tracker.current)
    }

    @Test fun `punctuation-only reaction can be confirmed without quotes`() {
        val tracker = BattleSubtitleTracker()
        tracker.observe("『……！！？？』", 0)
        val event = assertNotNull(tracker.observe("……!!??", 120))
        assertEquals("『……！！？？』", event.source)
    }

    @Test fun `partial typewriter-style OCR is replaced by the completed candidate`() {
        val tracker = BattleSubtitleTracker()
        tracker.observe("「やっと来た！", 0)
        val event = assertNotNull(tracker.observe("「やっと来た！ さあ、ブッ殺しの時間だよ！」", 120))
        assertEquals("「やっと来た！ さあ、ブッ殺しの時間だよ！」", event.source)
    }

    @Test fun `stale pending observation cannot confirm a later occurrence`() {
        val tracker = BattleSubtitleTracker()
        tracker.observe(source, 0)
        assertNull(tracker.observe(source, BattleSubtitleTracker.MAX_CONFIRM_GAP_MS + 1))
        assertNull(tracker.current)
        assertNotNull(tracker.observe(source, BattleSubtitleTracker.MAX_CONFIRM_GAP_MS + 121))
    }

    @Test fun `strong single subtitle is finalized when its disappearance is confirmed`() {
        val tracker = BattleSubtitleTracker()
        val candidate = BattleSubtitleCandidate("「一度だけ見えた台詞。」", 0.92f, 1, 0.35f)
        tracker.observeCandidate(candidate, 0)
        tracker.observe(null, 100)
        val event = assertNotNull(tracker.observe(null, 400))
        assertEquals(candidate.text, event.source)
        assertNull(tracker.current)
        assertEquals(listOf(BattleSubtitleEnd(event.id, 100)), tracker.endedEvents)
    }

    @Test fun `short one-frame OCR debris is not finalized on disappearance`() {
        val tracker = BattleSubtitleTracker()
        tracker.observeCandidate(BattleSubtitleCandidate("回", 0.99f, 1, 0.10f), 0)
        tracker.observe(null, 100)
        assertNull(tracker.observe(null, 400))
        assertNull(tracker.current)
        assertFalse(tracker.hasPending)
    }

    @Test fun `confirmed scene boundary finalizes a strong pending subtitle`() {
        val tracker = BattleSubtitleTracker()
        val candidate = BattleSubtitleCandidate("「最後の戦闘台詞！」", 0.90f, 1, 0.30f)
        tracker.observeCandidate(candidate, 100)
        val event = assertNotNull(tracker.finalizePendingAtBoundary(500))
        assertEquals(candidate.text, event.source)
        assertNull(tracker.current)
        assertEquals(BattleSubtitleEnd(event.id, 500), tracker.ended)
    }

    @Test fun `confirmed scene boundary rejects weak pending debris`() {
        val tracker = BattleSubtitleTracker()
        tracker.observeCandidate(BattleSubtitleCandidate("回", 0.99f, 1, 0.10f), 100)
        assertNull(tracker.finalizePendingAtBoundary(500))
        assertFalse(tracker.hasPending)
    }

    @Test fun `stale single candidate is not revived by a much later blank interval`() {
        val tracker = BattleSubtitleTracker()
        tracker.observeCandidate(BattleSubtitleCandidate("「古い候補は使わない。」", 0.95f, 1, 0.30f), 0)
        tracker.observe(null, BattleSubtitleTracker.MAX_CONFIRM_GAP_MS + 100)
        assertNull(tracker.observe(null, BattleSubtitleTracker.MAX_CONFIRM_GAP_MS + 500))
        assertFalse(tracker.hasPending)
    }
}
