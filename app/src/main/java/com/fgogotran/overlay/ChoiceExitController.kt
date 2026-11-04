package com.fgogotran.overlay

/** Elapsed time since the expected game-tap release, independent of callback latency. */
internal object ChoiceExitController {
    const val UNSELECTED_HOLD_MS = 100L
    const val UNSELECTED_FADE_MS = 500L
    const val SELECTED_HOLD_MS = 700L
    const val SELECTED_FADE_MS = 600L
    const val DURATION_MS = SELECTED_HOLD_MS + SELECTED_FADE_MS
    const val SELECTION_EFFECT_MS = 300L
    private const val SELECTION_RISE_MS = 60L
    private const val SELECTION_MAX_ALPHA = 140
    private const val SELECTION_MAX_SCALE = 1.6f

    fun selectionScale(elapsedMs: Long): Float =
        1f + (SELECTION_MAX_SCALE - 1f) * elapsedMs.coerceIn(0L, SELECTION_EFFECT_MS) / SELECTION_EFFECT_MS

    fun selectionAlpha(elapsedMs: Long): Int = when {
        elapsedMs <= 0L || elapsedMs >= SELECTION_EFFECT_MS -> 0
        elapsedMs < SELECTION_RISE_MS -> (SELECTION_MAX_ALPHA * elapsedMs / SELECTION_RISE_MS).toInt()
        else -> (SELECTION_MAX_ALPHA * (SELECTION_EFFECT_MS - elapsedMs) /
            (SELECTION_EFFECT_MS - SELECTION_RISE_MS)).toInt()
    }

    fun alpha(selected: Boolean, elapsedMs: Long): Int {
        val hold = if (selected) SELECTED_HOLD_MS else UNSELECTED_HOLD_MS
        val fade = if (selected) SELECTED_FADE_MS else UNSELECTED_FADE_MS
        if (elapsedMs <= hold) return 255
        if (elapsedMs >= hold + fade) return 0
        val remaining = hold + fade - elapsedMs
        // The sampled game footage fades quickly at first, then eases towards transparent.
        return (255L * remaining * remaining / (fade * fade)).toInt()
    }

    /** Keep the cover opaque above half text opacity; then both ease out together. */
    fun backgroundAlpha(selected: Boolean, elapsedMs: Long): Int =
        (alpha(selected, elapsedMs) * 2).coerceAtMost(255)

    /** An underlay beneath the existing flattened patch, preserving its exact glyph pixels. */
    fun additionalBackgroundAlpha(selected: Boolean, elapsedMs: Long): Int {
        val text = alpha(selected, elapsedMs)
        if (text == 255) return 0
        return (backgroundAlpha(selected, elapsedMs) - text) * 255 / (255 - text)
    }

    /** Zero requests a display frame; positive delays skip the two static hold intervals. */
    fun nextUpdateDelayMs(elapsedMs: Long, hasUnselected: Boolean, hasSelectedEffect: Boolean = false): Long? = when {
        elapsedMs >= DURATION_MS -> null
        hasSelectedEffect && elapsedMs < SELECTION_EFFECT_MS -> 0L
        hasUnselected && elapsedMs < UNSELECTED_HOLD_MS -> UNSELECTED_HOLD_MS - elapsedMs
        hasUnselected && elapsedMs < UNSELECTED_HOLD_MS + UNSELECTED_FADE_MS -> 0L
        elapsedMs < SELECTED_HOLD_MS -> SELECTED_HOLD_MS - elapsedMs
        else -> 0L
    }
}
