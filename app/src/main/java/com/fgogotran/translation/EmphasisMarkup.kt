package com.fgogotran.translation

object EmphasisMarkup {
    const val OPEN = "<em>"
    const val CLOSE = "</em>"

    data class Range(
        val startIndex: Int,
        val endIndex: Int,
        val phrase: String
    )

    data class Parsed(
        val plainText: String,
        val phrases: List<String>,
        val ranges: List<Range> = emptyList()
    )

    fun hasMarkup(text: String): Boolean {
        return text.contains(OPEN, ignoreCase = true) && text.contains(CLOSE, ignoreCase = true)
    }

    fun strip(text: String): String {
        return text
            .replace(OPEN, "", ignoreCase = true)
            .replace(CLOSE, "", ignoreCase = true)
    }

    fun parse(text: String): Parsed {
        if (!hasMarkup(text)) return Parsed(text, emptyList())

        val plain = StringBuilder()
        val phrases = mutableListOf<String>()
        val ranges = mutableListOf<Range>()
        var index = 0

        while (index < text.length) {
            val openIndex = text.indexOf(OPEN, index, ignoreCase = true)
            if (openIndex < 0) {
                plain.append(text.substring(index))
                break
            }

            plain.append(text.substring(index, openIndex))
            val contentStart = openIndex + OPEN.length
            val closeIndex = text.indexOf(CLOSE, contentStart, ignoreCase = true)
            if (closeIndex < 0) {
                plain.append(text.substring(openIndex))
                break
            }

            val emphasized = text.substring(contentStart, closeIndex)
            val rangeStart = plain.length
            plain.append(emphasized)
            val rangeEnd = plain.length
            val phrase = emphasized.trim()
            if (phrase.isNotBlank()) {
                phrases += phrase
                ranges += Range(rangeStart, rangeEnd, phrase)
            }
            index = closeIndex + CLOSE.length
        }

        return Parsed(
            plainText = plain.toString(),
            phrases = phrases.distinct(),
            ranges = ranges
        )
    }
}
