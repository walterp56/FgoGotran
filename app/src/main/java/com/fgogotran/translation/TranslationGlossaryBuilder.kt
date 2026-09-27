package com.fgogotran.translation

import com.fgogotran.data.SettingsRepository

/** A readable JP -> target prompt glossary entry shared by every translation model. */
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
        val englishTarget = SettingsRepository.normalizeTargetLanguage(context.targetLanguage) ==
            SettingsRepository.TARGET_LANGUAGE_ENGLISH
        // Literal mappings live in the glossary; pattern rules (honorifics, name plurals)
        // stay in the system-prompt cards so each policy has a single home.
        if (!englishTarget) {
            context.specialFirstPersonMappings.forEach { mapping ->
                add(mapping.sourceForm, mapping.targetTranslation, "第一人称")
            }
            context.specialSecondPersonMappings.forEach { mapping ->
                add(mapping.sourceForm, mapping.targetTranslation, "第二人称")
            }
        }
        if (context.hasMasterWord) {
            val genderNote = context.playerGender.toGenderNote(context.targetLanguage)
            add(
                "マスター",
                if (englishTarget) "Master" else "御主",
                genderNote.takeIf(String::isNotBlank)
                    ?.let {
                        if (englishTarget) "$it; player title"
                        else "$it；${context.localized("玩家称谓", "玩家稱謂")}"
                    }
                    .orEmpty()
            )
        }
        if (context.hasMasks) {
            maskTokens
                .filter(sourceText::contains)
                .forEach { mask ->
                    add(
                        mask,
                        mask,
                        if (englishTarget) "mask token, keep unchanged"
                        else context.localized("遮蔽符号，保持不变", "遮蔽符號，保持不變")
                    )
                }
        }
        if (includeCurrentSpeaker) {
            addCurrentSpeaker(
                entriesBySource = entriesBySource,
                currentSpeaker = currentSpeaker,
                currentSpeakerGender = context.currentSpeakerGender,
                characterContextPrompt = context.characterContextPrompt,
                targetLanguage = context.targetLanguage
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
        targetLanguage: String
    ) {
        val cleanSpeaker = currentSpeaker.trim()
        if (cleanSpeaker.isBlank()) return
        val match = bilingualSpeakerPattern.matchEntire(cleanSpeaker)
        val source = (match?.groupValues?.get(2) ?: cleanSpeaker).asGlossaryField()
        val target = (match?.groupValues?.get(1) ?: source).asGlossaryField()
        if (source.isBlank() || target.isBlank()) return
        val normalizedTarget = SettingsRepository.normalizeTargetLanguage(targetLanguage)
        val traditional = normalizedTarget == SettingsRepository.TARGET_LANGUAGE_TRADITIONAL
        val english = normalizedTarget == SettingsRepository.TARGET_LANGUAGE_ENGLISH
        val note = buildList {
            currentSpeakerGender
                .toGenderNote(targetLanguage)
                .takeIf(String::isNotBlank)
                ?.let(::add)
            add(
                when {
                    english -> "current speaker"
                    traditional -> "當前說話人"
                    else -> "当前说话人"
                }
            )
            characterContextPrompt
                .asGlossaryField()
                .takeIf(String::isNotBlank)
                ?.let(::add)
        }.joinToString(if (english) "; " else "；")
        val existing = entriesBySource[source]
        entriesBySource[source] = if (existing == null) {
            TranslationGlossaryEntry(source, target, note)
        } else {
            existing.copy(note = mergeGlossaryNotes(existing.note, note))
        }
    }

    private fun PromptContext.localized(simplified: String, traditional: String): String {
        return if (SettingsRepository.normalizeTargetLanguage(targetLanguage) ==
            SettingsRepository.TARGET_LANGUAGE_TRADITIONAL
        ) {
            traditional
        } else {
            simplified
        }
    }

    /** Gender note for prompt glossary entries; English targets get English note words. */
    internal fun genderNoteFor(gender: String, targetLanguage: String): String =
        gender.toGenderNote(targetLanguage)

    private fun String.toGenderNote(targetLanguage: String): String {
        val normalizedTarget = SettingsRepository.normalizeTargetLanguage(targetLanguage)
        val english = normalizedTarget == SettingsRepository.TARGET_LANGUAGE_ENGLISH
        val traditional = normalizedTarget == SettingsRepository.TARGET_LANGUAGE_TRADITIONAL
        return when (trim()) {
            "女性", "female" -> if (english) "female" else "女性"
            "男性", "male" -> if (english) "male" else "男性"
            "性別不明", "性别不明", "unspecified" -> when {
                english -> "unspecified"
                traditional -> "性別不明"
                else -> "性别不明"
            }
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
