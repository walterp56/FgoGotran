package com.fgogotran.translation

import com.fgogotran.util.FgoLogger
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
    val sourceKey: String = "",
    val dialogueSourceKey: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * In-memory history for the current service run only.
 */
object SessionTranslationHistory {
    private const val TAG = "SessionHistory"

    private val _entries = MutableStateFlow<List<SessionTranslationEntry>>(emptyList())
    val entries: StateFlow<List<SessionTranslationEntry>> = _entries.asStateFlow()

    fun add(entry: SessionTranslationEntry) {
        val currentEntries = _entries.value
        val originalKey = entry.contentKey()
        if (originalKey.isBlank()) return
        if (currentEntries.lastOrNull()?.contentKey() == originalKey) {
            FgoLogger.debug(TAG, "History duplicate skipped")
            return
        }

        val previousDialogueEntry = currentEntries
            .asReversed()
            .firstOrNull { it.dialogueKey().isNotBlank() }
        val normalizedEntry = entry.withoutRepeatedDialogueAfter(previousDialogueEntry)
        val key = normalizedEntry.contentKey()
        if (key.isBlank()) return
        if (normalizedEntry.shouldUpdateLatestSameSource(currentEntries.lastOrNull())) {
            FgoLogger.debug(TAG, "History latest same-source entry updated")
            _entries.value = currentEntries.dropLast(1) + normalizedEntry
            return
        }
        if (currentEntries.lastOrNull()?.contentKey() == key) {
            FgoLogger.debug(TAG, "History duplicate skipped")
            return
        }
        _entries.value = (currentEntries + normalizedEntry).takeLast(100)
    }

    fun clear() {
        _entries.value = emptyList()
    }

    private fun SessionTranslationEntry.contentKey(): String {
        return listOf(
            speakerName.orEmpty(),
            dialogueText.orEmpty(),
            choices.joinToString("\n")
        )
            .joinToString("\n")
            .normalizeHistoryText()
    }

    private fun String.normalizeHistoryText(): String {
        return lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .joinToString("\n")
            .replace(Regex("""[ \t　]+"""), " ")
            .trim()
    }

    private fun SessionTranslationEntry.normalizedSourceKey(): String {
        return sourceKey.normalizeHistoryText()
    }

    private fun SessionTranslationEntry.normalizedDialogueSourceKey(): String {
        return dialogueSourceKey
            .normalizeHistoryText()
            .ifBlank { normalizedSourceKey() }
    }

    private fun SessionTranslationEntry.shouldUpdateLatestSameSource(
        previous: SessionTranslationEntry?
    ): Boolean {
        if (previous == null) return false
        if (choices.isNotEmpty() != previous.choices.isNotEmpty()) return false
        if (choices.isEmpty() && (dialogueText.isNullOrBlank() || previous.dialogueText.isNullOrBlank())) {
            return false
        }

        val currentSourceKey = normalizedSourceKey()
        return currentSourceKey.isNotBlank() && currentSourceKey == previous.normalizedSourceKey()
    }

    private fun SessionTranslationEntry.withoutRepeatedDialogueAfter(
        previous: SessionTranslationEntry?
    ): SessionTranslationEntry {
        if (previous == null || choices.isEmpty()) return this

        val currentDialogueKey = dialogueKey()
        val currentDialogueSourceKey = normalizedDialogueSourceKey()
        val repeatsPreviousDialogue =
            (currentDialogueSourceKey.isNotBlank() &&
                currentDialogueSourceKey == previous.normalizedDialogueSourceKey()) ||
                (currentDialogueKey.isNotBlank() && currentDialogueKey == previous.dialogueKey())
        if (!repeatsPreviousDialogue) return this

        FgoLogger.debug(TAG, "History repeated dialogue omitted from choice entry")
        return copy(
            speakerName = null,
            dialogueText = null,
            speakerNameColor = null,
            dialogueTextColor = null
        )
    }

    private fun SessionTranslationEntry.dialogueKey(): String {
        return listOf(
            speakerName.orEmpty(),
            dialogueText.orEmpty()
        )
            .joinToString("\n")
            .normalizeHistoryText()
    }
}
