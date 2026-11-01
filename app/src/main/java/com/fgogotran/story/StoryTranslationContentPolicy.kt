package com.fgogotran.story

import com.fgogotran.translation.SceneTranslateInput
import com.fgogotran.translation.TextNormalizer

/** Story-only eligibility; OCR still retains every recognised row and its original position. */
internal object StoryTranslationContentPolicy {
    data class Decision(
        val translateDialogue: Boolean,
        val translateChoices: List<Boolean>
    ) {
        val hasWork: Boolean get() = translateDialogue || translateChoices.any { it }

        fun shouldTranslateChoice(index: Int): Boolean = translateChoices.getOrElse(index) { false }

        /** Empty choice slots preserve the translator's original-index result mapping. */
        fun prepare(input: SceneTranslateInput): SceneTranslateInput {
            require(input.choices.size == translateChoices.size)
            return input.copy(
                name = input.name.takeIf { translateDialogue },
                dialogue = input.dialogue.takeIf { translateDialogue },
                choices = input.choices.mapIndexed { index, text ->
                    if (shouldTranslateChoice(index)) text else ""
                },
                dialogueEmphasisRanges = if (translateDialogue) input.dialogueEmphasisRanges else emptyList(),
                choiceEmphasisRanges = if (input.choiceEmphasisRanges.isEmpty()) emptyList() else input.choices.indices.map { if (shouldTranslateChoice(it)) input.choiceEmphasisRanges.getOrElse(it) { emptyList() } else emptyList() },
                requestVoiceHint = input.requestVoiceHint && translateDialogue
            )
        }
    }

    fun evaluate(input: SceneTranslateInput): Decision = Decision(
        translateDialogue = TextNormalizer.hasTranslatableContent(input.dialogue.orEmpty()),
        translateChoices = input.choices.map(TextNormalizer::hasTranslatableContent)
    )
}
