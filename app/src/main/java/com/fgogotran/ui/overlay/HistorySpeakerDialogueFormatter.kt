package com.fgogotran.ui.overlay

internal data class HistorySpeakerDialogue(
    val body: String,
    val openingQuote: String,
    val closingQuote: String,
    val trailingPunctuation: String
)

/** Applies the fixed outer `「...」` history convention to dialogue with a speaker. */
internal object HistorySpeakerDialogueFormatter {
    private const val OPENING_QUOTE = '「'
    private const val CLOSING_QUOTE = '」'

    private val trailingPunctuation = setOf(
        '。', '．', '.', '、', '，', ',', '！', '!', '？', '?',
        '…', '‥', '⋯', '—', '―', '─', '━', '－', '-',
        '〜', '～', '~', '：', ':', '；', ';', '♪', '♡', '♥', '☆', '★'
    )

    fun prepare(text: String): HistorySpeakerDialogue {
        val trimmed = text.trim()
        if (trimmed.length < 2) return wrap(trimmed)

        var closingIndex = trimmed.lastIndex
        while (closingIndex > 0 && trimmed[closingIndex] in trailingPunctuation) {
            closingIndex--
        }
        if (!trimmed.hasSingleOuterSpeechQuote(closingIndex)) return wrap(trimmed)

        return HistorySpeakerDialogue(
            body = trimmed.substring(1, closingIndex),
            openingQuote = OPENING_QUOTE.toString(),
            closingQuote = CLOSING_QUOTE.toString(),
            trailingPunctuation = trimmed.substring(closingIndex + 1)
        )
    }

    private fun wrap(text: String): HistorySpeakerDialogue {
        return HistorySpeakerDialogue(
            body = text,
            openingQuote = OPENING_QUOTE.toString(),
            closingQuote = CLOSING_QUOTE.toString(),
            trailingPunctuation = ""
        )
    }

    /**
     * Reuse an existing pair only when it encloses the complete dialogue.
     * Adjacent fragments such as `「A」「B」` still need one outer history pair.
     */
    private fun String.hasSingleOuterSpeechQuote(closingIndex: Int): Boolean {
        if (firstOrNull() != OPENING_QUOTE || getOrNull(closingIndex) != CLOSING_QUOTE) {
            return false
        }

        var depth = 0
        for (index in 0..closingIndex) {
            when (this[index]) {
                OPENING_QUOTE -> depth++
                CLOSING_QUOTE -> {
                    depth--
                    if (depth < 0 || (depth == 0 && index != closingIndex)) return false
                }
            }
        }
        return depth == 0
    }
}
