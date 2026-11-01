package com.fgogotran.translation

import com.fgogotran.data.SettingsRepository

internal const val GENERAL_SCENE_CONTEXT_CACHE_POLICY_VERSION = "general-scene-context-v1"

private val legacyGeneralContextRubyMarkup = Regex("〈[^〉]*〉")

/** Selects only pairs compatible with this general-model request from its existing scene snapshot. */
internal fun prepareGeneralSceneDialogueContexts(
    contexts: List<SceneDialogueContext>,
    targetLanguage: String,
    normalizeSpeaker: (String) -> String,
    normalizeDialogue: (String) -> String,
    toTargetText: (String, String) -> String,
    selectSpeakerTarget: ((String, String, String) -> String)? = null
): List<SceneDialogueContext> {
    val targetLocale = SettingsRepository.normalizeTargetLanguage(targetLanguage)
    val englishTarget = targetLocale == SettingsRepository.TARGET_LANGUAGE_ENGLISH
    return contexts
        .takeLast(SettingsRepository.MAX_TRANSLATION_CONTEXT_SCENE_COUNT)
        .filter { context ->
            val previousEnglish = SettingsRepository.normalizeTargetLanguage(context.targetLocale) ==
                SettingsRepository.TARGET_LANGUAGE_ENGLISH
            previousEnglish == englishTarget
        }
        .mapNotNull { context ->
            val preparedSource = context.generalPreparedSourceDialogue
                ?: context.sourceDialogue.takeUnless(legacyGeneralContextRubyMarkup::containsMatchIn)
                ?: return@mapNotNull null
            val sourceDialogue = normalizeDialogue(FgoDialogueSymbols.normalizeLeadingOcrDash(preparedSource))
            val sourceSpeakerName = context.sourceSpeakerName
                ?.let(normalizeSpeaker)
                ?.takeIf(String::isNotBlank)
            val translatedDialogue = context.translatedDialogue
                ?.takeUnless(String::isGeneralSceneContextErrorText)
                ?.let { if (englishTarget) it else toTargetText(it, targetLocale) }
                ?.let(normalizeDialogue)
                ?.takeIf(String::isNotBlank)
            val translatedSpeakerName = context.translatedSpeakerName
                ?.takeUnless(String::isGeneralSceneContextErrorText)
                ?.let { text ->
                    if (sourceSpeakerName == null || englishTarget) text
                    else selectSpeakerTarget?.invoke(sourceSpeakerName, text, targetLocale) ?: text
                }
                ?.let { if (englishTarget) it else toTargetText(it, targetLocale) }
                ?.let(normalizeSpeaker)
                ?.takeIf(String::isNotBlank)
            if (
                sourceDialogue.isBlank() || translatedDialogue == null ||
                (sourceSpeakerName != null && translatedSpeakerName == null)
            ) {
                null
            } else {
                context.copy(
                    sourceSpeakerName = sourceSpeakerName,
                    translatedSpeakerName = translatedSpeakerName,
                    sourceDialogue = sourceDialogue,
                    translatedDialogue = translatedDialogue,
                    targetLocale = targetLocale
                )
            }
        }
}

internal fun buildGeneralPreviousSceneContextPrompt(contexts: List<SceneDialogueContext>): String {
    if (contexts.isEmpty()) return ""
    return buildPromptSection(
        "reference_context",
        buildString {
            appendLine("Use previous Japanese only to interpret the current scene.")
            appendLine("Previous translations are fallible reference; if they conflict with Japanese, follow Japanese.")
            appendLine("Never output or continue previous scenes, or assume their speaker is speaking now.")
            contexts.forEachIndexed { index, context ->
                if (index > 0) appendLine()
                val languageLabel = when (SettingsRepository.normalizeTargetLanguage(context.targetLocale)) {
                    SettingsRepository.TARGET_LANGUAGE_ENGLISH -> "English"
                    SettingsRepository.TARGET_LANGUAGE_TRADITIONAL -> "Traditional Chinese"
                    else -> "Simplified Chinese"
                }
                appendLine("Scene ${index + 1}:")
                context.sourceSpeakerName?.takeIf(String::isNotBlank)?.let { sourceSpeaker ->
                    appendLine("Speaker JP: $sourceSpeaker")
                    appendLine("Speaker $languageLabel: ${context.translatedSpeakerName.orEmpty()}")
                }
                appendLine("JP: ${context.sourceDialogue}")
                append("$languageLabel: ${context.translatedDialogue.orEmpty()}")
            }
        }
    )
}

private fun String.isGeneralSceneContextErrorText(): Boolean {
    val text = trim()
    return text.startsWith("[未配置 API Key]") ||
        text.startsWith("[No API key]") ||
        text.startsWith("[翻译失败") ||
        text.startsWith("[翻譯失敗") ||
        text.startsWith("[Translation failed") ||
        text == "翻译失败" || text == "翻譯失敗"
}
