package com.fgogotran.dialoguesubtitle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DialogueSubtitleDeliveryQueueTest {
    @Test fun `work starts in capture order but later completion frees capacity immediately`() {
        val queue = DialogueSubtitleDeliveryQueue()
        for (id in 1L..3L) queue.enqueue(DialogueSubtitleEvent(id, "source $id", id))
        assertEquals(1L, queue.nextTranslation()?.id)
        assertEquals(2L, queue.nextTranslation()?.id)
        assertTrue(queue.complete(2))
        assertEquals(2, queue.size)
        assertEquals(3L, queue.nextTranslation()?.id)
        assertNull(queue.nextTranslation())
        assertTrue(queue.complete(3))
        assertEquals(1, queue.size)
        assertTrue(queue.complete(1))
        assertEquals(0, queue.size)
    }

    @Test fun `unstarted duplicate and cancelled completions cannot consume another occurrence`() {
        val queue = DialogueSubtitleDeliveryQueue()
        queue.enqueue(DialogueSubtitleEvent(1, "same", 0))
        queue.enqueue(DialogueSubtitleEvent(2, "same", 1))
        assertFalse(queue.complete(1))
        queue.nextTranslation()
        assertTrue(queue.complete(1))
        assertFalse(queue.complete(1))
        assertEquals(2L, queue.nextTranslation()?.id)
        queue.clear()
        assertFalse(queue.complete(2))
        assertEquals(0, queue.size)
    }
}
