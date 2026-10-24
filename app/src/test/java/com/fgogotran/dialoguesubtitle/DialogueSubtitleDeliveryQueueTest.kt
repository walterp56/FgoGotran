package com.fgogotran.dialoguesubtitle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DialogueSubtitleDeliveryQueueTest {
    @Test fun `out of order completion waits for first occurrence and actual first draw`() {
        val queue = DialogueSubtitleDeliveryQueue<String>()
        queue.enqueue(DialogueSubtitleEvent(1, "A", 0))
        queue.enqueue(DialogueSubtitleEvent(2, "B", 100))
        queue.nextTranslation(); queue.nextTranslation()
        queue.complete(2, "B", "B")
        assertNull(queue.candidate(10_000))
        queue.complete(1, "A", "A")
        assertEquals(1L, queue.candidate(20_000)?.event?.id)
        queue.markVisible(1, 20_000)
        assertEquals(1L, queue.candidate(21_499)?.event?.id)
        assertEquals(2L, queue.candidate(21_500)?.event?.id)
    }

    @Test fun `pausing freezes reading time and failed occurrence cannot block following lines`() {
        val queue = DialogueSubtitleDeliveryQueue<String>()
        queue.enqueue(DialogueSubtitleEvent(1, "A", 0)); queue.nextTranslation()
        queue.complete(1, "A", "A"); queue.markVisible(1, 0)
        queue.pauseDisplay(500)
        assertEquals(1L, queue.candidate(50_000)?.event?.id)
        queue.markVisible(1, 50_000)
        assertNull(queue.candidate(51_000))
        queue.enqueue(DialogueSubtitleEvent(2, "B", 0)); queue.nextTranslation(); queue.fail(2)
        queue.enqueue(DialogueSubtitleEvent(3, "C", 0)); queue.nextTranslation(); queue.complete(3, "C", "C")
        assertEquals(3L, queue.candidate(51_100)?.event?.id)
    }

    @Test fun `long English and Chinese captions get more than the reading floor`() {
        assertEquals(1_500L, DialogueSubtitleDeliveryQueue.readingTimeMs("你好！"))
        assertTrue(DialogueSubtitleDeliveryQueue.readingTimeMs("This is a much longer conversation with many important words to read.") > 3_000)
        assertTrue(DialogueSubtitleDeliveryQueue.readingTimeMs("這是一段較長的角色台詞，需要保留所有意思並提供足夠的閱讀時間。") > 2_000)
    }
}
