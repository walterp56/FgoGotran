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
    val targetLocale: String = SettingsRepository.TARGET_LANGUAGE_SIMPLIFIED,
    val sourceKey: String = "",
    val dialogueSourceKey: String = "",
    val contextDialogueTranslationTrusted: Boolean = true,
    val sceneContextGeneration: Long = SessionTranslationHistory.UNSPECIFIED_SCENE_CONTEXT_GENERATION,
    val createdAt: Long = System.currentTimeMillis(),
    val historyId: Long = 0L,
    val battleOccurrence: Boolean = false,
    val generalPreparedSourceDialogue: String? = null,
    val dialogueSubtitleOccurrence: Boolean = false
) {
    val subtitleOccurrence: Boolean get() = battleOccurrence || dialogueSubtitleOccurrence
}

/** Reserves source order before a translation or explicit failure record completes. */
data class BattleHistoryReservation internal constructor(
    internal val session: Long,
    internal val order: Long,
    internal val sourceKey: String,
    internal val original: String,
    internal val createdAt: Long,
    internal val dialogueTargetLocale: String? = null
) {
    // Request-local completion identity; duplicate LOG rows must not require retaining every ID.
    internal var dialogueLogCompleted = false
}

/**
 * In-memory history for the current service run only.
 */
object SessionTranslationHistory {
    private const val TAG = "SessionHistory"
    internal const val DEFAULT_SCENE_DIALOGUE_CONTEXT_LIMIT =
        SettingsRepository.DEFAULT_TRANSLATION_CONTEXT_SCENE_COUNT

    private val _entries = MutableStateFlow<List<SessionTranslationEntry>>(emptyList())
    val entries: StateFlow<List<SessionTranslationEntry>> = _entries.asStateFlow()
    private var session = 0L
    private var nextOrder = 0L
    private var sceneContextGeneration = 0L
    private data class DialogueLogKey(val original: String, val targetLocale: String)
    private enum class DialogueLogStatus { PENDING, FAILED, SUCCEEDED }
    private class DialogueLogSlot(val first: BattleHistoryReservation) {
        var status = DialogueLogStatus.PENDING
    }
    private val dialogueLogSlots = mutableMapOf<DialogueLogKey, DialogueLogSlot>()
    private val dialogueLogSpaces = Regex("[\\p{Zs}\\t]+")

    private fun dialogueLogKey(original: String, targetLocale: String): DialogueLogKey {
        val normalized = original.replace("\r\n", "\n").replace('\r', '\n')
            .lineSequence().map(String::trim).joinToString("")
            .replace(dialogueLogSpaces, " ").trim()
        return DialogueLogKey(normalized, SettingsRepository.normalizeTargetLanguage(targetLocale))
    }

    @Synchronized
    fun add(entry: SessionTranslationEntry) {
        val scopedEntry = if (entry.sceneContextGeneration == UNSPECIFIED_SCENE_CONTEXT_GENERATION) {
            entry.copy(sceneContextGeneration = sceneContextGeneration)
        } else {
            entry
        }
        val currentEntries = _entries.value
        val latestEntryInContextIndex = currentEntries.indexOfLast {
            it.sceneContextGeneration == scopedEntry.sceneContextGeneration
        }
        val latestEntryInContext = currentEntries.getOrNull(latestEntryInContextIndex)
        val originalKey = scopedEntry.contentKey()
        if (originalKey.isBlank()) return
        if (latestEntryInContext?.let { !it.subtitleOccurrence && it.contentKey() == originalKey } == true) {
            refreshGeneralPreparedSourceDialogue(currentEntries, latestEntryInContextIndex, scopedEntry)
            FgoLogger.debug(TAG, "History duplicate skipped")
            return
        }

        val previousDialogueEntry = currentEntries
            .asReversed()
            .firstOrNull {
                !it.subtitleOccurrence &&
                    it.sceneContextGeneration == scopedEntry.sceneContextGeneration &&
                    it.dialogueKey().isNotBlank()
            }
        val normalizedEntry = scopedEntry.withoutRepeatedDialogueAfter(previousDialogueEntry)
        val key = normalizedEntry.contentKey()
        if (key.isBlank()) return
        if (normalizedEntry.shouldUpdateLatestSameSource(latestEntryInContext)) {
            FgoLogger.debug(TAG, "History latest same-source entry updated")
            _entries.value = currentEntries.toMutableList().apply {
                this[latestEntryInContextIndex] = normalizedEntry.copy(
                    historyId = latestEntryInContext!!.historyId
                )
            }
            return
        }
        if (latestEntryInContext?.let { !it.subtitleOccurrence && it.contentKey() == key } == true) {
            refreshGeneralPreparedSourceDialogue(currentEntries, latestEntryInContextIndex, normalizedEntry)
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
        return completeSubtitleEntry(reservation, translation, targetLocale, battle = true)
    }

    @Synchronized
    fun reserveDialogueSubtitleEntry(sourceKey: String, original: String, targetLocale: String): BattleHistoryReservation {
        val key = dialogueLogKey(original, targetLocale)
        val reservation = BattleHistoryReservation(session, ++nextOrder, sourceKey, original,
            System.currentTimeMillis(), dialogueTargetLocale = key.targetLocale)
        if (key.original.isNotBlank()) dialogueLogSlots.getOrPut(key) { DialogueLogSlot(reservation) }
        return reservation
    }

    @Synchronized
    fun completeDialogueSubtitleEntry(
        reservation: BattleHistoryReservation, translation: String, targetLocale: String, isFailure: Boolean = false
    ): Boolean {
        if (reservation.session != session || reservation.dialogueLogCompleted || translation.isBlank()) return false
        val key = dialogueLogKey(reservation.original, targetLocale)
        if (reservation.dialogueTargetLocale != key.targetLocale) return false
        val slot = dialogueLogSlots[key] ?: return false
        reservation.dialogueLogCompleted = true
        if (slot.status == DialogueLogStatus.SUCCEEDED || (isFailure && slot.status == DialogueLogStatus.FAILED)) {
            FgoLogger.debug(TAG, "Summon LOG duplicate skipped")
            return false
        }
        val first = slot.first
        val entry = SessionTranslationEntry(
            dialogueText = translation,
            originalDialogueText = first.original,
            targetLocale = key.targetLocale,
            sourceKey = first.sourceKey,
            createdAt = first.createdAt,
            historyId = first.order,
            dialogueSubtitleOccurrence = true
        )
        val current = _entries.value
        val index = current.binarySearchBy(first.order) { it.historyId }
        slot.status = if (isFailure) DialogueLogStatus.FAILED else DialogueLogStatus.SUCCEEDED
        // A later duplicate may finish first. Its LOG row still belongs at the first capture's
        // position; a recovered failure keeps that same ID/timestamp and the user's scroll anchor.
        _entries.value = current.toMutableList().apply {
            if (index >= 0) this[index] = entry else add(-index - 1, entry)
        }
        return true
    }

    private fun completeSubtitleEntry(reservation: BattleHistoryReservation, translation: String, targetLocale: String, battle: Boolean): Boolean {
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
            battleOccurrence = battle,
            dialogueSubtitleOccurrence = !battle
        )
        // API B may finish before A. Insert by reserved occurrence order, including among story entries.
        val index = current.binarySearchBy(reservation.order) { it.historyId }.let { if (it < 0) -it - 1 else it }
        _entries.value = current.toMutableList().apply { add(index, entry) }
        return true
    }

    @Synchronized
    fun clear() {
        session++ // Invalidate reservations even if an old network callback survives cancellation.
        sceneContextGeneration++
        dialogueLogSlots.clear()
        _entries.value = emptyList()
    }

    /** Starts a fresh story context without removing any user-visible LOG entries. */
    @Synchronized
    fun clearSceneDialogueContext() {
        sceneContextGeneration++
        FgoLogger.info(TAG, "Story translation context cleared at confirmed battle boundary")
    }

    @Synchronized
    fun currentSceneContextGeneration(): Long = sceneContextGeneration

    @Synchronized
    fun lastSceneDialogueContexts(
        limit: Int = DEFAULT_SCENE_DIALOGUE_CONTEXT_LIMIT,
        excludeDialogueSourceKey: String = ""
    ): List<SceneDialogueContext> {
        val safeLimit = limit.coerceIn(0, SettingsRepository.MAX_TRANSLATION_CONTEXT_SCENE_COUNT)
        if (safeLimit == 0) return emptyList()
        val excludeKey = excludeDialogueSourceKey.normalizeHistoryText()
        return _entries.value
            .asReversed()
            .asSequence()
            .filter { entry ->
                !entry.subtitleOccurrence && entry.sceneContextGeneration == sceneContextGeneration
            }
            .filter { entry ->
                val sourceDialogue = entry.contextSourceDialogue?.trim()
                sourceDialogue != null && sourceDialogue.isNotBlank()
            }
            .filter { entry ->
                entry.contextDialogueTranslationTrusted &&
                    !entry.contextTranslatedDialogue.isNullOrBlank() &&
                    !entry.contextTranslatedDialogue.isHistoryErrorText()
            }
            .filter { entry ->
                excludeKey.isBlank() || entry.normalizedDialogueSourceKey() != excludeKey
            }
            .mapNotNull { entry ->
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
                if (sourceSpeakerName != null && translatedSpeakerName == null) return@mapNotNull null
                val translatedDialogue = entry.contextTranslatedDialogue
                    ?.trim()
                    ?.takeIf { it.isNotBlank() && !it.isHistoryErrorText() }
                    ?: return@mapNotNull null
                SceneDialogueContext(
                    sourceSpeakerName = sourceSpeakerName,
                    translatedSpeakerName = translatedSpeakerName,
                    sourceDialogue = entry.contextSourceDialogue!!.trim(),
                    translatedDialogue = translatedDialogue,
                    targetLocale = SettingsRepository.normalizeTargetLanguage(entry.targetLocale),
                    dialogueSourceKey = entry.normalizedDialogueSourceKey(),
                    generalPreparedSourceDialogue = entry.generalPreparedSourceDialogue
                )
            }
            .take(safeLimit)
            .toList()
            .asReversed()
    }

    private fun refreshGeneralPreparedSourceDialogue(
        currentEntries: List<SessionTranslationEntry>,
        existingIndex: Int,
        incoming: SessionTranslationEntry
    ) {
        val existing = currentEntries[existingIndex]
        if (existing.generalPreparedSourceDialogue == incoming.generalPreparedSourceDialogue) return
        _entries.value = currentEntries.toMutableList().apply {
            this[existingIndex] = existing.copy(
                generalPreparedSourceDialogue = incoming.generalPreparedSourceDialogue
            )
        }
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
        if (previous.subtitleOccurrence) return false
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
            contextDialogueTranslationTrusted = false,
            generalPreparedSourceDialogue = null
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
            text.startsWith("[No API key]") ||
            text.startsWith("[翻译失败") ||
            text.startsWith("[翻譯失敗") ||
            text.startsWith("[Translation failed") ||
            text == "翻译失败" ||
            text == "翻譯失敗"
    }

    internal const val UNSPECIFIED_SCENE_CONTEXT_GENERATION = Long.MIN_VALUE
}
