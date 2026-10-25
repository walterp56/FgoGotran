package com.fgogotran.translation

/** Source-only snapshots never wait for, or inherit the identity of, a previous API completion. */
internal fun prepareDialogueSubtitleReferences(sources: List<String>): List<String> {
    val selected = sources.takeLast(2).map(String::trim).filter(String::isNotEmpty)
    val output = ArrayDeque<String>()
    var characters = 0
    for (source in selected.asReversed()) {
        // Omit an over-budget reference rather than cutting its sentence in half.
        if (characters + source.length <= 320) { output.addFirst(source); characters += source.length }
    }
    return output.toList()
}

internal fun buildDialogueSubtitleReferencePrompt(sources: List<String>): String =
    if (sources.isEmpty()) "" else buildPromptSection(
        "reference_context",
        "Previous Japanese from this playback is reference only; translate only the current Japanese. " +
            "Do not assume the same speaker.\n" + sources.joinToString("\n")
    )

/** Canonical cached/output rows. Never invent alignment by splitting a translated sentence. */
internal fun dialogueSubtitleTranslatedRows(text: String, expectedCount: Int): List<String>? {
    if (expectedCount <= 0) return null
    if (expectedCount == 1) return listOf(text.lineSequence().map(String::trim)
        .filter(String::isNotEmpty).joinToString(" ")).takeIf { it.single().isNotBlank() }
    return text.replace("\r\n", "\n").replace('\r', '\n').trim().lines().map(String::trim)
        .takeIf { it.size == expectedCount && it.all(String::isNotBlank) }
}
