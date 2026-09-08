package com.fgogotran.translation

/**
 * SakuraLLM prompt adapter.
 *
 * Sakura is a specialist translation model rather than a general instruction model.
 * Keep its official system/user wording intact and express FGO-specific guidance only
 * through the JP->CN glossary format used during Sakura training.
 */
internal object SakuraPromptBuilder {
    const val PROMPT_VERSION = "sakura-official-v5-gender"

    private const val MODEL_MARKER = "Sakura"
    private const val SIMPLE_TRANSLATION_PREFIX = "将下面的日文文本翻译成中文："
    private const val GLOSSARY_HEADER = "根据以下术语表（可以为空）："
    private const val GLOSSARY_TRANSLATION_PREFIX =
        "将下面的日文文本根据对应关系和备注翻译成中文："

    private val bilingualSpeakerPattern = Regex("""^(.+?)\s*\(JP:\s*(.+?)\)\s*$""")
    private val maskTokens = listOf("???", "？？？", "■", "□", "▇", "█")

    private val systemPrompt =
        "你是一个轻小说翻译模型，可以流畅通顺地以日本轻小说的风格将日文翻译成简体中文，" +
            "并联系上下文正确使用人称代词，不擅自添加原文中没有的代词。"

    /** Deliberately case-sensitive: Sakura matches, sakura and SAKURA do not. */
    fun matchesModel(apiModel: String): Boolean = apiModel.contains(MODEL_MARKER)

    fun buildSystemPrompt(): String = systemPrompt

    fun buildSingleRequest(
        japaneseText: String,
        previousDialogueContexts: List<SceneDialogueContext>,
        currentSpeaker: String,
        context: PromptContext,
        glossaryEntries: List<SakuraGlossaryEntry> = emptyList(),
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
        val entries = buildGlossaryEntries(
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
        glossaryEntries: List<SakuraGlossaryEntry> = emptyList()
    ): String {
        val sourceText = texts.joinToString("\n") { text ->
            text.asSourceLines().joinToString("\n")
        }
        val entries = buildGlossaryEntries(
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
        glossaryEntries: List<SakuraGlossaryEntry> = emptyList()
    ): String {
        val sourceText = japaneseText.asSourceLines().joinToString("\n")
        val entries = buildGlossaryEntries(
            sourceText = sourceText,
            context = context,
            matchedEntries = glossaryEntries,
            currentSpeaker = ""
        )
        return buildOfficialUserPrompt(sourceText, entries)
    }

    private fun buildOfficialUserPrompt(
        sourceText: String,
        entries: List<SakuraGlossaryEntry>
    ): String {
        if (entries.isEmpty()) return SIMPLE_TRANSLATION_PREFIX + sourceText
        return buildString {
            appendLine(GLOSSARY_HEADER)
            entries.forEach { entry ->
                append(entry.source)
                append("->")
                append(entry.target)
                if (entry.note.isNotBlank()) {
                    append(" #")
                    append(entry.note)
                }
                appendLine()
            }
            append(GLOSSARY_TRANSLATION_PREFIX)
            append(sourceText)
        }.trim()
    }

    private fun buildGlossaryEntries(
        sourceText: String,
        context: PromptContext,
        matchedEntries: List<SakuraGlossaryEntry>,
        currentSpeaker: String
    ): List<SakuraGlossaryEntry> {
        val entriesBySource = linkedMapOf<String, SakuraGlossaryEntry>()
        fun add(source: String, target: String, note: String = "") {
            val cleanSource = source.asGlossaryField()
            val cleanTarget = target.asGlossaryField()
            val cleanNote = note.asGlossaryField()
            if (cleanSource.isBlank() || cleanTarget.isBlank()) return
            val existing = entriesBySource[cleanSource]
            if (existing == null) {
                entriesBySource[cleanSource] =
                    SakuraGlossaryEntry(cleanSource, cleanTarget, cleanNote)
                return
            }
            if (existing.target == cleanTarget && cleanNote.isNotBlank()) {
                entriesBySource[cleanSource] = existing.copy(
                    note = mergeGlossaryNotes(existing.note, cleanNote)
                )
            }
        }

        matchedEntries.forEach { add(it.source, it.target, it.note) }
        context.specialFirstPersonMappings.forEach { mapping ->
            add(mapping.sourceForm, mapping.targetTranslation, "第一人称")
        }
        context.specialSecondPersonMappings.forEach { mapping ->
            add(mapping.sourceForm, mapping.targetTranslation, "第二人称")
        }
        context.honorificMatches.forEach { match ->
            val mapping = when (match.rule) {
                HonorificPromptRule.SAN -> "XXさん" to "XX桑"
                HonorificPromptRule.KUN -> "XXくん" to "XX君"
                HonorificPromptRule.CHAN -> "XXちゃん" to "XX酱"
                HonorificPromptRule.TONO -> "XX殿" to "XX阁下"
                HonorificPromptRule.TAN -> "XXたん" to "XX炭"
                HonorificPromptRule.TYA -> "XXてゃ" to "XX宝"
                HonorificPromptRule.SAMA -> "XX様" to "XX大人"
                HonorificPromptRule.SHI -> "XX氏" to "XX氏"
                HonorificPromptRule.CCHI -> "XXっち" to "小XX"
            }
            val exceptions = match.presentExceptions
                .takeIf(List<String>::isNotEmpty)
                ?.joinToString("、")
                ?.let { "；不用于$it" }
                .orEmpty()
            add(mapping.first, mapping.second, "人名后缀$exceptions")
        }
        if (context.hasMasterWord) add("マスター", "御主")
        if (context.namePluralUsage.isPresent) {
            add("Xズ", "X们", "角色群体词尾；普通词除外")
        }
        if (context.hasMasks) {
            maskTokens
                .filter(sourceText::contains)
                .forEach { mask -> add(mask, mask, "遮蔽符号，保持不变") }
        }
        addCurrentSpeaker(
            entriesBySource,
            currentSpeaker,
            context.currentSpeakerGender,
            context.characterContextPrompt
        )
        return entriesBySource.values.toList()
    }

    private fun addCurrentSpeaker(
        entriesBySource: MutableMap<String, SakuraGlossaryEntry>,
        currentSpeaker: String,
        currentSpeakerGender: String,
        characterContextPrompt: String
    ) {
        val cleanSpeaker = currentSpeaker.trim()
        if (cleanSpeaker.isBlank()) return
        val match = bilingualSpeakerPattern.matchEntire(cleanSpeaker)
        val source = (match?.groupValues?.get(2) ?: cleanSpeaker).asGlossaryField()
        val target = (match?.groupValues?.get(1) ?: source).asGlossaryField()
        if (source.isBlank() || target.isBlank()) return
        val note = buildList {
            currentSpeakerGender
                .toSakuraGenderNote()
                .takeIf(String::isNotBlank)
                ?.let(::add)
            add("当前说话人")
            characterContextPrompt
                .asGlossaryField()
                .takeIf(String::isNotBlank)
                ?.let(::add)
        }.joinToString("；")
        val existing = entriesBySource[source]
        entriesBySource[source] = if (existing == null) {
            SakuraGlossaryEntry(source, target, note)
        } else {
            existing.copy(
                note = mergeGlossaryNotes(existing.note, note)
            )
        }
    }

    private fun mergeGlossaryNotes(vararg notes: String): String = notes
        .flatMap { note -> note.split('；') }
        .map(String::trim)
        .filter(String::isNotBlank)
        .distinct()
        .joinToString("；")

    private fun String.asSourceLines(): List<String> = lineSequence()
        .map(String::trim)
        .filter(String::isNotBlank)
        .toList()

    private fun String.asGlossaryField(): String = lineSequence()
        .map(String::trim)
        .filter(String::isNotBlank)
        .joinToString(" ")
        .replace("->", "→")
        .replace('#', '＃')

    private fun String.toSakuraGenderNote(): String = when (trim()) {
        "女性" -> "女性"
        "男性" -> "男性"
        "性別不明" -> "性别不明"
        else -> ""
    }
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

internal data class SakuraGlossaryEntry(
    val source: String,
    val target: String,
    val note: String = ""
)
