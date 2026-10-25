package com.fgogotran.dialoguesubtitle

import kotlin.math.abs

data class DialogueSubtitleSourceRow(val id: Long, val text: String)
data class DialogueSubtitleVisibleRow(val id: Long, val slot: Int)
data class DialogueSubtitleEvent(
    val id: Long, val source: String, val startedAt: Long,
    val rows: List<DialogueSubtitleSourceRow> = listOf(DialogueSubtitleSourceRow(id, source))
)

/**
 * Tracks row occurrences through typewriter growth and lower-to-upper scrolling.
 * Only an actual OCR variant is kept; matching never repairs or invents Japanese.
 */
class DialogueSubtitleAssembler {
    private class Row(val id: Long, observation: DialogueSubtitleRow, val startedAt: Long) {
        var text = observation.text.trim()
        var top = observation.top
        var bottom = observation.bottom
        var confidence = observation.confidence
        var lastGrowthAt = startedAt
        var lastSeenAt = startedAt
        var reads = 1
        var committed = false
    }
    private var nextId = 0L
    private val pending = mutableListOf<Row>()
    private var visible = emptyList<Row>()
    private var absenceAt: Long? = null
    private var blankAt: Long? = null
    private var candidateWindow: List<DialogueSubtitleVisibleRow>? = null
    /** Null means observation is not yet confirmed; empty means confirmed blank/absent. */
    var visibleSnapshot: List<DialogueSubtitleVisibleRow>? = null
        private set
    var observedSnapshot: List<DialogueSubtitleVisibleRow> = emptyList()
        private set
    /** One-shot last source window at confirmed disappearance, before its identities are cleared. */
    var endingSnapshot: List<DialogueSubtitleVisibleRow>? = null
        private set
    val needsConfirmation: Boolean get() = pending.isNotEmpty() || absenceAt != null ||
        (blankAt != null && visibleSnapshot?.isEmpty() != true) || candidateWindow != null || visibleSnapshot == null
    var playbackEnded = false
        private set

    fun observe(rows: List<DialogueSubtitleRow>, panelPresent: Boolean, now: Long): List<DialogueSubtitleEvent> {
        playbackEnded = false
        endingSnapshot = null
        if (!panelPresent) {
            val absent = absenceAt
            if (absent == null) { absenceAt = now; return emptyList() }
            if (now - absent < ABSENCE_MS) return emptyList()
            val hadPlayback = visible.isNotEmpty() || pending.isNotEmpty()
            val ending = observedSnapshot.ifEmpty { visibleSnapshot.orEmpty() }.toList()
            val events = emitReady(now, boundary = true)
            clear()
            visibleSnapshot = emptyList()
            endingSnapshot = ending.takeIf { it.isNotEmpty() }
            playbackEnded = hadPlayback
            return events
        }
        absenceAt = null
        if (rows.isEmpty()) {
            if (blankAt == null) blankAt = now
            if (now - blankAt!! >= ABSENCE_MS) {
                endingSnapshot = observedSnapshot.ifEmpty { visibleSnapshot.orEmpty() }.toList()
                    .takeIf { it.isNotEmpty() }
                visible = emptyList()
                visibleSnapshot = emptyList()
                observedSnapshot = emptyList()
                candidateWindow = null
            }
            return emitReady(now)
        }
        blankAt = null
        pending.removeAll { it.reads < 2 && now - it.lastSeenAt > FRAGMENT_SETTLE_MS }
        val available = (visible + pending.filter { now - it.lastSeenAt <= FRAGMENT_SETTLE_MS }).distinct().toMutableList()
        val next = mutableListOf<Row>()
        for (observation in rows.sortedBy { it.top }) {
            val key = identity(observation.text)
            val match = available.filter { row ->
                val oldKey = identity(row.text)
                oldKey == key || (!row.committed && minOf(oldKey.length, key.length) >= 1 &&
                    (minOf(oldKey.length, key.length) >= 2 || abs(row.top - observation.top) <= 10f) &&
                    (oldKey.startsWith(key) || key.startsWith(oldKey)))
            }.maxByOrNull { row ->
                // A carried lower row is consumed once. Identical text newly below it is a new occurrence.
                (if (identity(row.text) == key) 1_000f else 0f) - abs(row.top - observation.top)
            }
            val row = if (match == null) {
                Row(++nextId, observation, now).also { pending += it }
            } else {
                available.remove(match)
                if (key.length > identity(match.text).length) {
                    match.text = observation.text.trim()
                    match.lastGrowthAt = now
                    match.reads = 1
                } else {
                    match.reads++
                    // A clipped/shorter prefix must never replace the full earlier observation.
                    if (key == identity(match.text) && observation.text.length > match.text.length) {
                        match.text = observation.text.trim()
                    }
                }
                match.top = observation.top
                match.bottom = observation.bottom
                match.confidence = maxOf(match.confidence, observation.confidence)
                match.lastSeenAt = now
                match
            }
            next += row
        }
        visible = next
        val window = next.mapIndexed { index, row ->
            val slot = if (next.size == 2) index else
                if ((row.top + row.bottom) / 2 < DialogueSubtitleLayout.text.height / 2f) 0 else 1
            DialogueSubtitleVisibleRow(row.id, slot)
        }
        observedSnapshot = window
        // Confirm row membership independently of typewriter growth/translation readiness.
        if (window == visibleSnapshot || window == candidateWindow) {
            visibleSnapshot = window
            candidateWindow = null
        } else candidateWindow = window
        return emitReady(now)
    }

    private fun emitReady(now: Long, boundary: Boolean = false): List<DialogueSubtitleEvent> {
        val output = mutableListOf<DialogueSubtitleEvent>()
        while (pending.isNotEmpty()) {
            val rows = mutableListOf<Row>()
            for (row in pending) {
                rows += row
                if (terminal(row.text)) break
            }
            val last = rows.last()
            // A full two-row sentence can be visible for only one OCR pass. At a confirmed
            // boundary, judge that complete observation together, not just its terminal row.
            val strongBoundaryChunk = boundary && terminal(last.text) && rows.all {
                it.confidence >= 0.9f && it.lastSeenAt == last.lastSeenAt
            }
            val confirmed = rows.all { it.reads >= 2 } || strongBoundaryChunk
            val settled = now - last.lastGrowthAt >= STABLE_MS
            val closed = terminal(last.text) || now - last.lastGrowthAt >= FRAGMENT_SETTLE_MS
            if (!confirmed || (!boundary && (!settled || !closed))) {
                if (boundary) {
                    // An uncertain trailing row must not erase an already confirmed leading fragment.
                    val prefix = rows.takeWhile { it.reads >= 2 }
                    if (prefix.isNotEmpty()) {
                        output += event(prefix)
                        prefix.forEach { it.committed = true }
                        pending.subList(0, prefix.size).clear()
                    } else pending.removeAt(0) // Weak one-frame noise is not a captured utterance.
                    continue
                }
                break
            }
            output += event(rows)
            rows.forEach { it.committed = true }
            pending.subList(0, rows.size).clear()
        }
        return output
    }

    /** Failed captures are unknown, not proof that playback ended. */
    fun observationUnavailable() { absenceAt = null; blankAt = null; candidateWindow = null }

    /** Keep occurrence identities across a pause, but don't redisplay without fresh observations. */
    fun requireFreshSnapshot() { observationUnavailable(); visibleSnapshot = null; endingSnapshot = null }

    private fun event(rows: List<Row>) = DialogueSubtitleEvent(
        rows.first().id, rows.joinToString("") { it.text }, rows.first().startedAt,
        rows.map { DialogueSubtitleSourceRow(it.id, it.text) }
    )

    /** An explicit observation gap must not join a later unrelated row onto an old fragment. */
    fun suspendContinuations(now: Long): List<DialogueSubtitleEvent> = emitReady(now, boundary = true)

    /** Don't reset IDs: an old asynchronous callback must never identify a new occurrence. */
    fun clear() {
        pending.clear(); visible = emptyList(); absenceAt = null; blankAt = null
        candidateWindow = null; visibleSnapshot = null; observedSnapshot = emptyList()
        endingSnapshot = null
        playbackEnded = false
    }

    companion object {
        const val STABLE_MS = 600L
        const val FRAGMENT_SETTLE_MS = 1_800L
        const val ABSENCE_MS = 600L
        private fun identity(text: String) = text.filterNot { it.isWhitespace() || it == '　' }
        private fun terminal(text: String): Boolean = text.trimEnd(' ', '　', '」', '』', '”', '\"', '）', ')')
            .lastOrNull() in listOf('。', '！', '？', '!', '?', '…', '‥', '♡', '♥', '♪')
    }
}
