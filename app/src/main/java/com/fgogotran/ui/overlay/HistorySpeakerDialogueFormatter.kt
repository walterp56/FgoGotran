package com.fgogotran.ui.overlay

import com.fgogotran.data.SettingsRepository

internal data class HistorySpeakerDialogue(
    val body: String,
    val openingQuote: String,
    val closingQuote: String
)

/** The history speech frame is separate from the stored dialogue and its punctuation. */
internal object HistorySpeakerDialogueFormatter {
    fun prepare(text: String, targetLocale: String): HistorySpeakerDialogue {
        val english = SettingsRepository.normalizeTargetLanguage(targetLocale) ==
            SettingsRepository.TARGET_LANGUAGE_ENGLISH
        return if (english) HistorySpeakerDialogue(text, "“", "”")
        else HistorySpeakerDialogue(text, "「", "」")
    }
}
