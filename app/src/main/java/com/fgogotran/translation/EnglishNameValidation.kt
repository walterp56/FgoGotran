package com.fgogotran.translation

/** A resolved name part passed to the name prompt as an official hint. */
data class NameTranslationHint(
    val source: String,
    val target: String
)

/**
 * English name-box validation used only by the English name path.
 *
 * The Chinese name path keeps its existing behaviour and never calls these helpers.
 */
internal object EnglishNameValidation {
    fun containsCjkOrKana(text: String): Boolean = text.any { char ->
        ((char in '\u3040'..'\u30FF' || char in '\uFF66'..'\uFF9D') && char != '\u30FB') ||
            char in '\u3400'..'\u9FFF' ||
            char in '\uF900'..'\uFAFF'
    }

    fun hasLatinLetter(text: String): Boolean =
        text.any { it in 'a'..'z' || it in 'A'..'Z' }

    fun isValidState(stateText: String, maxLength: Int): Boolean {
        if (stateText.isBlank() || stateText.length > maxLength) return false
        if (containsCjkOrKana(stateText)) return false
        if (!hasLatinLetter(stateText)) return false
        return stateText.none { it == '\n' || it == '\r' }
    }

    fun isValidFullName(
        sourceName: String,
        translatedName: String,
        baseTranslation: String,
        maxLength: Int
    ): Boolean {
        val translated = translatedName.trim()
        if (translated.isBlank() || translated.length > maxLength) return false
        if (containsCjkOrKana(translated)) return false
        if (!hasLatinLetter(translated)) return false
        if (translated.any { it == '\n' || it == '\r' }) return false

        val sourceOpen = sourceName.count { it == '(' || it == '（' }
        val sourceClose = sourceName.count { it == ')' || it == '）' }
        val translatedOpen = translated.count { it == '(' || it == '（' }
        val translatedClose = translated.count { it == ')' || it == '）' }
        if (sourceOpen != translatedOpen || sourceClose != translatedClose) return false

        val baseKey = nameLookupKey(baseTranslation)
        if (baseKey.isNotBlank() && !nameLookupKey(translated).contains(baseKey)) return false
        return true
    }

    private fun nameLookupKey(text: String): String =
        text.filter { it.isLetterOrDigit() }.lowercase()
}
