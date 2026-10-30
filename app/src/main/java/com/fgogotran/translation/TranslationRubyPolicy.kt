package com.fgogotran.translation

import com.fgogotran.text.EmphasisText

/** Request-only ruby filtering; the original OCR source and session history stay intact. */
object TranslationRubyPolicy {
    private val rubyMarkup = Regex("〈[^〉]*〉")
    private val repeatedSpaces = Regex("[ \\t]{2,}")

    fun prepare(input: SceneTranslateInput, includeRuby: Boolean): SceneTranslateInput {
        if (includeRuby) return input
        val dialogue = input.dialogue?.let { EmphasisText.decode(EmphasisText.transform(EmphasisText.encode(it, input.dialogueEmphasisRanges), ::mainTextOnly)) }
        val choices = input.choices.mapIndexed { i, text -> EmphasisText.decode(EmphasisText.transform(EmphasisText.encode(text, input.choiceEmphasisRanges.getOrElse(i) { emptyList() }), ::mainTextOnly)) }
        return input.copy(
            dialogue = dialogue?.text,
            choices = choices.map { it.text },
            dialogueEmphasisRanges = dialogue?.ranges.orEmpty(),
            choiceEmphasisRanges = if (input.choiceEmphasisRanges.isEmpty()) emptyList() else choices.map { it.ranges },
            // Old reference scenes must not reintroduce ruby after the user switches it off.
            previousDialogueContexts = input.previousDialogueContexts.map { context ->
                context.copy(
                    sourceDialogue = mainTextOnly(context.sourceDialogue),
                    translatedDialogue = context.translatedDialogue?.let(::mainTextOnly),
                    generalPreparedSourceDialogue = context.generalPreparedSourceDialogue?.let(::mainTextOnly)
                )
            }
        )
    }

    private fun mainTextOnly(text: String): String {
        if (!rubyMarkup.containsMatchIn(text)) return text
        val stripped = rubyMarkup.replace(text, "")
            .replace(repeatedSpaces, " ")
            .trim()
        // Preserve recognised text when an annotation has no base instead of sending a blank row.
        return stripped.ifBlank { text }
    }
}
