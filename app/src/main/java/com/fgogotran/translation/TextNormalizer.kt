package com.fgogotran.translation

/**
 * Normalizes OCR text before cache lookup and prompt construction.
 *
 * OCR often changes whitespace between frames. Keeping cache keys stable here
 * prevents repeated API calls for the same visible dialogue.
 */
object TextNormalizer {
    private val rubyAnnotationPattern = Regex("(?<=.)《[^》]{1,24}》")

    fun normalizeForTranslation(text: String): String {
        return text
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
            .replace(Regex("[ \\t]+"), " ")
            .trim()
    }

    fun stripRubyAnnotations(text: String): String {
        return normalizeForTranslation(text).replace(rubyAnnotationPattern, "")
    }

    fun hasTranslatableContent(text: String): Boolean {
        return normalizeForTranslation(text).any { it.isLetterOrDigit() }
    }
}
