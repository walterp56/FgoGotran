package com.fgogotran.translation

/**
 * Shared FGO-style punctuation rules used after OCR and translation.
 *
 * Keep this focused on visual dialogue rhythm only; terminology and OCR word
 * correction stay in their own layers.
 */
object FgoDialogueSymbols {
    const val PAUSE_ELLIPSIS = "……"
    const val LONG_DASH_RUN = "───"

    val longPausePattern = Regex("[·・･]{2,}|\\.{2,}|…+|‥+|⋯+")
    val trailingDashRunPattern = Regex("[—―─━ー－\\-一]{2,}\\s*$")

    private val alternatePauseRunPattern = Regex("[·・･]{2,}|‥+|⋯+")
    private val asciiPauseDotRunPattern = Regex("\\.+")
    private val unicodeEllipsisRunPattern = Regex("…+")
    private val longHorizontalLineRunPattern =
        Regex("[—―─━ー－-]{2,}|(?<![\\p{IsHan}A-Za-z0-9])一{2,}(?![\\p{IsHan}A-Za-z0-9])")
    private val leadingAsciiDashBeforeTextPattern =
        Regex("(?m)(^|[「『（(\\[\\s　])-+(?=[\\u3400-\\u9FFFA-Za-z0-9_])")
    private val leadingOcrHyphenBeforeJapanesePattern =
        Regex(
            """(?m)(^|[「『（(\[])([ \t　]*)[-－]+(?=[\u3040-\u30FF\u31F0-\u31FF\u3400-\u9FFF\uF900-\uFAFF\uFF66-\uFF9D■□▇█])"""
        )

    fun containsLongPause(text: String): Boolean {
        return longPausePattern.containsMatchIn(text)
    }

    fun startsWithLongPause(text: String): Boolean {
        return longPausePattern.find(text)?.range?.first == 0
    }

    fun endsWithLongPause(text: String): Boolean {
        val match = longPausePattern.findAll(text).lastOrNull() ?: return false
        return match.range.last == text.lastIndex
    }

    fun normalizePauseDots(text: String): String {
        return alternatePauseRunPattern
            .replace(text, PAUSE_ELLIPSIS)
            .let { normalized ->
                unicodeEllipsisRunPattern.replace(normalized) { match ->
                    if (
                        match.value.length == 1 &&
                        normalized.hasCjkTextAround(match.range.first, match.range.last + 1)
                    ) {
                        PAUSE_ELLIPSIS
                    } else {
                        match.value
                    }
                }
            }
            .let { normalized ->
                asciiPauseDotRunPattern.replace(normalized) { match ->
                    when {
                        !normalized.hasCjkTextAround(match.range.first, match.range.last + 1) -> {
                            match.value
                        }

                        match.value.length == 3 -> PAUSE_ELLIPSIS
                        match.value.length == 6 -> PAUSE_ELLIPSIS.repeat(2)
                        else -> match.value
                    }
                }
            }
    }

    /**
     * Applies conservative FGO-style punctuation to a completed Chinese
     * dialogue. It intentionally does not add punctuation or alter names,
     * choices, masks, Latin tokens, or unusual dramatic pause lengths.
     */
    fun normalizeTranslatedPunctuation(text: String): String {
        val trimmed = text.trimEnd()
        if (trimmed.isBlank()) return trimmed

        val collapsed = collapseTrailingCommas(trimmed)
        val terminalIndex = collapsed.indexOfLast { !it.isWhitespace() }
        val normalized = StringBuilder(collapsed.length)
        var index = 0
        while (index < collapsed.length) {
            val symbol = collapsed[index]
            when (symbol) {
                '.' -> {
                    val runEnd = collapsed.indexAfterRun(index, '.')
                    val runLength = runEnd - index
                    when {
                        runLength in ASCII_ELLIPSIS_RUNS &&
                            collapsed.hasCjkTextAround(index, runEnd) -> {
                            repeat(runLength / 3) { normalized.append(PAUSE_ELLIPSIS) }
                        }

                        runLength == 1 && collapsed.shouldNormalizePeriod(index) -> {
                            normalized.append('。')
                        }

                        else -> normalized.append(collapsed, index, runEnd)
                    }
                    index = runEnd
                }

                '…' -> {
                    val runEnd = collapsed.indexAfterRun(index, '…')
                    val runLength = runEnd - index
                    if (runLength == 1 && collapsed.hasCjkTextAround(index, runEnd)) {
                        normalized.append(PAUSE_ELLIPSIS)
                    } else {
                        normalized.append(collapsed, index, runEnd)
                    }
                    index = runEnd
                }

                '．' -> {
                    normalized.append(if (collapsed.shouldNormalizePeriod(index)) '。' else symbol)
                    index++
                }

                ',', '，', '、' -> {
                    normalized.append(
                        if (
                            symbol == ',' &&
                            index != terminalIndex &&
                            collapsed.hasCjkTextAround(index, index + 1)
                        ) {
                            '，'
                        } else {
                            symbol
                        }
                    )
                    index++
                }

                '!', '?' -> {
                    normalized.append(
                        if (collapsed.hasPrecedingCjkText(index)) {
                            if (symbol == '!') '！' else '？'
                        } else {
                            symbol
                        }
                    )
                    index++
                }

                else -> {
                    normalized.append(symbol)
                    index++
                }
            }
        }

        return normalized.toString()
    }

    fun normalizeDashRuns(text: String): String {
        return longHorizontalLineRunPattern.replace(text, LONG_DASH_RUN)
    }

    /**
     * Repairs the common OCR result where a leading dramatic dash is read as
     * an ASCII/full-width hyphen. The Japanese/mask look-ahead deliberately
     * excludes negative numbers and ordinary Latin hyphenated text.
     */
    fun normalizeLeadingOcrDash(text: String): String {
        return leadingOcrHyphenBeforeJapanesePattern.replace(text) {
            "${it.groupValues[1]}${it.groupValues[2]}$LONG_DASH_RUN"
        }
    }

    fun normalizeForRender(text: String): String {
        return text
            .replace('－', '-')
            .replace('―', '—')
            .let(::normalizeDashRuns)
            .replace(leadingAsciiDashBeforeTextPattern) {
                "${it.groupValues[1]}$LONG_DASH_RUN"
            }
            .let(::normalizePauseDots)
            .replace(Regex("[ \\t]+"), " ")
            .trim()
    }

    fun isDashRunChar(char: Char): Boolean {
        return char in setOf('—', '―', '─', '━', 'ー', '－', '-', '一')
    }

    private fun collapseTrailingCommas(text: String): String {
        val commaTail = text.takeLastWhile { it in COMMA_SYMBOLS }
        if (commaTail.length <= 1) return text
        return text.dropLast(commaTail.length) + commaTail.first()
    }

    private fun String.indexAfterRun(startIndex: Int, symbol: Char): Int {
        var index = startIndex
        while (index < length && this[index] == symbol) index++
        return index
    }

    private fun String.hasPrecedingCjkText(punctuationIndex: Int): Boolean {
        var index = punctuationIndex - 1
        while (index >= 0) {
            val current = this[index]
            when {
                current == '\n' || current == '\r' -> return false
                current.isWhitespace() || current in CJK_CONTEXT_SKIPPED_SYMBOLS -> index--
                else -> return current.isCjkTextCharacter()
            }
        }
        return false
    }

    private fun String.hasFollowingCjkText(punctuationEndIndex: Int): Boolean {
        var index = punctuationEndIndex
        while (index < length) {
            val current = this[index]
            when {
                current == '\n' || current == '\r' -> return false
                current.isWhitespace() || current in CJK_CONTEXT_SKIPPED_SYMBOLS -> index++
                else -> return current.isCjkTextCharacter()
            }
        }
        return false
    }

    private fun String.hasCjkTextAround(startIndex: Int, endIndex: Int): Boolean {
        return hasPrecedingCjkText(startIndex) || hasFollowingCjkText(endIndex)
    }

    private fun String.shouldNormalizePeriod(periodIndex: Int): Boolean {
        if (!hasPrecedingCjkText(periodIndex)) return false
        val next = getOrNull(periodIndex + 1) ?: return true
        return !next.isAsciiLetterOrDigit()
    }

    private fun Char.isCjkTextCharacter(): Boolean {
        return this in '\u3400'..'\u4DBF' ||
            this in '\u4E00'..'\u9FFF' ||
            this in '\uF900'..'\uFAFF' ||
            this in '\u3040'..'\u30FF' ||
            this in '\u31F0'..'\u31FF'
    }

    private fun Char.isAsciiLetterOrDigit(): Boolean {
        return this in 'A'..'Z' || this in 'a'..'z' || this in '0'..'9'
    }

    private val COMMA_SYMBOLS = setOf('、', '，', ',')
    private val ASCII_ELLIPSIS_RUNS = setOf(3, 6)
    private val CJK_CONTEXT_SKIPPED_SYMBOLS = setOf(
        '!', '?', '！', '？', '.', '．', '…', '‥', '―', '—', '─', '～', '〜',
        '」', '』', '）', '】', '”', '’', '"', '\''
    )
}
