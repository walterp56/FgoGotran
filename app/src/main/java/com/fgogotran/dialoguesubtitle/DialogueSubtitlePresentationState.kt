package com.fgogotran.dialoguesubtitle

data class DialogueSubtitleCaptionRow<T>(
    val id: Long, val slot: Int, val eventId: Long, val text: String, val value: T
)

/** Current source rows select the display; API completion order does not. No bitmap retention. */
class DialogueSubtitlePresentationState<T : Any> {
    private data class Translation<T>(val eventId: Long, val text: String, val value: T)
    private class Ending(val rows: List<DialogueSubtitleVisibleRow>, now: Long, drawnIds: Set<Long>) {
        val waitUntil = now + FINAL_WAIT_MS
        val hardUntil = waitUntil + TAIL_MS
        var readUntil = now + TAIL_MS
        val drawnIds = drawnIds.toMutableSet()
    }
    private var source: List<DialogueSubtitleVisibleRow>? = null
    private var retainedIds = emptySet<Long>()
    private val translations = mutableMapOf<Long, Translation<T>>()
    private var drawn = emptyList<DialogueSubtitleCaptionRow<T>>()
    private var tail = emptyList<DialogueSubtitleCaptionRow<T>>()
    private var tailUntil: Long? = null
    private var ending: Ending? = null
    // At most two IDs prevent a repeated ending notification from restarting an expired window.
    private var lastEndingIds = emptySet<Long>()

    fun observe(rows: List<DialogueSubtitleVisibleRow>?, now: Long,
                observed: List<DialogueSubtitleVisibleRow> = rows.orEmpty(),
                endingRows: List<DialogueSubtitleVisibleRow>? = null) {
        require(endingRows == null || rows?.isEmpty() == true)
        if (!rows.isNullOrEmpty() || observed.isNotEmpty()) {
            if (ending != null) discardEnding()
            lastEndingIds = emptySet()
        } else if (ending != null) {
            // Repeated blank/unknown frames must not prune the frozen IDs or restart deadlines.
            candidate(now)
            return
        }
        if (!endingRows.isNullOrEmpty() && endingRows.map { it.id }.toSet() != lastEndingIds) {
            require(endingRows.size <= 2 && endingRows.map { it.id }.distinct().size == endingRows.size &&
                endingRows.map { it.slot }.distinct().size == endingRows.size && endingRows.all { it.slot in 0..1 })
            lastEndingIds = endingRows.map { it.id }.toSet()
            ending = Ending(endingRows.toList(), now, drawn.map { it.id }.toSet())
            retainedIds = lastEndingIds
            // Preserve ready results for the latest observed window before normal blank pruning.
            translations.keys.retainAll(retainedIds)
            tail = drawn
            tailUntil = if (tail.isEmpty()) null else now + TAIL_MS
            source = emptyList()
            drawn = emptyList()
            return
        }
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
            // Without an explicit captured ending window, only already drawn captions get a tail.
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

    fun complete(event: DialogueSubtitleEvent, texts: List<String>, value: T, now: Long) {
        require(texts.size == event.rows.size && texts.all { it.isNotBlank() })
        if (ending?.let { now >= it.waitUntil } == true) {
            candidate(now)
            return
        }
        event.rows.zip(texts).forEach { (row, text) ->
            if (row.id in retainedIds) translations[row.id] = Translation(event.id, text, value)
        }
    }

    fun candidate(now: Long): List<DialogueSubtitleCaptionRow<T>> {
        ending?.let { final ->
            if (now < final.hardUntil) {
                val ready = translated(final.rows)
                // A result accepted before the wait deadline can still receive its first draw.
                if (ready.any { it.id !in final.drawnIds }) return ready
                if (now < final.readUntil) {
                    if (ready.isNotEmpty()) return ready
                    if (tailUntil?.let { now < it } == true) return tail
                }
                // No caption may be ready yet; an empty draw must not cancel the result wait.
                if (now < final.waitUntil) return emptyList()
            }
            discardEnding()
            return emptyList()
        }
        val rows = source ?: return emptyList()
        if (rows.isEmpty()) return if (tailUntil?.let { now < it } == true) tail else {
            tail = emptyList(); tailUntil = null; emptyList()
        }
        return translated(rows)
    }

    private fun translated(rows: List<DialogueSubtitleVisibleRow>): List<DialogueSubtitleCaptionRow<T>> =
        rows.mapNotNull { row -> translations[row.id]?.let {
            DialogueSubtitleCaptionRow(row.id, row.slot, it.eventId, it.text, it.value)
        } }

    fun markDrawn(rows: List<DialogueSubtitleCaptionRow<T>>, now: Long): Boolean {
        if (rows.isEmpty() || rows != candidate(now)) return false
        val final = ending
        if (final != null) {
            var firstDraw = false
            for (row in rows) {
                if (row.id in retainedIds && final.drawnIds.add(row.id)) firstDraw = true
            }
            if (firstDraw) final.readUntil = minOf(now + TAIL_MS, final.hardUntil)
        } else if (source?.isNotEmpty() == true) drawn = rows.toList()
        return true
    }

    private fun discardEnding() {
        ending = null; retainedIds = emptySet(); translations.clear()
        drawn = emptyList(); tail = emptyList(); tailUntil = null
    }

    /** Results may finish for still-retained IDs, but resume requires a new source snapshot. */
    fun pause() {
        if (ending != null) discardEnding()
        source = null; drawn = emptyList(); tail = emptyList(); tailUntil = null
    }

    fun clear() { pause(); retainedIds = emptySet(); translations.clear(); lastEndingIds = emptySet() }

    companion object {
        const val TAIL_MS = 2_000L
        const val FINAL_WAIT_MS = 2_000L
    }
}
