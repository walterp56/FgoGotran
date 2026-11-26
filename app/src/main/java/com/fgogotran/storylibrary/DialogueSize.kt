package com.fgogotran.storylibrary

/**
 * Presentation size the chapter pack attaches to one dialogue line.
 *
 * The builder writes the game's font command as a marker at the head of the JP
 * source row (`⟬L⟭` for `[f large]`, `⟬S⟭` for `[f small]`, `⟬XL⟭` for
 * `[f x-large]`). The marker is not text: the app strips it from every displayed
 * string, and it never enters a lookup key. Only the dialogue render scale
 * follows it.
 *
 * The scales are measured from the game's own glyph advance (a full-width CJK
 * cell, so the advance equals the em box):
 * - normal 51.7 px (a 3-wide `[line N]` rule is 155 px),
 * - `[f large]` 85.3 px (the same rule is 256 px) -> 1.652x,
 * - `[f x-large]` 120 px (measured on a 2340x1080 capture) -> 2.32x,
 * - `[f small]` has no capture yet; 0.70x is the first estimate.
 */
enum class DialogueSize(val scale: Float) {
    NORMAL(1f),
    LARGE(1.652f),
    EXTRA_LARGE(2.32f),
    SMALL(0.70f);

    companion object {
        private val marker = Regex("^⟬(?:L|S|XL)⟭")

        /** Stored row text without the leading size marker. */
        fun strip(text: String): String = text.replace(marker, "")

        /** Size of stored row text; absent marker or unknown text is [NORMAL]. */
        fun of(storedText: String): DialogueSize = when {
            storedText.startsWith("⟬XL⟭") -> EXTRA_LARGE
            storedText.startsWith("⟬L⟭") -> LARGE
            storedText.startsWith("⟬S⟭") -> SMALL
            else -> NORMAL
        }
    }
}
