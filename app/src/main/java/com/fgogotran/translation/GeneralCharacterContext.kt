package com.fgogotran.translation

import com.fgogotran.data.SettingsRepository
import java.text.Normalizer

internal data class GeneralCharacterContextRule(
    val sourcePattern: Regex,
    val baseGeneral: String,
    val baseEnglish: String,
    val conditionalGeneral: String,
    val conditionalEnglish: String
)

internal data class PreparedGeneralCharacterContext(val prompt: String, val cacheIdentity: String)

internal fun prepareGeneralCharacterContext(
    profile: CharacterContextProfile,
    japaneseSource: String,
    targetLanguage: String
): PreparedGeneralCharacterContext {
    val rule = profile.generalRule ?: return PreparedGeneralCharacterContext(
        profile.promptFor(targetLanguage, isSakuraModel = false),
        profile.cacheIdentityFor(targetLanguage, isSakuraModel = false)
    )
    val english = SettingsRepository.normalizeTargetLanguage(targetLanguage) == SettingsRepository.TARGET_LANGUAGE_ENGLISH
    // Normalize only the classifier input, never the source passed to the API.
    val classifierSource = Normalizer.normalize(japaneseSource, Normalizer.Form.NFKC)
        .map { if (it in '\u30A1'..'\u30F6') (it.code - 0x60).toChar() else it }.joinToString("")
    val base = if (english) rule.baseEnglish else rule.baseGeneral
    // A possible match retains the old conditional guidance; it never authorizes adding a tic.
    val prompt = if (rule.sourcePattern.containsMatchIn(classifierSource)) {
        "$base ${if (english) rule.conditionalEnglish else rule.conditionalGeneral}"
    } else base
    return PreparedGeneralCharacterContext(prompt, listOf(
        profile.speakerId, "general-source-voice-v1", if (english) "english" else "general", prompt
    ).joinToString("\u001D"))
}
