package com.fgogotran.translation

/**
 * Normalizes OCR text before cache lookup and prompt construction.
 *
 * OCR often changes whitespace between frames. Keeping cache keys stable here
 * prevents repeated API calls for the same visible dialogue.
 */
object TextNormalizer {
    private val rubyAnnotationPattern = Regex("(?<=.)〈[^〉]{1,24}〉")
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

    /** Real dialogue can consist entirely of expressive marks; it needs display, not an API. */
    fun isPunctuationOnlyDialogue(text: String): Boolean {
        val normalized = normalizeForTranslation(text)
        return normalized.isNotEmpty() && normalized.all {
            it.isWhitespace() || it in DIALOGUE_PUNCTUATION
        }
    }

    fun hasDialogueContent(text: String): Boolean =
        hasTranslatableContent(text) || isPunctuationOnlyDialogue(text)

    // Do not include Japanese ー or 一: they remain lexical text, never a global dash substitute.
    private const val DIALOGUE_PUNCTUATION = "…‥⋯.．·・･•─━—―-－_、。，,!！?？:：;；「」『』“”\"'（）()[]【】〈〉《》"

    /** FGO's standard masked speaker name is exactly three full-width question marks. */
    fun canonicalQuestionMask(text: String): String? {
        val compact = normalizeForTranslation(text)
        return if (compact.length == 3 && compact.all { it == '?' || it == '？' }) "？？？" else null
    }

    fun isQuestionMaskOnly(text: String): Boolean = canonicalQuestionMask(text) != null
}
