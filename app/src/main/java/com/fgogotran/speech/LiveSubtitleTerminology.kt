package com.fgogotran.speech

import java.text.Normalizer

/** Local terminology correction for Azure subtitles only; no cross-utterance state. */
internal object LiveSubtitleTerminology {
    // Allow spacing inside the word, but never across rows. Consume an available long mark
    // without backtracking to a short title inside a compound such as マスタード.
    private val masterTitles = Regex(
        "(?:(?<![ァ-ヶー])[マま][\\p{Zs}\\t]*[スす][\\p{Zs}\\t]*[タた](?:[\\p{Zs}\\t]*ー)?+(?![ァ-ヶー])|" +
            "(?<![A-Za-z0-9_ァ-ヶー])(?i:master)(?![A-Za-z0-9_ァ-ヶー]))" +
            "(?!する|した|して|しよう|します|しました|すれば|でき|出来|され|させ)"
    )
    // Do not replace a title-shaped fragment inside a known non-title compound.
    private val masterTranslations = Regex(
        "御主|(?<!店)(?:老闆|老板)|店長|店长|主人(?!公|翁)|師父|师父|師傅|师傅|" +
            "大師(?!兄|姐)|大师(?!兄|姐)|達人|达人|名人(?!堂)"
    )
    // Unlike Master, Servant remains a valid term in compounds such as グランドサーヴァント.
    private val servantTerms = Regex(
        "[サさ][\\p{Zs}\\t]*ー[\\p{Zs}\\t]*(?:[ヴゔ][\\p{Zs}\\t]*[ァぁ]|[バば])[\\p{Zs}\\t]*[ンん][\\p{Zs}\\t]*[トと]|" +
            "(?<![A-Za-z0-9_])(?i:servants?)(?![A-Za-z0-9_])"
    )
    private val servantTranslations = Regex("從者|从者|僕人|仆人|傭人|佣人|侍從|侍从|隨從|随从|僕從|仆从|僕役|仆役|侍者")

    fun correct(sourceText: String, translatedText: String, targetLanguage: String): String {
        if (targetLanguage != "zh-Hans" && targetLanguage != "zh-Hant") return translatedText
        // Normalize only the recognition copy; preserve the original subtitle text.
        val normalizedSource = Normalizer.normalize(sourceText, Normalizer.Form.NFKC)
        val masterCorrected = replaceMatchedTerm(
            translatedText, masterTitles.findAll(normalizedSource).count(), masterTranslations, "御主"
        )
        // Each term has its own budget; an absent or ambiguous Master must not block Servant.
        return replaceMatchedTerm(
            masterCorrected, servantTerms.findAll(normalizedSource).count(), servantTranslations,
            if (targetLanguage == "zh-Hant") "從者" else "从者"
        )
    }

    private fun replaceMatchedTerm(
        translatedText: String,
        sourceCount: Int,
        candidates: Regex,
        replacement: String
    ): String {
        if (sourceCount == 0) return translatedText

        // Existing correct titles also consume a match; do not guess which word to replace.
        var translationCount = 0
        var needsCorrection = false
        for (match in candidates.findAll(translatedText)) {
            translationCount++
            if (translationCount > sourceCount) return translatedText
            if (match.value != replacement) needsCorrection = true
        }
        if (translationCount != sourceCount || !needsCorrection) return translatedText
        return candidates.replace(translatedText, replacement)
    }
}
