package com.fgogotran.translation

/** Pure general request assembly, shared by production routing and offline evaluation. */
private const val SCENE_RESPONSE_EXAMPLE =
    """{"name":null,"dialogue":null,"choices":[]}"""
private const val SCENE_WITH_VOICE_HINT_RESPONSE_EXAMPLE =
    """{"name":null,"dialogue":null,"choices":[],"voice_hint":null}"""

internal fun buildGeneralSceneUserPrompt(
    name: String?,
    dialogue: String?,
    choices: List<String>,
    requestVoiceHint: Boolean,
    translateName: Boolean,
    translateDialogue: Boolean,
    previousDialogueContexts: List<SceneDialogueContext> = emptyList(),
    currentSpeaker: String = "",
    glossaryEntries: List<TranslationGlossaryEntry> = emptyList(),
    voiceHintContext: VoiceHintContext? = null
): String {
    val responseKeys = if (requestVoiceHint) {
        "name, dialogue, choices, voice_hint"
    } else {
        "name, dialogue, choices"
    }
    val emptyExample = if (requestVoiceHint) {
        SCENE_WITH_VOICE_HINT_RESPONSE_EXAMPLE
    } else {
        SCENE_RESPONSE_EXAMPLE
    }
    return buildString {
        appendGeneralTranslationGlossarySection(glossaryEntries)
        appendGeneralReferenceMarkSection(listOfNotNull(name.takeIf { translateName },
            dialogue.takeIf { translateDialogue }) + choices)
        appendGeneralEmphasisSection(listOfNotNull(dialogue.takeIf { translateDialogue }) + choices)
        appendPromptSectionText(buildGeneralCurrentSpeakerContext(currentSpeaker, appliesToDialogue = dialogue != null))
        appendPromptSectionText(buildGeneralPreviousSceneContextPrompt(previousDialogueContexts))
        appendPromptSection(
            "current_scene",
            buildString {
                name?.let {
                    append("Name JP (${if (translateName) "translate" else "context only"}): ")
                    appendLine(it)
                }
                dialogue?.let {
                    appendLine("Dialogue JP (${if (translateDialogue) "translate" else "context only"}):")
                    appendLine(it)
                }
                if (choices.isNotEmpty()) {
                    appendLine("Player choices JP (translate):")
                    append(renderNumberedPromptItems(choices))
                }
            }.trim()
        )
        appendPromptSection(
            "required_output",
            buildString {
                appendLine("Return JSON only with exactly these keys: $responseKeys.")
                appendLine("Example shape: $emptyExample")
                appendLine(
                    "Set name=${if (translateName && name != null) "a nonempty translated JSON string" else "null"}; " +
                        "dialogue=${if (translateDialogue && dialogue != null) "a nonempty translated JSON string" else "null"}; " +
                        "choices=exactly ${choices.size} nonempty translated JSON strings in the same order."
                )
                if (requestVoiceHint) {
                    append(buildVoiceHintInstructions(checkNotNull(voiceHintContext)))
                }
            }
        )
    }
}

internal fun buildGeneralDialogueWithVoiceHintUserPrompt(
    dialogue: String,
    voiceHintContext: VoiceHintContext,
    previousDialogueContexts: List<SceneDialogueContext> = emptyList(),
    currentSpeaker: String = "",
    glossaryEntries: List<TranslationGlossaryEntry> = emptyList()
): String {
    return buildString {
        appendGeneralTranslationGlossarySection(glossaryEntries)
        appendGeneralReferenceMarkSection(listOf(dialogue))
        appendGeneralEmphasisSection(listOf(dialogue))
        appendPromptSectionText(buildGeneralCurrentSpeakerContext(currentSpeaker, appliesToDialogue = true))
        appendPromptSectionText(buildGeneralPreviousSceneContextPrompt(previousDialogueContexts))
        appendPromptSection("current_japanese", dialogue)
        appendPromptSection(
            "required_output",
            buildString {
                appendLine("Return JSON only with exactly these keys: dialogue, voice_hint.")
                appendLine("dialogue must be a nonempty translated JSON string.")
                appendLine("""Neutral example: {"dialogue":"translated dialogue","voice_hint":null}""")
                append(buildVoiceHintInstructions(voiceHintContext))
            }
        )
    }
}

