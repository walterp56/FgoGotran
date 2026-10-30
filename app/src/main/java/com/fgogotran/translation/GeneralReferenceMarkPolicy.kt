package com.fgogotran.translation

/** Consecutive story-obscuration marks only; a single footnote ※ keeps ordinary semantics. */
internal object GeneralReferenceMarkPolicy {
    const val CACHE_VERSION = "general-reference-marks-v1"
    private val runs = Regex("※{2,}")

    fun containsRun(text: String): Boolean = '※' in text && runs.containsMatchIn(text)

    fun isPreserved(source: String, translation: String): Boolean {
        if (!containsRun(source)) return true
        // Include isolated marks in the comparison: splitting a run into single marks is unsafe.
        return source.filter { it == '※' }.length == translation.count { it == '※' } &&
            runs.findAll(source).map { it.value.length }.toList() ==
            runs.findAll(translation).map { it.value.length }.toList()
    }
}

/** All call sites are general request builders; shared/Sakura glossary assembly stays unchanged. */
internal fun StringBuilder.appendGeneralReferenceMarkSection(sources: List<String>) {
    if (sources.none(GeneralReferenceMarkPolicy::containsRun)) return
    appendPromptSectionText(
        "Preserve each consecutive ※ run exactly, including its count; do not infer hidden words."
    )
}
