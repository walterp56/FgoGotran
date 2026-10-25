package com.fgogotran.dialoguesubtitle

/** Pending translation work only. Screen membership never blocks queue completion. */
class DialogueSubtitleDeliveryQueue {
    private class Item(val event: DialogueSubtitleEvent, var started: Boolean = false)
    private val items = ArrayDeque<Item>()
    val size get() = items.size
    fun enqueue(event: DialogueSubtitleEvent) { items.addLast(Item(event)) }
    fun nextTranslation(): DialogueSubtitleEvent? = items.firstOrNull { !it.started }?.also {
        it.started = true
    }?.event
    fun complete(id: Long): Boolean {
        val item = items.firstOrNull { it.event.id == id && it.started } ?: return false
        return items.remove(item)
    }
    fun clear() { items.clear() }
}
