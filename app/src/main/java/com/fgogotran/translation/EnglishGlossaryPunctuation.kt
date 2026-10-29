package com.fgogotran.translation

import com.fgogotran.data.SettingsRepository

/** Only content shortcuts use this; visible name labels and model/cache results stay separate. */
internal fun TranslateResult.withEnglishGlossaryPunctuation(
    sourceText: String?,
    targetLanguage: String
): TranslateResult {
    if (sourceText.isNullOrBlank() || isFailure ||
        backend !in setOf("character-db", "glossary") ||
        SettingsRepository.normalizeTargetLanguage(targetLanguage) != SettingsRepository.TARGET_LANGUAGE_ENGLISH ||
        !TextNormalizer.hasTranslatableContent(sourceText)
    ) return this

    val restored = FgoDialogueSymbols.restoreEnglishGlossaryTerminal(sourceText, translatedText)
    return if (restored == translatedText) this else copy(translatedText = restored)
}
