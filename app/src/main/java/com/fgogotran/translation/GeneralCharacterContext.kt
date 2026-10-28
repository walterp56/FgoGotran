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

/** A separate general-only asset; legacy profile aliases and model variants remain untouched. */
internal fun parseGeneralCharacterContextRules(lines: Sequence<String>): Map<String, GeneralCharacterContextRule> {
    val rows = lines.map { it.trimEnd('\r', '\n') }.filter { it.isNotBlank() && !it.startsWith('#') }.toList()
    if (rows.isEmpty()) return emptyMap()
    require(rows.first().removePrefix("\uFEFF").split('\t') == listOf(
        "speaker_id", "source_pattern", "base_general", "base_english", "conditional_general", "conditional_english"
    )) { "Invalid general character rules header" }
    val rules = linkedMapOf<String, GeneralCharacterContextRule>()
    rows.drop(1).forEachIndexed { index, line ->
        val columns = line.split('\t').map(String::trim)
        require(columns.size == 6 && columns.all(String::isNotBlank)) { "Invalid general character rule at ${index + 2}" }
        require(columns.drop(2).all { it.length <= 800 } && columns[1].length <= 160) { "General character rule too long" }
        require(columns[0] !in rules) { "Duplicate general character rule: ${columns[0]}" }
        rules[columns[0]] = GeneralCharacterContextRule(
            Regex(columns[1]), columns[2], columns[3], columns[4], columns[5]
        )
    }
    return rules
}

internal fun prepareGeneralCharacterContext(
    profile: CharacterContextProfile,
    japaneseSource: String,
    targetLanguage: String,
    rules: Map<String, GeneralCharacterContextRule>
): PreparedGeneralCharacterContext {
    val rule = rules[profile.speakerId] ?: return PreparedGeneralCharacterContext(
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
