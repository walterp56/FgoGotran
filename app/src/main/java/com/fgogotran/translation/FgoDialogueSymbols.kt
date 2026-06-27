package com.fgogotran.translation

/**
 * Shared FGO-style punctuation rules used after OCR and translation.
 *
 * Keep this focused on visual dialogue rhythm only; terminology and OCR word
 * correction stay in their own layers.
 */
object FgoDialogueSymbols {
    const val PAUSE_ELLIPSIS = "……"
    const val LONG_DASH_RUN = "———"

    val longPausePattern = Regex("[·・･]{2,}|\\.{2,}|…+|‥+|⋯+")
    val trailingDashRunPattern = Regex("[—―─━ー－\\-一]{2,}\\s*$")

    private val longHorizontalLineRunPattern =
        Regex("[—―─━ー－-]{2,}|(?<![\\p{IsHan}A-Za-z0-9])一{2,}(?![\\p{IsHan}A-Za-z0-9])")
    private val leadingAsciiDashBeforeTextPattern =
        Regex("(?m)(^|[「『（(\\[\\s　])-+(?=[\\u3400-\\u9FFFA-Za-z0-9_])")

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
        return longPausePattern.replace(text, PAUSE_ELLIPSIS)
    }

    fun normalizeDashRuns(text: String): String {
        return longHorizontalLineRunPattern.replace(text, LONG_DASH_RUN)
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
}
