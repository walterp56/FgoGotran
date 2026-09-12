package com.fgogotran.translation

/**
 * SakuraLLM prompt adapter.
 *
 * Sakura is a specialist translation model rather than a general instruction model.
 * Keep its official system/user wording intact and express FGO-specific guidance only
 * through the JP->CN glossary format used during Sakura training.
 */
internal object SakuraPromptBuilder {
    const val PROMPT_VERSION = "sakura-official-v7-concrete-glossary"

    private const val MODEL_MARKER = "Sakura"
    private const val SIMPLE_TRANSLATION_PREFIX = "将下面的日文文本翻译成中文："
    private const val GLOSSARY_HEADER = "根据以下术语表（可以为空）："
    private const val GLOSSARY_TRANSLATION_PREFIX =
        "将下面的日文文本根据对应关系和备注翻译成中文："

    private val systemPrompt =
        "你是一个轻小说翻译模型，可以流畅通顺地以日本轻小说的风格将日文翻译成简体中文，" +
            "并联系上下文正确使用人称代词，不擅自添加原文中没有的代词。"

    /** Model IDs are user/provider supplied, so Sakura matching is case-insensitive. */
    fun matchesModel(apiModel: String): Boolean = apiModel.contains(MODEL_MARKER, ignoreCase = true)

    fun buildSystemPrompt(): String = systemPrompt

    fun buildSingleRequest(
        japaneseText: String,
        previousDialogueContexts: List<SceneDialogueContext>,
        currentSpeaker: String,
        context: PromptContext,
        glossaryEntries: List<TranslationGlossaryEntry> = emptyList(),
        translateAsChoices: Boolean = false,
        translateAsName: Boolean = false,
        retryStage: Int = 0
    ): SakuraPromptRequest {
        val currentLines = japaneseText.asSourceLines()
        val includePreviousContext = retryStage == 0 &&
            context.isDialogue &&
            !translateAsChoices &&
            !translateAsName
        val previousLines = if (includePreviousContext) {
            previousDialogueContexts.flatMap { it.sourceDialogue.asSourceLines() }
        } else {
            emptyList()
        }
        val sourceText = (previousLines + currentLines).joinToString("\n")
        val entries = buildSakuraGlossary(
            sourceText = japaneseText,
            context = context,
            matchedEntries = glossaryEntries,
            currentSpeaker = currentSpeaker
        )
        return SakuraPromptRequest(
            userPrompt = buildOfficialUserPrompt(sourceText, entries),
            previousSourceLineCount = previousLines.size,
            currentSourceLineCount = currentLines.size
        )
    }

    fun buildBatchUserPrompt(
        texts: List<String>,
        currentSpeaker: String,
        context: PromptContext,
        glossaryEntries: List<TranslationGlossaryEntry> = emptyList()
    ): String {
        val sourceText = texts.joinToString("\n") { text ->
            text.asSourceLines().joinToString("\n")
        }
        val entries = buildSakuraGlossary(
            sourceText = sourceText,
            context = context,
            matchedEntries = glossaryEntries,
            currentSpeaker = currentSpeaker
        )
        return buildOfficialUserPrompt(sourceText, entries)
    }

    fun buildCropUserPrompt(
        japaneseText: String,
        context: PromptContext,
        glossaryEntries: List<TranslationGlossaryEntry> = emptyList()
    ): String {
        val sourceText = japaneseText.asSourceLines().joinToString("\n")
        val entries = buildSakuraGlossary(
            sourceText = sourceText,
            context = context,
            matchedEntries = glossaryEntries,
            currentSpeaker = ""
        )
        return buildOfficialUserPrompt(sourceText, entries)
    }

    private fun buildSakuraGlossary(
        sourceText: String,
        context: PromptContext,
        matchedEntries: List<TranslationGlossaryEntry>,
        currentSpeaker: String
    ): List<TranslationGlossaryEntry> = TranslationGlossaryBuilder.build(
        sourceText = sourceText,
        context = context,
        matchedEntries = matchedEntries,
        currentSpeaker = currentSpeaker,
        includeHonorificTemplates = false,
        includeNamePluralTemplate = false
    )

    private fun buildOfficialUserPrompt(
        sourceText: String,
        entries: List<TranslationGlossaryEntry>
    ): String {
        if (entries.isEmpty()) return SIMPLE_TRANSLATION_PREFIX + sourceText
        return buildString {
            appendLine(GLOSSARY_HEADER)
            appendLine(TranslationGlossaryBuilder.render(entries))
            append(GLOSSARY_TRANSLATION_PREFIX)
            append(sourceText)
        }.trim()
    }

    private fun String.asSourceLines(): List<String> = lineSequence()
        .map(String::trim)
        .filter(String::isNotBlank)
        .toList()

}

internal data class SakuraPromptRequest(
    val userPrompt: String,
    val previousSourceLineCount: Int,
    val currentSourceLineCount: Int
) {
    fun extractCurrentTranslation(modelText: String): String? {
        if (previousSourceLineCount <= 0) return modelText
        val cleaned = modelText.trim()
            .replace("\\r\\n", "\n")
            .replace("\\n", "\n")
            .replace("\\r", "\n")
        val lines = cleaned.lineSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .toList()
        val expectedLineCount = previousSourceLineCount + currentSourceLineCount
        if (lines.size != expectedLineCount) return null
        return lines.takeLast(currentSourceLineCount).joinToString("\n")
    }
}
