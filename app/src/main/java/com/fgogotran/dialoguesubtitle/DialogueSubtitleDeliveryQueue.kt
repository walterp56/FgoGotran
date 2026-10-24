package com.fgogotran.dialoguesubtitle

/** Captured strings only, never bitmaps. Network completion cannot reorder occurrences. */
class DialogueSubtitleDeliveryQueue<T : Any> {
    enum class State { WAITING, TRANSLATING, READY, FAILED }
    class Item<T : Any>(val event: DialogueSubtitleEvent) {
        var state = State.WAITING
        var result: T? = null
        var readingMs = 1_500L
        var visibleMs = 0L
        var visibleSince: Long? = null
    }
    private val items = ArrayDeque<Item<T>>()
    val size get() = items.size
    fun enqueue(event: DialogueSubtitleEvent) { items.addLast(Item(event)) }
    fun nextTranslation(): DialogueSubtitleEvent? = items.firstOrNull { it.state == State.WAITING }?.also {
        it.state = State.TRANSLATING
    }?.event
    fun complete(id: Long, result: T, translatedText: String): Boolean {
        val item = items.firstOrNull { it.event.id == id && it.state == State.TRANSLATING } ?: return false
        item.result = result
        item.readingMs = readingTimeMs(translatedText)
        item.state = State.READY
        return true
    }
    fun fail(id: Long) { items.firstOrNull { it.event.id == id }?.state = State.FAILED }
    fun candidate(now: Long): Item<T>? {
        while (items.isNotEmpty()) {
            val head = items.first()
            if (head.state == State.FAILED) { items.removeFirst(); continue }
            if (head.state != State.READY) return null
            val elapsed = head.visibleMs + (head.visibleSince?.let { (now - it).coerceAtLeast(0) } ?: 0)
            if (head.visibleSince != null && elapsed >= head.readingMs) { items.removeFirst(); continue }
            return head
        }
        return null
    }
    fun markVisible(id: Long, now: Long): Boolean {
        val head = items.firstOrNull() ?: return false
        if (head.event.id != id || head.state != State.READY || head.visibleSince != null) return false
        head.visibleSince = now
        return true
    }
    fun pauseDisplay(now: Long) {
        items.firstOrNull()?.let { item ->
            item.visibleSince?.let { item.visibleMs += (now - it).coerceAtLeast(0) }
            item.visibleSince = null
        }
    }
    fun clear() { items.clear() }

    companion object {
        /** Independent starting policy: ~12 CJK characters/s or 3.5 Latin words/s, with a reading floor. */
        fun readingTimeMs(text: String): Long {
            val cjk = text.count { it.code in 0x3400..0x9fff || it.code in 0x3040..0x30ff }
            val words = Regex("[A-Za-z0-9]+(?:['’][A-Za-z]+)*").findAll(text).count()
            return (400L + cjk * 1_000L / 12 + words * 1_000L / 3.5).toLong().coerceAtLeast(1_500L)
        }
    }
}
