package com.fgogotran.battle

/** Main-thread state, independent of OCR, network completion order and overlay implementation. */
class BattleSubtitleDeliveryQueue<T : Any> {
    enum class State { WAITING, TRANSLATING, READY, FAILED }

    class Item<T : Any>(val event: BattleSubtitleEvent) {
        var state = State.WAITING
            internal set
        var result: T? = null
            internal set
        var sourceEndedAt: Long? = null
            internal set
        internal var visibleMs = 0L
        internal var visibleSince: Long? = null
    }

    private val items = ArrayDeque<Item<T>>()
    val size: Int get() = items.size
    val hasPending: Boolean get() = items.isNotEmpty()

    fun enqueue(event: BattleSubtitleEvent) {
        if (items.none { it.event.id == event.id }) items.addLast(Item(event))
    }

    fun nextTranslation(): BattleSubtitleEvent? {
        val item = items.firstOrNull { it.state == State.WAITING } ?: return null
        item.state = State.TRANSLATING
        return item.event
    }

    fun complete(id: Long, result: T): Boolean {
        val item = items.firstOrNull { it.event.id == id && it.state == State.TRANSLATING } ?: return false
        item.result = result
        item.state = State.READY
        return true
    }

    fun fail(id: Long) {
        items.firstOrNull { it.event.id == id && it.state == State.TRANSLATING }?.state = State.FAILED
    }

    fun endSource(id: Long, at: Long) {
        items.firstOrNull { it.event.id == id }?.let {
            if (it.sourceEndedAt == null) it.sourceEndedAt = at
        }
    }

    fun endAllSources(at: Long) {
        items.forEach { if (it.sourceEndedAt == null) it.sourceEndedAt = at }
    }

    /** Normal and late/queued captions each retain one second of actual visibility. */
    fun candidate(now: Long): Item<T>? {
        while (items.isNotEmpty()) {
            val head = items.first()
            if (head.state == State.FAILED) { items.removeFirst(); continue }
            if (head.state != State.READY) return null // Preserve Japanese occurrence order.
            val visible = head.visibleMs + (head.visibleSince?.let { (now - it).coerceAtLeast(0) } ?: 0)
            val nextReady = items.asSequence().drop(1).firstOrNull { it.state != State.FAILED }?.state == State.READY
            val tailEnded = head.sourceEndedAt?.let { now >= it + SOURCE_TAIL_MS } == true
            if (visible >= MIN_VISIBLE_MS && (nextReady || tailEnded)) {
                items.removeFirst()
                continue
            }
            return head
        }
        return null
    }

    /** Called after the overlay's first draw, not when the API result is queued. */
    fun markVisible(id: Long, now: Long): Boolean {
        val head = items.firstOrNull() ?: return false
        if (head.event.id != id || head.state != State.READY || head.visibleSince != null) return false
        head.visibleSince = now
        return true
    }

    fun pauseDisplay(now: Long) {
        items.firstOrNull()?.let { head ->
            head.visibleSince?.let { head.visibleMs += (now - it).coerceAtLeast(0) }
            head.visibleSince = null
        }
    }

    fun clear() { items.clear() }

    companion object {
        const val MIN_VISIBLE_MS = 1_000L
        const val SOURCE_TAIL_MS = 1_000L
    }
}
