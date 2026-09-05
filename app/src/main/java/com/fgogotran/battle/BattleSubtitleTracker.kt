package com.fgogotran.battle

enum class BattleSceneMode { STORY, BATTLE, WAITING }

/** Pure state machine: only confirmed FGO UI evidence changes capture ownership. */
class BattleSceneTracker {
    var mode = BattleSceneMode.STORY
        private set
    private var battleCount = 0
    private var storyCount = 0
    private var resultCount = 0

    val inBattle: Boolean get() = mode == BattleSceneMode.BATTLE
    val blocksStory: Boolean get() = mode != BattleSceneMode.STORY || battleCount > 0

    fun observe(
        hudVisible: Boolean,
        storyVisible: Boolean = false,
        resultVisible: Boolean = false,
        choiceVisible: Boolean = false
    ): BattleSceneMode {
        when (mode) {
            BattleSceneMode.STORY -> {
                storyCount = 0
                resultCount = 0
                battleCount = if (hudVisible) battleCount + 1 else 0
                if (battleCount >= REQUIRED_OBSERVATIONS) transitionTo(BattleSceneMode.BATTLE)
            }

            BattleSceneMode.BATTLE -> when {
                // A complete story signature is stronger than stray HUD-coloured pixels.
                storyVisible -> {
                    battleCount = 0
                    resultCount = 0
                    if (++storyCount >= REQUIRED_OBSERVATIONS) transitionTo(BattleSceneMode.STORY)
                }
                hudVisible -> {
                    battleCount = 0
                    storyCount = 0
                    resultCount = 0
                }
                resultVisible -> {
                    battleCount = 0
                    storyCount = 0
                    if (++resultCount >= REQUIRED_OBSERVATIONS) transitionTo(BattleSceneMode.WAITING)
                }
                else -> {
                    // HUD-free attacks, Noble Phantasms and loading frames remain battle-owned.
                    battleCount = 0
                    storyCount = 0
                    resultCount = 0
                }
            }

            BattleSceneMode.WAITING -> when {
                storyVisible || choiceVisible -> {
                    battleCount = 0
                    resultCount = 0
                    if (++storyCount >= REQUIRED_OBSERVATIONS) transitionTo(BattleSceneMode.STORY)
                }
                hudVisible -> {
                    storyCount = 0
                    resultCount = 0
                    if (++battleCount >= REQUIRED_OBSERVATIONS) transitionTo(BattleSceneMode.BATTLE)
                }
                else -> {
                    battleCount = 0
                    storyCount = 0
                    resultCount = 0
                }
            }
        }
        return mode
    }

    fun reset() {
        mode = BattleSceneMode.STORY
        clearCounts()
    }

    private fun transitionTo(next: BattleSceneMode) {
        mode = next
        clearCounts()
    }

    private fun clearCounts() {
        battleCount = 0
        storyCount = 0
        resultCount = 0
    }

    companion object { private const val REQUIRED_OBSERVATIONS = 2 }
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

    /** Two content-agreeing reads identify occurrences. Delivery survives disappearance and replacement. */
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

    /** OCR-volatile punctuation never splits one occurrence; the original source remains untouched. */
    private fun key(text: String): String {
        if (text.isBlank()) return ""
        val content = text.filterNot {
            it.isWhitespace() || it == '　' || it in IDENTITY_IGNORED_PUNCTUATION
        }
        return if (content.isNotEmpty()) content else PUNCTUATION_ONLY_KEY
    }

    companion object {
        const val MIN_CONFIRM_MS = 70L
        const val ABSENCE_GRACE_MS = 250L
        private const val IDENTITY_IGNORED_PUNCTUATION =
            "「」『』“”\"'‘’（）()［］[]｛｝{}、。，．,.！？!?…‥・：:；;—―－-〜～♪"
        private const val PUNCTUATION_ONLY_KEY = "<punctuation>"
    }
}
