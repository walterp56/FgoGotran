package com.fgogotran.translation

/**
 * Normalizes OCR text before cache lookup and prompt construction.
 *
 * OCR often changes whitespace between frames. Keeping cache keys stable here
 * prevents repeated API calls for the same visible dialogue.
 */
object TextNormalizer {
    private val rubyAnnotationPattern = Regex("(?<=.)《[^》]{1,24}》")
    private val caldeaOcrPattern = Regex("""力ル(?=[\s　…・･、。,.!?！？ー─—―]*デア)""")

    fun normalizeForTranslation(text: String): String {
        return text
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
            .replace(Regex("[ \\t]+"), " ")
            .replace(caldeaOcrPattern, "カル")
            .trim()
    }

    fun stripRubyAnnotations(text: String): String {
        return normalizeForTranslation(text).replace(rubyAnnotationPattern, "")
    }

    fun hasRubyAnnotations(text: String): Boolean {
        return rubyAnnotationPattern.containsMatchIn(normalizeForTranslation(text))
    }

    fun hasTranslatableContent(text: String): Boolean {
        return normalizeForTranslation(text).any { it.isLetterOrDigit() }
    }
}
