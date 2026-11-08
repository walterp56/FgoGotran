package com.fgogotran.ui.overlay

internal data class HistorySpeakerDialogue(
    val body: String,
    val openingQuote: String,
    val closingQuote: String,
    val trailingPunctuation: String
)

/** Uses target-language speech quotes for translations; Japanese originals retain `「...」`. */
internal object HistorySpeakerDialogueFormatter {
    private const val OPENING_QUOTE = '「'
    private const val CLOSING_QUOTE = '」'

    private val trailingPunctuation = setOf(
        '。', '．', '.', '、', '，', ',', '！', '!', '？', '?',
        '…', '‥', '⋯', '—', '―', '─', '━', '－', '-',
        '〜', '～', '~', '：', ':', '；', ';', '♪', '♡', '♥', '☆', '★'
    )

    fun prepare(text: String, targetLocale: String? = null): HistorySpeakerDialogue {
        if (targetLocale != null) {
            val normalized = com.fgogotran.translation.TranslationPunctuationNormalizer.normalize(text, targetLocale)
            val open = if (targetLocale == "en") '"' else '『'
            val close = if (targetLocale == "en") '"' else '』'
            var end = normalized.lastIndex
            while (end > 0 && normalized[end] in trailingPunctuation) end--
            var depth = 0
            var completeEarly = false
            if (open != close) normalized.take(end + 1).forEachIndexed { index, char ->
                if (char == open) depth++
                if (char == close) depth--
                if (depth <= 0 && index < end) completeEarly = true
            }
            val onePair = normalized.firstOrNull() == open && normalized.getOrNull(end) == close &&
                if (open == close) normalized.substring(1, end).none { it == open } else depth == 0 && !completeEarly
            return if (onePair) HistorySpeakerDialogue(normalized.substring(1, end), open.toString(), close.toString(), normalized.substring(end + 1))
            else HistorySpeakerDialogue(normalized, open.toString(), close.toString(), "")
        }
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
