package com.fgogotran.battle

import com.fgogotran.translation.TranslateResult
import kotlin.test.*

class BattleSubtitleDeliveryQueueTest {
    private fun event(id: Long) = BattleSubtitleEvent(id, "字幕" + id, 0)
    private fun ready(queue: BattleSubtitleDeliveryQueue<String>, id: Long) {
        queue.enqueue(event(id))
        assertEquals(id, queue.nextTranslation()?.id)
        assertTrue(queue.complete(id, "译文" + id))
    }

    @Test fun `first nonempty model response displays even when unsafe for context`() {
        val result = TranslateResult(
            translatedText = "第一回译文",
            backend = "deepseek",
            cached = false,
            trustedForContext = false
        )

        assertTrue(result.isDisplayableBattleResponse())
    }

    @Test fun `empty unavailable and failed responses never display`() {
        assertFalse(TranslateResult("", "deepseek", false).isDisplayableBattleResponse())
        assertFalse(
            TranslateResult("未配置", "none", false, trustedForContext = false)
                .isDisplayableBattleResponse()
        )
        assertFalse(
            TranslateResult(
                translatedText = "[翻译失败]",
                backend = "deepseek",
                cached = false,
                trustedForContext = false,
                isFailure = true
            ).isDisplayableBattleResponse()
        )
    }

    @Test fun `normal subtitle ends one second after source ends`() {
        val queue = BattleSubtitleDeliveryQueue<String>()
        ready(queue, 1)
        queue.markVisible(1, 1_000)
        queue.endSource(1, 4_000)
        assertEquals(1L, queue.candidate(4_999)?.event?.id)
        assertNull(queue.candidate(5_000))
    }

    @Test fun `late reply after entire source window still receives one visible second`() {
        val queue = BattleSubtitleDeliveryQueue<String>()
        queue.enqueue(event(1))
        queue.nextTranslation()
        queue.endSource(1, 2_000)
        assertNull(queue.candidate(4_000))
        queue.complete(1, "迟到的译文")
        assertEquals(1L, queue.candidate(4_500)?.event?.id)
        queue.markVisible(1, 4_500)
        assertEquals(1L, queue.candidate(5_499)?.event?.id)
        assertNull(queue.candidate(5_500))
    }

    @Test fun `B completes before A but never displays ahead of A`() {
        val queue = BattleSubtitleDeliveryQueue<String>()
        queue.enqueue(event(1)); queue.enqueue(event(2))
        queue.nextTranslation(); queue.nextTranslation()
        queue.endSource(1, 3_000)
        queue.complete(2, "B")
        assertNull(queue.candidate(4_200))
        queue.complete(1, "A")
        assertEquals(1L, queue.candidate(4_800)?.event?.id)
        queue.markVisible(1, 4_800)
        assertEquals(1L, queue.candidate(5_799)?.event?.id)
        assertEquals(2L, queue.candidate(5_800)?.event?.id)
    }

    @Test fun `new ready caption replaces old before tail deadline once minimum reading time is met`() {
        val queue = BattleSubtitleDeliveryQueue<String>()
        ready(queue, 1)
        queue.markVisible(1, 1_000)
        queue.endSource(1, 4_000)
        ready(queue, 2)
        assertEquals(1L, queue.candidate(1_999)?.event?.id)
        assertEquals(2L, queue.candidate(2_000)?.event?.id)
    }

    @Test fun `new caption cannot overwrite an unread queued caption`() {
        val queue = BattleSubtitleDeliveryQueue<String>()
        ready(queue, 1); ready(queue, 2); ready(queue, 3)
        queue.endAllSources(100)
        assertEquals(1L, queue.candidate(10_000)?.event?.id)
        queue.markVisible(1, 10_000)
        assertEquals(2L, queue.candidate(12_000)?.event?.id)
        assertEquals(2L, queue.candidate(30_000)?.event?.id) // No draw acknowledgement yet.
        queue.markVisible(2, 30_000)
        assertEquals(3L, queue.candidate(32_000)?.event?.id)
    }

    @Test fun `pausing LOG freezes minimum visible time`() {
        val queue = BattleSubtitleDeliveryQueue<String>()
        ready(queue, 1)
        queue.endSource(1, 0)
        queue.markVisible(1, 1_000)
        queue.pauseDisplay(1_500)
        assertEquals(1L, queue.candidate(50_000)?.event?.id)
        queue.markVisible(1, 50_000)
        assertEquals(1L, queue.candidate(50_499)?.event?.id)
        assertNull(queue.candidate(50_500))
    }

    @Test fun `failed request releases later successful captions without retrying it`() {
        val queue = BattleSubtitleDeliveryQueue<String>()
        queue.enqueue(event(1)); queue.enqueue(event(2))
        queue.nextTranslation(); queue.nextTranslation()
        queue.complete(2, "B")
        queue.fail(1)
        assertEquals(2L, queue.candidate(8_000)?.event?.id)
        assertNull(queue.nextTranslation())
        assertFalse(queue.complete(1, "late A"))
    }

    @Test fun `battle exit retains translations still in flight`() {
        val queue = BattleSubtitleDeliveryQueue<String>()
        queue.enqueue(event(1)); queue.nextTranslation()
        queue.endAllSources(1_000)
        queue.complete(1, "战斗结束后完成")
        assertEquals(1L, queue.candidate(9_000)?.event?.id)
        queue.markVisible(1, 9_000)
        assertNull(queue.candidate(10_000))
    }

    @Test fun `service reset rejects old replies and clears all queued state`() {
        val queue = BattleSubtitleDeliveryQueue<String>()
        queue.enqueue(event(1)); queue.nextTranslation()
        queue.clear()
        assertFalse(queue.hasPending)
        assertFalse(queue.complete(1, "old reply"))
        ready(queue, 2)
        assertEquals(2L, queue.candidate(10)?.event?.id)
    }

    @Test fun `repeat draw callbacks do not reset reading time`() {
        val queue = BattleSubtitleDeliveryQueue<String>()
        ready(queue, 1)
        queue.endSource(1, 0)
        assertTrue(queue.markVisible(1, 1_000))
        assertFalse(queue.markVisible(1, 1_900))
        assertNull(queue.candidate(2_000))
    }

    @Test fun `same text in distinct occurrences is delivered separately`() {
        val queue = BattleSubtitleDeliveryQueue<String>()
        queue.enqueue(BattleSubtitleEvent(1, "「フォウ？」", 0))
        queue.enqueue(BattleSubtitleEvent(2, "「フォウ？」", 3_000))
        queue.nextTranslation(); queue.nextTranslation()
        queue.complete(1, "「芙？」"); queue.complete(2, "「芙？」")
        queue.markVisible(1, 1_000)
        assertEquals(2L, queue.candidate(2_000)?.event?.id)
    }

    @Test fun `no queue count cap silently drops successful subtitles`() {
        val queue = BattleSubtitleDeliveryQueue<String>()
        for (id in 1L..250L) ready(queue, id)
        queue.endAllSources(0)
        for (id in 1L..250L) {
            val now = id * 1_000
            assertEquals(id, queue.candidate(now)?.event?.id)
            queue.markVisible(id, now)
        }
        assertNull(queue.candidate(251_000))
    }

    @Test fun `logged short subtitle is displayed even when the following two OCR frames are blank`() {
        val tracker = BattleSubtitleTracker()
        val queue = BattleSubtitleDeliveryQueue<String>()
        val source = "「まずは小手調べ♪」"
        tracker.observe(source, 55_271)
        val event = assertNotNull(tracker.observe(source, 56_004))
        queue.enqueue(event)
        assertEquals(event, queue.nextTranslation())
        tracker.observe(source, 56_713)
        // Log: translation ready 21:15:57.278; older implementation waited and discarded at 58.045.
        queue.complete(event.id, "先小试一下♪")
        assertEquals(event, queue.candidate(57_278)?.event)
        queue.markVisible(event.id, 57_300)
        tracker.observe(null, 57_278)
        tracker.observe(null, 57_855)
        val end = assertNotNull(tracker.ended)
        queue.endSource(end.id, end.at)
        assertNull(tracker.current)
        assertEquals(event, queue.candidate(58_045)?.event)
        assertEquals(event, queue.candidate(58_299)?.event)
        assertNull(queue.candidate(58_300))
    }
}
