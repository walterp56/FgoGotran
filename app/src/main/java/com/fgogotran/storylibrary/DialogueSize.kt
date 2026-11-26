package com.fgogotran.storylibrary

/**
 * Presentation size the chapter pack attaches to one dialogue line.
 *
 * The builder writes the game's font command as a marker at the head of the JP
 * source row (`⟬L⟭` for `[f large]`, `⟬S⟭` for `[f small]`). The marker is not
 * text: the app strips it from every displayed string, and it never enters a
 * lookup key. Only the dialogue render scale follows it.
 *
 * `LARGE` is measured from the game: the same `[line 3]` rule is 256 px on a
 * large line and 155 px on a normal one, so the large font is 1.652x. `SMALL`
 * is a first estimate until a capture of an `[f small]` line is available.
 */
enum class DialogueSize(val scale: Float) {
    NORMAL(1f),
    LARGE(1.652f),
    SMALL(0.70f);

    companion object {
        private val marker = Regex("^⟬[LS]⟭")

        /** Stored row text without the leading size marker. */
        fun strip(text: String): String = text.replace(marker, "")

        /** Size of stored row text; absent marker or unknown text is [NORMAL]. */
        fun of(storedText: String): DialogueSize = when {
            storedText.startsWith("⟬L⟭") -> LARGE
            storedText.startsWith("⟬S⟭") -> SMALL
            else -> NORMAL
        }
    }
}
