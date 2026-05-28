package com.fgogotran.translation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SessionTranslationEntry(
    val speakerName: String? = null,
    val dialogueText: String? = null,
    val choices: List<String> = emptyList(),
    val speakerNameColor: Int? = null,
    val dialogueTextColor: Int? = null,
    val choiceColors: List<Int?> = emptyList(),
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * In-memory history for the current service run only.
 */
object SessionTranslationHistory {
    private val _entries = MutableStateFlow<List<SessionTranslationEntry>>(emptyList())
    val entries: StateFlow<List<SessionTranslationEntry>> = _entries.asStateFlow()

    fun add(entry: SessionTranslationEntry) {
        if (_entries.value.lastOrNull()?.contentKey() == entry.contentKey()) return
        _entries.value = (_entries.value + entry).takeLast(100)
    }

    fun clear() {
        _entries.value = emptyList()
    }

    private fun SessionTranslationEntry.contentKey(): String {
        return listOf(
            speakerName.orEmpty().trim(),
            dialogueText.orEmpty().trim(),
            choices.joinToString("\n") { it.trim() }
        ).joinToString("\n").trim()
    }
}
