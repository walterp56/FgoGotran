package com.fgogotran.story

/** Choice-request eligibility and completeness, including punctuation-only rows. */
internal object ChoiceRecognitionPolicy {
    /** A story advance tap is not user initiation; only the floating button requests OCR. */
    fun canRecognize(userInitiated: Boolean, fullAuto: Boolean): Boolean = userInitiated || fullAuto

    fun <T> isComplete(expectedButtons: List<T>, rows: List<Pair<T, String>>): Boolean {
        if (expectedButtons.isEmpty() || rows.size != expectedButtons.size) return false
        val expected = expectedButtons.toSet()
        if (expected.size != expectedButtons.size || rows.any { it.second.isBlank() }) return false
        val recognized = rows.map { it.first }.toSet()
        return recognized.size == rows.size && recognized == expected
    }

    /** Replaces the position-only AUTO cooldown. Holds compact masks, never capture pixels. */
    class EmptyOcrCooldown(private val baseMs: Long = 600L, private val maxMs: Long = 1_200L) {
        private var emptyFrame: StoryTapHandoff.ChoiceFrame? = null
        private var retryAt = 0L
        private var streak = 0

        init {
            require(baseMs > 0L && maxMs >= baseMs)
        }

        fun isCoolingDown(frame: StoryTapHandoff.ChoiceFrame, now: Long): Boolean {
            if (emptyFrame?.sameText(frame) != true) {
                reset()
                return false
            }
            return now < retryAt
        }

        fun recordEmpty(frame: StoryTapHandoff.ChoiceFrame, now: Long): Long {
            streak = if (emptyFrame?.sameText(frame) == true) {
                (streak + 1).coerceAtMost((maxMs / baseMs).toInt() + 1)
            } else 1
            val duration = (baseMs * streak).coerceAtMost(maxMs)
            emptyFrame = frame
            retryAt = now + duration
            return duration
        }

        fun reset() {
            emptyFrame = null
            retryAt = 0L
            streak = 0
        }
    }
}
