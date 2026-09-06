package com.fgogotran.battle

import kotlin.math.abs

enum class BattleSceneMode { STORY, BATTLE, WAITING }

/** Pure state machine: only confirmed FGO UI evidence changes capture ownership. */
class BattleSceneTracker {
    var mode = BattleSceneMode.STORY
        private set
    private var battleCount = 0
    private var choiceCount = 0
    private var resultCount = 0

    val inBattle: Boolean get() = mode == BattleSceneMode.BATTLE
    val blocksStory: Boolean get() = mode != BattleSceneMode.STORY || battleCount > 0

    fun observe(
        hudVisible: Boolean,
        diamondVisible: Boolean = false,
        resultVisible: Boolean = false,
        choiceVisible: Boolean = false
    ): BattleSceneMode {
        // FGO's dialogue-complete diamond is the authoritative story signal.
        // It wins immediately even when remnants of the battle HUD are visible.
        if (diamondVisible) {
            if (mode == BattleSceneMode.STORY) clearCounts()
            else transitionTo(BattleSceneMode.STORY)
            return mode
        }

        when (mode) {
            BattleSceneMode.STORY -> {
                choiceCount = 0
                resultCount = 0
                battleCount = if (hudVisible) battleCount + 1 else 0
                if (battleCount >= REQUIRED_OBSERVATIONS) transitionTo(BattleSceneMode.BATTLE)
            }

            BattleSceneMode.BATTLE -> when {
                hudVisible -> {
                    battleCount = 0
                    choiceCount = 0
                    resultCount = 0
                }
                resultVisible -> {
                    battleCount = 0
                    choiceCount = 0
                    if (++resultCount >= REQUIRED_OBSERVATIONS) transitionTo(BattleSceneMode.WAITING)
                }
                else -> {
                    // HUD-free attacks, Noble Phantasms and loading frames remain battle-owned.
                    battleCount = 0
                    choiceCount = 0
                    resultCount = 0
                }
            }

            BattleSceneMode.WAITING -> when {
                choiceVisible -> {
                    battleCount = 0
                    resultCount = 0
                    if (++choiceCount >= REQUIRED_OBSERVATIONS) transitionTo(BattleSceneMode.STORY)
                }
                hudVisible -> {
                    choiceCount = 0
                    resultCount = 0
                    if (++battleCount >= REQUIRED_OBSERVATIONS) transitionTo(BattleSceneMode.BATTLE)
                }
                else -> {
                    battleCount = 0
                    choiceCount = 0
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
        choiceCount = 0
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
    private var pending: PendingOccurrence? = null
    private val activeKeys = mutableSetOf<String>()
    private var absentSince: Long? = null
    private val endedThisObservation = mutableListOf<BattleSubtitleEnd>()
    val endedEvents: List<BattleSubtitleEnd> get() = endedThisObservation
    val ended: BattleSubtitleEnd? get() = endedThisObservation.lastOrNull()
    val hasPending: Boolean get() = pending != null
    var lastConfirmationReason = ""
        private set
    var lastConfirmationObservations = 0
        private set

    /** Compatibility entry point for callers/tests that do not have OCR quality metadata. */
    fun observe(source: String?, now: Long): BattleSubtitleEvent? {
        val text = source?.trim().orEmpty()
        return if (text.isEmpty()) observeBlank(now) else observeCandidate(
            BattleSubtitleCandidate(text, confidence = 1f, rowCount = 1, horizontalCoverage = 1f), now
        )
    }

    /** Two temporally compatible reads identify an occurrence without rewriting either OCR result. */
    fun observeCandidate(observed: BattleSubtitleCandidate, now: Long): BattleSubtitleEvent? {
        endedThisObservation.clear()
        val text = observed.text.trim()
        if (text.isEmpty()) return observeBlank(now)
        val candidate = if (text == observed.text) observed else observed.copy(text = text)
        absentSince = null
        val candidateKey = key(text)
        if (candidateKey in activeKeys || candidateKey == key(current?.source.orEmpty())) {
            pending = null
            return null
        }

        val existing = pending
        if (existing == null || now - existing.lastSeenAt > MAX_CONFIRM_GAP_MS) {
            pending = PendingOccurrence(candidate, now)
            return null
        }
        val match = existing.match(candidate)
        if (match == MatchKind.NONE) {
            pending = PendingOccurrence(candidate, now)
            return null
        }
        existing.add(candidate, now)
        if (now - existing.startedAt < MIN_CONFIRM_MS) return null
        return confirmPending("two ${match.description} reads")
    }

    private fun observeBlank(now: Long): BattleSubtitleEvent? {
        endedThisObservation.clear()
        if (absentSince == null) {
            absentSince = now
            return null
        }
        val sourceEndedAt = absentSince!!
        if (now - sourceEndedAt < ABSENCE_GRACE_MS) return null

        val pendingOccurrence = pending
        if (pendingOccurrence != null &&
            sourceEndedAt - pendingOccurrence.lastSeenAt <= MAX_CONFIRM_GAP_MS &&
            pendingOccurrence.isEligibleForDisappearanceConfirmation()) {
            return confirmPending("strong read followed by confirmed disappearance", sourceEndedAt)
        }
        pending = null
        current?.let { endedThisObservation += BattleSubtitleEnd(it.id, sourceEndedAt) }
        current = null
        activeKeys.clear()
        return null
    }

    /** Capture/OCR failure is unknown: keep pending evidence, but cancel an absence claim. */
    fun observationUnavailable() {
        endedThisObservation.clear()
        absentSince = null
    }

    /** A confirmed scene boundary can close a strong subtitle that was visible for only one OCR pass. */
    fun finalizePendingAtBoundary(now: Long): BattleSubtitleEvent? {
        endedThisObservation.clear()
        val occurrence = pending ?: return null
        if (now - occurrence.lastSeenAt > MAX_CONFIRM_GAP_MS ||
            !occurrence.isEligibleForDisappearanceConfirmation()) {
            pending = null
            return null
        }
        return confirmPending("strong read followed by confirmed scene boundary", now)
    }

    fun clear() {
        current = null
        pending = null
        activeKeys.clear()
        absentSince = null
        endedThisObservation.clear()
        lastConfirmationReason = ""
        lastConfirmationObservations = 0
        // Do not reset IDs: late replies from a previous session must remain invalid.
    }

    private fun confirmPending(reason: String, sourceEndedAt: Long? = null): BattleSubtitleEvent? {
        val occurrence = pending ?: return null
        val selected = occurrence.bestCandidate()
        current?.let { endedThisObservation += BattleSubtitleEnd(it.id, occurrence.startedAt) }
        val event = BattleSubtitleEvent(++nextId, selected.text, occurrence.startedAt)
        current = event
        activeKeys.clear()
        activeKeys += occurrence.evidence.map { key(it.candidate.text) }
        pending = null
        lastConfirmationReason = reason
        lastConfirmationObservations = occurrence.observations
        if (sourceEndedAt != null) {
            endedThisObservation += BattleSubtitleEnd(event.id, sourceEndedAt)
            current = null
            activeKeys.clear()
        }
        return event
    }

    /** OCR-volatile punctuation participates in identity only; selected source text stays untouched. */
    private fun key(text: String): String {
        if (text.isBlank()) return ""
        val content = text.filterNot {
            it.isWhitespace() || it == '　' || it in IDENTITY_IGNORED_PUNCTUATION
        }
        if (content.isNotEmpty()) return content
        val punctuation = buildString(text.length) {
            text.forEach { char ->
                when (char) {
                    '!', '！' -> append('！')
                    '?', '？' -> append('？')
                    '.', '。', '．', '…', '‥' -> append('…')
                    '-', '－', '—', '―' -> append('—')
                    '~', '〜', '～' -> append('～')
                    '♪' -> append('♪')
                }
            }
        }.collapseRuns()
        return PUNCTUATION_ONLY_KEY + punctuation
    }

    private fun matchKind(first: String, second: String): MatchKind {
        val a = key(first)
        val b = key(second)
        if (a == b) return MatchKind.EXACT
        if (a.startsWith(PUNCTUATION_ONLY_KEY) || b.startsWith(PUNCTUATION_ONLY_KEY)) return MatchKind.NONE

        val shorter = minOf(a.length, b.length)
        val longer = maxOf(a.length, b.length)
        if (shorter >= MIN_PREFIX_LENGTH && longer >= MIN_FUZZY_LENGTH &&
            (a.startsWith(b) || b.startsWith(a)) && shorter.toFloat() / longer >= MIN_PREFIX_RATIO) {
            return MatchKind.PREFIX
        }
        if (shorter < MIN_FUZZY_LENGTH) return MatchKind.NONE
        val allowedEdits = maxOf(1, (longer * MAX_EDIT_RATIO).toInt())
        if (abs(a.length - b.length) > allowedEdits) return MatchKind.NONE
        val distance = levenshteinDistance(a, b, allowedEdits)
        val similarity = 1f - distance.toFloat() / longer
        return if (distance <= allowedEdits && similarity >= MIN_SIMILARITY) MatchKind.FUZZY else MatchKind.NONE
    }

    private fun levenshteinDistance(a: String, b: String, limit: Int): Int {
        var previous = IntArray(b.length + 1) { it }
        var currentRow = IntArray(b.length + 1)
        for (i in a.indices) {
            currentRow[0] = i + 1
            var rowMinimum = currentRow[0]
            for (j in b.indices) {
                val substitution = previous[j] + if (a[i] == b[j]) 0 else 1
                currentRow[j + 1] = minOf(previous[j + 1] + 1, currentRow[j] + 1, substitution)
                rowMinimum = minOf(rowMinimum, currentRow[j + 1])
            }
            if (rowMinimum > limit) return limit + 1
            val swap = previous
            previous = currentRow
            currentRow = swap
        }
        return previous[b.length]
    }

    private fun String.collapseRuns(): String = buildString(length) {
        this@collapseRuns.forEach { if (lastOrNull() != it) append(it) }
    }

    private inner class PendingOccurrence(first: BattleSubtitleCandidate, val startedAt: Long) {
        val evidence = mutableListOf(CandidateEvidence(first, startedAt))
        var observations = 1
            private set
        var lastSeenAt = startedAt
            private set

        fun match(candidate: BattleSubtitleCandidate): MatchKind = evidence.asSequence()
            .map { matchKind(it.candidate.text, candidate.text) }
            .maxByOrNull(MatchKind::strength) ?: MatchKind.NONE

        fun add(candidate: BattleSubtitleCandidate, now: Long) {
            val exactVariant = evidence.firstOrNull { it.candidate.text == candidate.text }
            if (exactVariant == null) evidence += CandidateEvidence(candidate, now)
            else exactVariant.add(candidate)
            observations++
            lastSeenAt = now
        }

        fun bestCandidate(): BattleSubtitleCandidate = evidence.maxWithOrNull(
            compareBy<CandidateEvidence> { it.count }
                .thenBy { key(it.candidate.text).removePrefix(PUNCTUATION_ONLY_KEY).length }
                .thenBy { it.averageConfidence }
                .thenBy { punctuationCompleteness(it.candidate.text) }
                .thenBy { it.candidate.text.count { char -> !char.isWhitespace() } }
                .thenBy { it.candidate.horizontalCoverage }
                .thenBy { -it.firstSeenAt }
        )!!.candidate

        fun isEligibleForDisappearanceConfirmation(): Boolean {
            val candidate = bestCandidate()
            val candidateKey = key(candidate.text)
            return !candidateKey.startsWith(PUNCTUATION_ONLY_KEY) &&
                candidateKey.length >= MIN_STRONG_SINGLE_LENGTH &&
                candidate.confidence >= MIN_STRONG_SINGLE_CONFIDENCE &&
                candidate.rowCount in 1..2 && candidate.horizontalCoverage >= MIN_STRONG_SINGLE_COVERAGE
        }
    }

    private fun punctuationCompleteness(text: String): Int {
        val compact = text.filterNot(Char::isWhitespace)
        if (compact.isEmpty()) return 0
        val startsWithQuote = compact.first() in "「『“\"‘'"
        val endsWithQuote = compact.last() in "」』”\"’'"
        val terminal = compact.dropLastWhile { it in "」』”\"’'）)］]｝}" }.lastOrNull()
        return (if (startsWithQuote) 2 else 0) +
            (if (endsWithQuote) 2 else 0) +
            (if (startsWithQuote && endsWithQuote) 2 else 0) +
            (if (terminal != null && terminal in "！？!?…‥♪") 5 else 0) +
            compact.count { it in "！？!?…‥♪" }.coerceAtMost(3)
    }

    private data class CandidateEvidence(
        var candidate: BattleSubtitleCandidate,
        val firstSeenAt: Long,
        var count: Int = 1,
        var confidenceTotal: Float = candidate.confidence
    ) {
        val averageConfidence: Float get() = confidenceTotal / count

        fun add(next: BattleSubtitleCandidate) {
            count++
            confidenceTotal += next.confidence
            val currentQuality = candidate.confidence * 10f + candidate.horizontalCoverage
            val nextQuality = next.confidence * 10f + next.horizontalCoverage
            if (nextQuality > currentQuality) candidate = next
        }
    }

    private enum class MatchKind(val strength: Int, val description: String) {
        NONE(0, "unrelated"), FUZZY(1, "near-matching"), PREFIX(2, "prefix-compatible"), EXACT(3, "content-agreeing")
    }

    companion object {
        const val MIN_CONFIRM_MS = 70L
        const val ABSENCE_GRACE_MS = 250L
        const val MAX_CONFIRM_GAP_MS = 2_000L
        private const val MIN_FUZZY_LENGTH = 5
        private const val MIN_PREFIX_LENGTH = 4
        private const val MIN_PREFIX_RATIO = 0.25f
        private const val MIN_SIMILARITY = 0.84f
        private const val MAX_EDIT_RATIO = 0.15f
        private const val MIN_STRONG_SINGLE_LENGTH = 5
        private const val MIN_STRONG_SINGLE_CONFIDENCE = 0.72f
        private const val MIN_STRONG_SINGLE_COVERAGE = 0.04f
        private const val IDENTITY_IGNORED_PUNCTUATION =
            "「」『』“”\"'‘’（）()［］[]｛｝{}、。，．,.！？!?…‥・：:；;—―－-〜～♪"
        private const val PUNCTUATION_ONLY_KEY = "<punctuation>:"
    }
}
