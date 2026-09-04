package com.fgogotran.battle

/** Pure state machines: neither network completion nor a transient HUD loss advances the game. */
class BattleSceneTracker {
    var inBattle = false
        private set
    private var enterCount = 0
    private var exitCount = 0
    val blocksStory: Boolean get() = inBattle || enterCount > 0

    fun observe(hudVisible: Boolean, exitVisible: Boolean = false): Boolean {
        if (exitVisible) {
            enterCount = 0
            if (++exitCount >= 2) inBattle = false
        } else {
            exitCount = 0
            if (hudVisible) {
                if (++enterCount >= 2) inBattle = true
            } else enterCount = 0
        }
        return inBattle
    }

    fun reset() { inBattle = false; enterCount = 0; exitCount = 0 }
}

data class BattleSubtitleEvent(val id: Long, val source: String, val startedAt: Long)
data class BattleSubtitleEnd(val id: Long, val at: Long)

class BattleSubtitleTracker {
    var current: BattleSubtitleEvent? = null
        private set
    private var nextId = 0L
    private var pending = ""
    private var pendingSince = 0L
    private var absentSince: Long? = null
    var ended: BattleSubtitleEnd? = null
        private set

    /** Two agreeing reads identify occurrences. Delivery survives both disappearance and replacement. */
    fun observe(source: String?, now: Long): BattleSubtitleEvent? {
        ended = null
        val text = source?.trim().orEmpty()
        if (text.isEmpty()) {
            pending = ""
            if (absentSince == null) absentSince = now
            if (now - absentSince!! >= ABSENCE_GRACE_MS) {
                ended = current?.let { BattleSubtitleEnd(it.id, absentSince!!) }
                current = null
            }
            return null
        }
        absentSince = null
        if (key(text) == key(current?.source.orEmpty())) {
            pending = ""
            return null
        }
        if (key(text) != key(pending)) {
            pending = text
            pendingSince = now
            return null
        }
        if (now - pendingSince < MIN_CONFIRM_MS) return null
        val event = BattleSubtitleEvent(++nextId, text, pendingSince)
        ended = current?.let { BattleSubtitleEnd(it.id, pendingSince) }
        current = event
        pending = ""
        return event
    }

    /** Capture/OCR failure is unknown, not evidence that the source disappeared. */
    fun observationUnavailable() {
        ended = null
        pending = ""
        absentSince = null
    }

    fun clear() {
        current = null; pending = ""; absentSince = null; ended = null
        // Do not reset IDs: late replies from a previous session must remain invalid.
    }

    private fun key(text: String) = text.filterNot { it.isWhitespace() || it == '　' }

    companion object {
        const val MIN_CONFIRM_MS = 70L
        const val ABSENCE_GRACE_MS = 250L
    }
}
