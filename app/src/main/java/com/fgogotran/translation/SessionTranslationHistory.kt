package com.fgogotran.translation

import com.fgogotran.data.SettingsRepository
import com.fgogotran.util.FgoLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SessionTranslationEntry(
    val speakerName: String? = null,
    val dialogueText: String? = null,
    val originalDialogueText: String? = null,
    val contextSourceSpeakerName: String? = null,
    val contextTranslatedSpeakerName: String? = null,
    val contextSourceDialogue: String? = null,
    val contextTranslatedDialogue: String? = null,
    val choices: List<String> = emptyList(),
    val originalChoices: List<String?> = emptyList(),
    val speakerNameColor: Int? = null,
    val dialogueTextColor: Int? = null,
    val choiceColors: List<Int?> = emptyList(),
    val targetLocale: String = SettingsRepository.TARGET_LOCALE_SIMPLIFIED,
    val sourceKey: String = "",
    val dialogueSourceKey: String = "",
    val contextDialogueTranslationTrusted: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val historyId: Long = 0L,
    val battleOccurrence: Boolean = false
)

/** Reserves source order without putting unfinished/failed translation text in the LOG. */
data class BattleHistoryReservation internal constructor(
    internal val session: Long,
    internal val order: Long,
    internal val sourceKey: String,
    internal val original: String,
    internal val createdAt: Long
)

/**
 * In-memory history for the current service run only.
 */
object SessionTranslationHistory {
    private const val TAG = "SessionHistory"
    internal const val DEFAULT_SCENE_DIALOGUE_CONTEXT_LIMIT = 2

    private val _entries = MutableStateFlow<List<SessionTranslationEntry>>(emptyList())
    val entries: StateFlow<List<SessionTranslationEntry>> = _entries.asStateFlow()
    private var session = 0L
    private var nextOrder = 0L

    @Synchronized
    fun add(entry: SessionTranslationEntry) {
        val currentEntries = _entries.value
        val originalKey = entry.contentKey()
        if (originalKey.isBlank()) return
        if (currentEntries.lastOrNull()?.let { !it.battleOccurrence && it.contentKey() == originalKey } == true) {
            FgoLogger.debug(TAG, "History duplicate skipped")
            return
        }

        val previousDialogueEntry = currentEntries
            .asReversed()
            .firstOrNull { !it.battleOccurrence && it.dialogueKey().isNotBlank() }
        val normalizedEntry = entry.withoutRepeatedDialogueAfter(previousDialogueEntry)
        val key = normalizedEntry.contentKey()
        if (key.isBlank()) return
        if (normalizedEntry.shouldUpdateLatestSameSource(currentEntries.lastOrNull())) {
            FgoLogger.debug(TAG, "History latest same-source entry updated")
            _entries.value = currentEntries.dropLast(1) + normalizedEntry.copy(historyId = currentEntries.last().historyId)
            return
        }
        if (currentEntries.lastOrNull()?.let { !it.battleOccurrence && it.contentKey() == key } == true) {
            FgoLogger.debug(TAG, "History duplicate skipped")
            return
        }
        _entries.value = currentEntries + normalizedEntry.copy(historyId = ++nextOrder)
    }

    @Synchronized
    fun reserveBattleEntry(sourceKey: String, original: String): BattleHistoryReservation =
        BattleHistoryReservation(session, ++nextOrder, sourceKey, original, System.currentTimeMillis())

    @Synchronized
    fun completeBattleEntry(reservation: BattleHistoryReservation, translation: String, targetLocale: String): Boolean {
        if (reservation.session != session || translation.isBlank()) return false
        val current = _entries.value
        if (current.any { it.historyId == reservation.order }) return false
        val entry = SessionTranslationEntry(
            dialogueText = translation,
            originalDialogueText = reservation.original,
            targetLocale = targetLocale,
            sourceKey = reservation.sourceKey,
            createdAt = reservation.createdAt,
            historyId = reservation.order,
            battleOccurrence = true
        )
        // API B may finish before A. Insert by reserved occurrence order, including among story entries.
        val index = current.binarySearchBy(reservation.order) { it.historyId }.let { if (it < 0) -it - 1 else it }
        _entries.value = current.toMutableList().apply { add(index, entry) }
        return true
    }

    @Synchronized
    fun clear() {
        session++ // Invalidate reservations even if an old network callback survives cancellation.
        _entries.value = emptyList()
    }

    fun lastSceneDialogueContexts(
        limit: Int = DEFAULT_SCENE_DIALOGUE_CONTEXT_LIMIT,
        excludeDialogueSourceKey: String = ""
    ): List<SceneDialogueContext> {
        val excludeKey = excludeDialogueSourceKey.normalizeHistoryText()
        return _entries.value
            .asReversed()
            .asSequence()
            .filter { entry ->
                val sourceDialogue = entry.contextSourceDialogue?.trim()
                sourceDialogue != null && sourceDialogue.isNotBlank()
            }
            .filter { entry ->
                excludeKey.isBlank() || entry.normalizedDialogueSourceKey() != excludeKey
            }
            .take(limit)
            .map { entry ->
                val sourceSpeakerName = entry.contextSourceSpeakerName
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                val translatedSpeakerName = if (sourceSpeakerName != null) {
                    entry.contextTranslatedSpeakerName
                        ?.trim()
                        ?.takeIf { it.isNotBlank() && !it.isHistoryErrorText() }
                } else {
                    null
                }
                val translatedDialogue = if (entry.contextDialogueTranslationTrusted) {
                    entry.contextTranslatedDialogue
                        ?.trim()
                        ?.takeIf { it.isNotBlank() && !it.isHistoryErrorText() }
                } else {
                    null
                }
                SceneDialogueContext(
                    sourceSpeakerName = sourceSpeakerName,
                    translatedSpeakerName = translatedSpeakerName,
                    sourceDialogue = entry.contextSourceDialogue!!.trim(),
                    translatedDialogue = translatedDialogue,
                    targetLocale = SettingsRepository.normalizeTargetChineseLocale(entry.targetLocale),
                    dialogueSourceKey = entry.normalizedDialogueSourceKey()
                )
            }
            .toList()
            .asReversed()
    }

    private fun SessionTranslationEntry.contentKey(): String {
        return listOf(
            speakerName.orEmpty(),
            dialogueText.orEmpty(),
            originalDialogueText.orEmpty(),
            contextSourceSpeakerName.orEmpty(),
            contextTranslatedSpeakerName.orEmpty(),
            contextSourceDialogue.orEmpty(),
            contextTranslatedDialogue.orEmpty(),
            choices.joinToString("\n"),
            originalChoices.joinToString("\n") { it.orEmpty() },
            contextDialogueTranslationTrusted.toString()
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
        if (previous.battleOccurrence) return false
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
            originalDialogueText = null,
            contextSourceSpeakerName = null,
            contextTranslatedSpeakerName = null,
            contextSourceDialogue = null,
            contextTranslatedDialogue = null,
            speakerNameColor = null,
            dialogueTextColor = null,
            contextDialogueTranslationTrusted = false
        )
    }

    private fun SessionTranslationEntry.dialogueKey(): String {
        return listOf(
            speakerName.orEmpty(),
            dialogueText.orEmpty(),
            originalDialogueText.orEmpty(),
            contextSourceSpeakerName.orEmpty(),
            contextTranslatedSpeakerName.orEmpty(),
            contextSourceDialogue.orEmpty(),
            contextTranslatedDialogue.orEmpty()
        )
            .joinToString("\n")
            .normalizeHistoryText()
    }

    private fun String.isHistoryErrorText(): Boolean {
        val text = trim()
        return text.startsWith("[未配置 API Key]") ||
            text.startsWith("[翻译失败") ||
            text.startsWith("[翻譯失敗") ||
            text == "翻译失败" ||
            text == "翻譯失敗"
    }
}
