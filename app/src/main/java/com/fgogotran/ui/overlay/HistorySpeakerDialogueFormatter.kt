package com.fgogotran.ui.overlay

internal data class HistorySpeakerDialogue(
    val body: String,
    val openingQuote: String,
    val closingQuote: String
)

/** The history speech frame is separate from the stored dialogue and its punctuation. */
internal object HistorySpeakerDialogueFormatter {
    fun prepare(text: String): HistorySpeakerDialogue = HistorySpeakerDialogue(text, "「", "」")
}
