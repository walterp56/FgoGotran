package com.fgogotran.translation

import com.fgogotran.data.SettingsRepository

/** A readable JP -> CN prompt glossary entry shared by every translation model. */
internal data class TranslationGlossaryEntry(
    val source: String,
    val target: String,
    val note: String = ""
)

/**
 * Adds request-local mappings to the exact database terms selected by RAG.
 *
 * Gender is metadata only. It may help with explicitly stated references, but it
 * must never be treated as evidence for a subject or possessor omitted in JP.
 */
internal object TranslationGlossaryBuilder {
    private val bilingualSpeakerPattern = Regex("""^(.+?)\s*\(JP:\s*(.+?)\)\s*$""")
    private val maskTokens = listOf("???", "？？？", "■", "□", "▇", "█")

    fun build(
        sourceText: String,
        context: PromptContext,
        matchedEntries: List<TranslationGlossaryEntry>,
        currentSpeaker: String,
        includeConditionalMappings: Boolean = true,
        includeCurrentSpeaker: Boolean = true
    ): List<TranslationGlossaryEntry> {
        val entriesBySource = linkedMapOf<String, TranslationGlossaryEntry>()

        fun add(source: String, target: String, note: String = "") {
            val cleanSource = source.asGlossaryField()
            val cleanTarget = target.asGlossaryField()
            val cleanNote = note.asGlossaryField()
            if (cleanSource.isBlank() || cleanTarget.isBlank()) return
            val existing = entriesBySource[cleanSource]
            if (existing == null) {
                entriesBySource[cleanSource] =
                    TranslationGlossaryEntry(cleanSource, cleanTarget, cleanNote)
                return
            }
            if (existing.target == cleanTarget && cleanNote.isNotBlank()) {
                entriesBySource[cleanSource] = existing.copy(
                    note = mergeGlossaryNotes(existing.note, cleanNote)
                )
            }
        }

        matchedEntries.forEach { add(it.source, it.target, it.note) }
        if (includeConditionalMappings) {
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
                    HonorificPromptRule.CHAN -> "XXちゃん" to context.localized("XX酱", "XX醬")
                    HonorificPromptRule.TONO -> "XX殿" to context.localized("XX阁下", "XX閣下")
                    HonorificPromptRule.TAN -> "XXたん" to "XX炭"
                    HonorificPromptRule.TYA -> "XXてゃ" to context.localized("XX宝", "XX寶")
                    HonorificPromptRule.SAMA -> "XX様" to "XX大人"
                    HonorificPromptRule.SHI -> "XX氏" to "XX氏"
                    HonorificPromptRule.CCHI -> "XXっち" to "小XX"
                }
                val exceptions = match.presentExceptions
                    .takeIf(List<String>::isNotEmpty)
                    ?.joinToString("、")
                    ?.let { context.localized("；不用于$it", "；不用於$it") }
                    .orEmpty()
                add(mapping.first, mapping.second, context.localized("人名后缀", "人名後綴") + exceptions)
            }
            if (context.hasMasterWord) {
                val genderNote = context.playerGender.toGenderNote(context.targetChineseLocale)
                add(
                    "マスター",
                    "御主",
                    genderNote.takeIf(String::isNotBlank)
                        ?.let { "$it；${context.localized("玩家称谓", "玩家稱謂")}" }
                        .orEmpty()
                )
            }
            if (context.namePluralUsage.isPresent) {
                add(
                    "Xズ",
                    context.localized("X们", "X們"),
                    context.localized("角色群体词尾；普通词除外", "角色群體詞尾；普通詞除外")
                )
            }
            if (context.hasMasks) {
                maskTokens
                    .filter(sourceText::contains)
                    .forEach { mask ->
                        add(mask, mask, context.localized("遮蔽符号，保持不变", "遮蔽符號，保持不變"))
                    }
            }
        }
        if (includeCurrentSpeaker) {
            addCurrentSpeaker(
                entriesBySource = entriesBySource,
                currentSpeaker = currentSpeaker,
                currentSpeakerGender = context.currentSpeakerGender,
                characterContextPrompt = context.characterContextPrompt,
                targetChineseLocale = context.targetChineseLocale
            )
        }
        return entriesBySource.values.toList()
    }

    fun render(entries: List<TranslationGlossaryEntry>): String = buildString {
        entries.forEachIndexed { index, entry ->
            if (index > 0) appendLine()
            append(entry.source)
            append("->")
            append(entry.target)
            if (entry.note.isNotBlank()) {
                append(" #")
                append(entry.note)
            }
        }
    }

    private fun addCurrentSpeaker(
        entriesBySource: MutableMap<String, TranslationGlossaryEntry>,
        currentSpeaker: String,
        currentSpeakerGender: String,
        characterContextPrompt: String,
        targetChineseLocale: String
    ) {
        val cleanSpeaker = currentSpeaker.trim()
        if (cleanSpeaker.isBlank()) return
        val match = bilingualSpeakerPattern.matchEntire(cleanSpeaker)
        val source = (match?.groupValues?.get(2) ?: cleanSpeaker).asGlossaryField()
        val target = (match?.groupValues?.get(1) ?: source).asGlossaryField()
        if (source.isBlank() || target.isBlank()) return
        val traditional = SettingsRepository.normalizeTargetChineseLocale(targetChineseLocale) ==
            SettingsRepository.TARGET_LOCALE_TRADITIONAL
        val note = buildList {
            currentSpeakerGender
                .toGenderNote(targetChineseLocale)
                .takeIf(String::isNotBlank)
                ?.let(::add)
            add(if (traditional) "當前說話人" else "当前说话人")
            characterContextPrompt
                .asGlossaryField()
                .takeIf(String::isNotBlank)
                ?.let(::add)
        }.joinToString("；")
        val existing = entriesBySource[source]
        entriesBySource[source] = if (existing == null) {
            TranslationGlossaryEntry(source, target, note)
        } else {
            existing.copy(note = mergeGlossaryNotes(existing.note, note))
        }
    }

    private fun PromptContext.localized(simplified: String, traditional: String): String {
        return if (SettingsRepository.normalizeTargetChineseLocale(targetChineseLocale) ==
            SettingsRepository.TARGET_LOCALE_TRADITIONAL
        ) {
            traditional
        } else {
            simplified
        }
    }

    private fun String.toGenderNote(targetChineseLocale: String): String {
        val traditional = SettingsRepository.normalizeTargetChineseLocale(targetChineseLocale) ==
            SettingsRepository.TARGET_LOCALE_TRADITIONAL
        return when (trim()) {
            "女性", "female" -> "女性"
            "男性", "male" -> "男性"
            "性別不明", "性别不明" -> if (traditional) "性別不明" else "性别不明"
            else -> ""
        }
    }

    private fun mergeGlossaryNotes(vararg notes: String): String = notes
        .flatMap { note -> note.split('；') }
        .map(String::trim)
        .filter(String::isNotBlank)
        .distinct()
        .joinToString("；")

    private fun String.asGlossaryField(): String = lineSequence()
        .map(String::trim)
        .filter(String::isNotBlank)
        .joinToString(" ")
        .replace("->", "→")
        .replace('#', '＃')
}
