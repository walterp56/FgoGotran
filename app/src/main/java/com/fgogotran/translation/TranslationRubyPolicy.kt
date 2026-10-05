package com.fgogotran.translation

/** Request-only ruby filtering; the original OCR source and session history stay intact. */
object TranslationRubyPolicy {
    private val rubyMarkup = Regex("〈[^〉]*〉")
    private val repeatedSpaces = Regex("[ \\t]{2,}")

    fun prepare(input: SceneTranslateInput, includeRuby: Boolean): SceneTranslateInput {
        if (includeRuby) return input
        return input.copy(
            dialogue = input.dialogue?.let(::mainTextOnly),
            choices = input.choices.map(::mainTextOnly),
            // Old reference scenes must not reintroduce ruby after the user switches it off.
            previousDialogueContexts = input.previousDialogueContexts.map { context ->
                context.copy(
                    sourceDialogue = mainTextOnly(context.sourceDialogue),
                    translatedDialogue = context.translatedDialogue?.let(::mainTextOnly)
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
