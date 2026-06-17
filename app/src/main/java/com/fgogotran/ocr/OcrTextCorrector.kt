package com.fgogotran.ocr

/**
 * Conservative cleanup for high-confidence OCR glyph confusions in dialogue text.
 *
 * Rules are generated from canonical Japanese words plus visually confusable
 * glyphs, then applied only in normal word contexts. This avoids broad character
 * replacement such as changing every 善 into 普.
 */
object OcrTextCorrector {
    private data class CorrectionRule(
        val ocrVariant: String,
        val canonical: String
    )

    private val likelyDialogueWords = listOf(
        "普段"
    )

    private val confusableGlyphs = mapOf(
        '普' to setOf('善')
    )

    private val rules: List<CorrectionRule> = likelyDialogueWords
        .flatMap { canonical ->
            variantsFor(canonical)
                .filter { it != canonical }
                .map { variant -> CorrectionRule(variant, canonical) }
        }
        .sortedByDescending { it.ocrVariant.length }

    fun correct(text: String): String {
        if (text.isBlank() || rules.isEmpty()) return text

        var corrected = text
        for (rule in rules) {
            corrected = applyRule(corrected, rule)
        }
        return corrected
    }

    private fun variantsFor(canonical: String): Set<String> {
        var variants = setOf("")
        for (char in canonical) {
            val choices = listOf(char) + confusableGlyphs[char].orEmpty()
            variants = variants.flatMap { prefix ->
                choices.map { choice -> prefix + choice }
            }.toSet()
        }
        return variants
    }

    private fun applyRule(text: String, rule: CorrectionRule): String {
        val result = StringBuilder()
        var searchStart = 0
        var changed = false

        while (searchStart < text.length) {
            val matchStart = text.indexOf(rule.ocrVariant, searchStart)
            if (matchStart < 0) break

            val matchEndExclusive = matchStart + rule.ocrVariant.length
            result.append(text, searchStart, matchStart)
            if (hasDialogueWordContext(text, matchStart, matchEndExclusive)) {
                result.append(rule.canonical)
                changed = true
            } else {
                result.append(text, matchStart, matchEndExclusive)
            }
            searchStart = matchEndExclusive
        }

        if (!changed) return text
        result.append(text, searchStart, text.length)
        return result.toString()
    }

    private fun hasDialogueWordContext(text: String, start: Int, endExclusive: Int): Boolean {
        val before = text.getOrNull(start - 1)
        val after = text.getOrNull(endExclusive)
        return isSafeBeforeWord(before) && isSafeAfterWord(after)
    }

    private fun isSafeBeforeWord(char: Char?): Boolean {
        return char == null ||
                char.isWhitespace() ||
                char in setOf(
                    '\n', '　', '、', '。', '，', '．', '.', ',', '!',
                    '！', '?', '？', '…', '「', '『', '（', '(', '[',
                    '【', 'は', 'が', 'を', 'に', 'で', 'へ', 'と',
                    'も', 'の', 'や', 'か'
                )
    }

    private fun isSafeAfterWord(char: Char?): Boolean {
        return char == null ||
                char.isWhitespace() ||
                char in setOf(
                    '\n', '　', '、', '。', '，', '．', '.', ',', '!',
                    '！', '?', '？', '…', '」', '』', '）', ')', ']',
                    '】', 'は', 'が', 'を', 'に', 'で', 'へ', 'と',
                    'も', 'の', 'や', 'か', 'よ', 'ね', 'な', 'だ'
                )
    }
}
