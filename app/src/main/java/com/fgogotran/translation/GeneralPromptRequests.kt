package com.fgogotran.translation

import com.fgogotran.text.EmphasisText

/** Reuse the original general request on every repair; feedback never becomes a new task. */
internal fun buildGeneralStrictRepairMessages(
    initialMessages: List<ChatMessage>,
    targetLanguage: String,
    badTranslation: String = "",
    kanaTokens: List<String> = emptyList()
): List<ChatMessage> {
    require(initialMessages.size == 2 && initialMessages[0].role == "system" &&
        initialMessages[1].role == "user"
    ) { "General repair requires the initial system/user message pair" }
    val feedback = buildString {
        if (badTranslation.isNotBlank()) {
            appendLine(badTranslation.trim())
            appendLine()
        }
        appendLine("Use ${targetLanguagePromptLabel(targetLanguage)}.")
        appendLine("Repair the draft using the current Japanese source and required output format.")
        appendLine(
            "Translate untranslated Japanese except exact player names and literals explicitly required to remain unchanged."
        )
        append("Preserve every meaning; do not add explanation.")
        if (kanaTokens.isNotEmpty()) {
            append("\nReview these Japanese fragments: ")
            append(kanaTokens.joinToString(", "))
        }
    }
    val repairedUserPrompt = buildString {
        append(initialMessages[1].content)
        appendPromptSection("previous_draft", feedback)
    }
    return listOf(initialMessages[0], initialMessages[1].copy(content = repairedUserPrompt))
}

internal fun buildGeneralCurrentSpeakerContext(
    currentSpeaker: String,
    appliesToDialogue: Boolean
): String = buildGeneralCurrentSpeakerContext(
    currentSpeaker,
    appliesToDialogue,
    "player choices or name labels"
)

private fun buildGeneralCurrentSpeakerContext(
    currentSpeaker: String,
    appliesToDialogue: Boolean,
    currentItems: String
): String {
    if (currentSpeaker.isBlank()) return ""
    return if (appliesToDialogue) {
        buildPromptSection(
            "speaker_context",
            currentSpeaker.trim() +
                "\nUse this identity only for voice and register of the current dialogue supported by Japanese.\n" +
                "Never apply it to player choices or name labels, or infer omitted participants, possession, or relationships."
        )
    } else {
        "Scene speaker (reference only):\n${currentSpeaker.trim()}\n" +
            "Current items are $currentItems; this speaker does not supply their voice or register."
    }
}

internal fun buildGeneralSingleUserPrompt(
    promptBuilder: PromptBuilder,
    japaneseText: String,
    choiceTexts: List<String>,
    previousDialogueContexts: List<SceneDialogueContext> = emptyList(),
    currentSpeaker: String = "",
    translateAsChoices: Boolean = false,
    translateAsName: Boolean = false,
    glossaryEntries: List<TranslationGlossaryEntry> = emptyList()
): String {
    val basePrompt = if (translateAsName || translateAsChoices) {
        buildString {
            appendGeneralTranslationGlossarySection(glossaryEntries)
            appendGeneralReferenceMarkSection(listOf(japaneseText))
            appendGeneralEmphasisSection(listOf(japaneseText))
            appendPromptSection("current_japanese", japaneseText)
        }
    } else {
        promptBuilder.buildUserPrompt(japaneseText, choiceTexts, glossaryEntries)
    }
    if (currentSpeaker.isBlank() && previousDialogueContexts.isEmpty()) return basePrompt
    return buildString {
        appendPromptSectionText(
            buildGeneralCurrentSpeakerContext(
                currentSpeaker,
                appliesToDialogue = !translateAsChoices && !translateAsName,
                currentItems = if (translateAsName) "name labels" else "player choices"
            )
        )
        appendPromptSectionText(buildGeneralPreviousSceneContextPrompt(previousDialogueContexts))
        appendPromptSectionText(basePrompt)
    }
}

internal fun buildGeneralBatchUserPrompt(
    texts: List<String>,
    currentSpeaker: String = "",
    translateAsChoices: Boolean = false,
    glossaryEntries: List<TranslationGlossaryEntry> = emptyList(),
    previousDialogueContexts: List<SceneDialogueContext> = emptyList()
): String = buildString {
    appendGeneralTranslationGlossarySection(glossaryEntries)
    appendGeneralReferenceMarkSection(texts)
    appendGeneralEmphasisSection(texts)
    appendPromptSectionText(
        buildGeneralCurrentSpeakerContext(
            currentSpeaker,
            appliesToDialogue = !translateAsChoices,
            currentItems = "player choices"
        )
    )
    appendPromptSectionText(buildGeneralPreviousSceneContextPrompt(previousDialogueContexts))
    appendPromptSection("current_japanese_rows", renderNumberedPromptItems(texts))
    appendPromptSection(
        "required_output",
        "Return a JSON array of exactly ${texts.size} nonempty translated strings, one per input item, " +
            "in the same order${if (translateAsChoices) "; treat every item as a player choice" else ""}."
    )
}

/** No annotation, no new prompt tokens. This helper is never called by Sakura templates. */
internal fun StringBuilder.appendGeneralEmphasisSection(texts: List<String>) {
    if (texts.none(EmphasisText::has)) return
    appendPromptSection("visual_emphasis", "⟦…⟧ marks visual emphasis only. Translate naturally and retain it around the corresponding wording; do not change meaning to preserve the markers.")
}
