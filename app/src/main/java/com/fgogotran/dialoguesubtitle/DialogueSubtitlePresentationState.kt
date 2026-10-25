package com.fgogotran.dialoguesubtitle

data class DialogueSubtitleCaptionRow<T>(
    val id: Long, val slot: Int, val eventId: Long, val text: String, val value: T
)

/** Current source rows select the display; API completion order does not. No bitmap retention. */
class DialogueSubtitlePresentationState<T : Any> {
    private data class Translation<T>(val eventId: Long, val text: String, val value: T)
    private var source: List<DialogueSubtitleVisibleRow>? = null
    private var retainedIds = emptySet<Long>()
    private val translations = mutableMapOf<Long, Translation<T>>()
    private var drawn = emptyList<DialogueSubtitleCaptionRow<T>>()
    private var tail = emptyList<DialogueSubtitleCaptionRow<T>>()
    private var tailUntil: Long? = null

    fun observe(rows: List<DialogueSubtitleVisibleRow>?, now: Long,
                observed: List<DialogueSubtitleVisibleRow> = rows.orEmpty()) {
        // A fast/cache result can precede confirmation of its row's new screen position.
        // Retain only the confirmed window plus the latest observed window (at most four IDs).
        if (rows != null || observed.isNotEmpty()) {
            retainedIds = (rows.orEmpty() + observed).map { it.id }.toSet()
            translations.keys.retainAll(retainedIds)
        }
        if (rows == null || rows == source) return
        require(rows.size <= 2 && rows.map { it.id }.distinct().size == rows.size &&
            rows.map { it.slot }.distinct().size == rows.size && rows.all { it.slot in 0..1 })
        if (rows.isEmpty()) {
            // Only captions actually drawn before disappearance receive a tail. Late API results
            // go to LOG, and repeated blank observations cannot extend the deadline.
            tail = drawn
            tailUntil = if (tail.isEmpty()) null else now + TAIL_MS
            retainedIds = emptySet()
            translations.clear()
        } else {
            tail = emptyList(); tailUntil = null
        }
        source = rows.toList()
        drawn = emptyList()
    }

    fun complete(event: DialogueSubtitleEvent, texts: List<String>, value: T) {
        require(texts.size == event.rows.size && texts.all { it.isNotBlank() })
        event.rows.zip(texts).forEach { (row, text) ->
            if (row.id in retainedIds) translations[row.id] = Translation(event.id, text, value)
        }
    }

    fun candidate(now: Long): List<DialogueSubtitleCaptionRow<T>> {
        val rows = source ?: return emptyList()
        if (rows.isEmpty()) return if (tailUntil?.let { now < it } == true) tail else {
            tail = emptyList(); tailUntil = null; emptyList()
        }
        return rows.mapNotNull { row -> translations[row.id]?.let {
            DialogueSubtitleCaptionRow(row.id, row.slot, it.eventId, it.text, it.value)
        } }
    }

    fun markDrawn(rows: List<DialogueSubtitleCaptionRow<T>>, now: Long): Boolean {
        if (rows.isEmpty() || rows != candidate(now)) return false
        if (source?.isNotEmpty() == true) drawn = rows.toList()
        return true
    }

    /** Results may finish for still-retained IDs, but resume requires a new source snapshot. */
    fun pause() { source = null; drawn = emptyList(); tail = emptyList(); tailUntil = null }

    fun clear() { pause(); retainedIds = emptySet(); translations.clear() }

    companion object { const val TAIL_MS = 2_000L }
}
