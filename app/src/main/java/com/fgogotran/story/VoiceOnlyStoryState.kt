package com.fgogotran.story

/** Non-JP recognition state only. Never owns an overlay, replayed tap, bitmap or voice player. */
internal class VoiceOnlyStoryState<DialogueMask> {
    val visualGate = StoryOcrVisualGate()
    val emptyChoiceCooldown = ChoiceRecognitionPolicy.EmptyOcrCooldown()
    var generation = 0L
        private set

    private data class Source(val fingerprint: String, val stabilityKey: String)
    private val acceptedSources = mutableMapOf<String, Source>()
    private val retryAt = mutableMapOf<String, Long>()
    private val failureStreaks = mutableMapOf<String, Pair<String, Int>>()
    private var previousMask: DialogueMask? = null
    private var beforePreviousMask: DialogueMask? = null
    private var acceptedChoices: StoryTapHandoff.ChoiceFrame? = null
    private var absentChoiceFrames = 0
    private var choicesDeparted = false
    private var unchangedChoicesAt = Long.MIN_VALUE
    private var choiceVerificationPending = false

    fun isAccepted(mode: String, fingerprint: String, stabilityKey: String): Boolean {
        val accepted = acceptedSources[mode] ?: return false
        return fingerprint == accepted.fingerprint ||
            (stabilityKey.isNotBlank() && stabilityKey == accepted.stabilityKey)
    }

    fun acceptSource(mode: String, fingerprint: String, stabilityKey: String) {
        acceptedSources[mode] = Source(fingerprint, stabilityKey)
        retryAt.remove(mode)
        failureStreaks.remove(mode)
    }

    /** Every stored mask had marker evidence; a missing marker/mask breaks the three-frame chain. */
    fun dialogueComplete(
        strictMarker: Boolean,
        markerEvidence: Boolean,
        mask: DialogueMask?,
        similar: (DialogueMask, DialogueMask) -> Boolean
    ): Boolean {
        if (strictMarker) {
            resetCompletion()
            return true
        }
        if (!markerEvidence || mask == null) {
            resetCompletion()
            return false
        }
        val previous = previousMask
        val beforePrevious = beforePreviousMask
        if (previous != null && beforePrevious != null &&
            similar(beforePrevious, previous) && similar(previous, mask)
        ) {
            resetCompletion()
            return true
        }
        beforePreviousMask = previous
        previousMask = mask
        return false
    }

    fun resetCompletion() {
        previousMask = null
        beforePreviousMask = null
    }

    fun onChoicesAbsent() {
        unchangedChoicesAt = Long.MIN_VALUE
        absentChoiceFrames++
        if (absentChoiceFrames >= 2 && acceptedChoices != null) choicesDeparted = true
        emptyChoiceCooldown.reset()
    }

    fun hasChoiceDeparture(): Boolean = choicesDeparted

    fun shouldSkipChoices(frame: StoryTapHandoff.ChoiceFrame, now: Long, dialogueMayBePresent: Boolean): Boolean {
        absentChoiceFrames = 0
        if (dialogueMayBePresent || choicesDeparted || !frame.hasText ||
            acceptedChoices?.sameText(frame) != true
        ) {
            unchangedChoicesAt = Long.MIN_VALUE
            return false
        }
        if (choiceVerificationPending) return false
        if (unchangedChoicesAt == Long.MIN_VALUE) unchangedChoicesAt = now
        if (now - unchangedChoicesAt >= UNCHANGED_VERIFICATION_MS) {
            choiceVerificationPending = true
            return false
        }
        return true
    }

    /** Commit only complete OCR, including an unchanged-source periodic verification. */
    fun acceptChoices(frame: StoryTapHandoff.ChoiceFrame) {
        acceptedChoices = frame
        absentChoiceFrames = 0
        choicesDeparted = false
        unchangedChoicesAt = Long.MIN_VALUE
        choiceVerificationPending = false
        emptyChoiceCooldown.reset()
    }

    fun isCoolingDown(mode: String, now: Long): Boolean = now < (retryAt[mode] ?: 0L)

    fun delayFor(mode: String, now: Long, durationMs: Long) {
        retryAt[mode] = maxOf(retryAt[mode] ?: 0L, now + durationMs)
    }

    fun rememberFailure(mode: String, reason: String, now: Long, baseMs: Long, maxMs: Long) {
        val previous = failureStreaks[mode]
        val streak = if (previous?.first == reason) (previous.second + 1).coerceAtMost(100) else 1
        failureStreaks[mode] = reason to streak
        delayFor(mode, now, (baseMs * streak).coerceAtMost(maxMs))
    }

    /** An advance hint wakes ordinary scanning, not a request to speak semi-auto choices. */
    fun onAdvanceObserved() {
        retryAt.clear()
        failureStreaks.clear()
        resetCompletion()
    }

    fun reset() {
        generation++
        acceptedSources.clear()
        visualGate.reset()
        emptyChoiceCooldown.reset()
        acceptedChoices = null
        absentChoiceFrames = 0
        choicesDeparted = false
        unchangedChoicesAt = Long.MIN_VALUE
        choiceVerificationPending = false
        onAdvanceObserved()
    }

    private companion object {
        const val UNCHANGED_VERIFICATION_MS = 1_200L
    }
}
