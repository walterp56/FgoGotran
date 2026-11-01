package com.fgogotran.overlay

/** Display preferences only. Never included in translation requests or cache keys. */
enum class DialogueTextSpeed(val storedValue: String, val label: String, val charactersPerSecond: Double) {
    HALF("half", "0.5", 5.0), NORMAL("normal", "1", 10.0), DOUBLE("double", "2", 20.0),
    TRIPLE("triple", "3", 30.0), QUADRUPLE("quadruple", "4", 40.0), INSTANT("instant", "MAX", 0.0);

    companion object {
        fun fromStored(value: String?) = entries.firstOrNull { it.storedValue == value } ?: INSTANT
    }
}

data class DialoguePlaybackSettings(
    val speed: DialogueTextSpeed = DialogueTextSpeed.INSTANT,
    val punctuationPause: Int = 0
) {
    val pauseLevel: Int get() = punctuationPause.coerceIn(0, 3)
    /** MAX removes character delay, but punctuation can still require timed presentation. */
    val requiresPlayback: Boolean get() = speed != DialogueTextSpeed.INSTANT || pauseLevel > 0
}

/** Immutable reveal deadlines in logical-character boundaries supplied by the Android ICU adapter. */
internal class DialoguePlaybackTimeline(
    val text: String,
    characterEnds: IntArray,
    settings: DialoguePlaybackSettings
) {
    private val ends = characterEnds.copyOf()
    private val deadlines = LongArray(ends.size)
    val durationNanos: Long get() = deadlines.lastOrNull() ?: 0L

    init {
        require((1 until ends.size).all { ends[it - 1] < ends[it] } &&
            (ends.isEmpty() && text.isEmpty() || ends.firstOrNull()?.let { it > 0 } == true && ends.last() == text.length))
        val interval = if (settings.speed == DialogueTextSpeed.INSTANT) 0L else
            (1_000_000_000.0 / settings.speed.charactersPerSecond).toLong()
        var next = 0L
        var start = 0
        for (i in ends.indices) {
            deadlines[i] = next
            val end = ends[i]
            if (end < text.length) {
                if (text.substring(start, end).any { !it.isWhitespace() }) next += interval
                if (pausesAfter(text, end)) next += settings.pauseLevel * PAUSE_STEP_NANOS
            }
            start = end
        }
    }

    fun visibleEndAt(elapsedNanos: Long): Int {
        var low = 0
        var high = deadlines.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (deadlines[mid] <= elapsedNanos.coerceAtLeast(0L)) low = mid + 1 else high = mid
        }
        return if (low == 0) 0 else ends[low - 1]
    }

    companion object {
        // The unobstructed 0.5 recording advances about five JP characters/second. Other
        // speeds use that multiplier. Pause levels are short display pauses, not seconds
        // or a claim about FGO's private script timers; fast-forwarded samples cannot calibrate them.
        private const val PAUSE_STEP_NANOS = 100_000_000L
        private const val PUNCTUATION = "、，,。.!?！？…"
        private val abbreviation = Regex("(?i)(?:\\b(?:mr|mrs|ms|dr|prof|st|vs|etc)|(?:[a-z]\\.)+[a-z])\\.$")

        private fun pausesAfter(text: String, end: Int): Boolean {
            val c = text[end - 1]
            if (c !in PUNCTUATION || text[end] in PUNCTUATION) return false
            if (c == '.') {
                if (end >= 2 && text[end - 2].isDigit() && text[end].isDigit()) return false
                if (abbreviation.containsMatchIn(text.substring(0, end))) return false
                // Internal dots of initialisms, e.g. U.S., are not sentence boundaries.
                if (text[end].isLetter() && end + 1 < text.length && text[end + 1] == '.') return false
            }
            return true
        }
    }
}

/** No coroutine or per-character timers; a view samples this clock on display frames. */
internal class DialoguePlaybackController {
    private var timeline: DialoguePlaybackTimeline? = null
    private var anchor = 0L
    private var accumulated = 0L
    private var paused = false
    private var completed = false
    var generation = 0L
        private set

    fun start(value: DialoguePlaybackTimeline, now: Long) {
        generation++
        timeline = value
        anchor = now
        accumulated = 0L
        paused = false
        completed = false
    }

    fun visibleEnd(now: Long): Int {
        val current = timeline ?: return 0
        return if (completed) current.text.length else current.visibleEndAt(elapsed(now))
    }

    fun isRevealing(now: Long): Boolean = timeline?.let { visibleEnd(now) < it.text.length } == true
    fun pause(now: Long) { if (!paused) { accumulated = elapsed(now); paused = true } }
    fun resume(now: Long) { if (paused) { anchor = now; paused = false } }
    fun complete() { completed = true }
    fun cancel() { generation++; timeline = null; completed = false; paused = true }
    private fun elapsed(now: Long) = accumulated + if (paused) 0L else (now - anchor).coerceAtLeast(0L)
}

/** Latch at DOWN so natural completion before UP cannot accidentally advance the game. */
internal class DialoguePlaybackTapGate {
    private var owner: Long? = null
    private var downX = 0f
    private var downY = 0f
    private var slopSquared = 0f

    fun down(generation: Long, x: Float, y: Float, slop: Float, eligible: Boolean) {
        owner = generation.takeIf { eligible && x.isFinite() && y.isFinite() }
        downX = x; downY = y; slopSquared = slop * slop
    }
    fun move(x: Float, y: Float) {
        if (!x.isFinite() || !y.isFinite() || (x - downX) * (x - downX) + (y - downY) * (y - downY) > slopSquared) cancel()
    }
    fun up(generation: Long, x: Float, y: Float): Boolean {
        move(x, y)
        val consume = owner == generation
        cancel()
        return consume
    }
    fun cancel() { owner = null }
}
