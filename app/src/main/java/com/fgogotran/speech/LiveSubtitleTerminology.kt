package com.fgogotran.speech

/** Local terminology correction for Azure subtitles only; no cross-utterance state. */
internal object LiveSubtitleTerminology {
    private val masterTitles = Regex(
        "(?<![ァ-ヶー])マスター?(?![ァ-ヶー]|する|した|して|しよう|します|しました|すれば|でき|出来|され|させ)"
    )
    // Do not replace a title-shaped fragment inside a known non-title compound.
    private val masterTranslations = Regex(
        "御主|(?<!店)(?:老闆|老板)|店長|店长|主人(?!公|翁)|師父|师父|師傅|师傅|" +
            "大師(?!兄|姐)|大师(?!兄|姐)|達人|达人|名人(?!堂)"
    )

    fun correct(sourceText: String, translatedText: String, targetLanguage: String): String {
        if (targetLanguage != "zh-Hans" && targetLanguage != "zh-Hant") return translatedText
        val titleCount = masterTitles.findAll(sourceText).count()
        if (titleCount == 0) return translatedText

        // Existing correct titles also consume a match; do not guess which word to replace.
        var translationCount = 0
        var needsCorrection = false
        for (match in masterTranslations.findAll(translatedText)) {
            translationCount++
            if (translationCount > titleCount) return translatedText
            if (match.value != "御主") needsCorrection = true
        }
        if (translationCount != titleCount || !needsCorrection) return translatedText
        return masterTranslations.replace(translatedText, "御主")
    }
}
