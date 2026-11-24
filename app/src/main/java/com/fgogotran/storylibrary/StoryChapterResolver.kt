package com.fgogotran.storylibrary

import android.content.Context
import com.fgogotran.R
import com.fgogotran.data.SettingsRepository
import com.fgogotran.localization.AppLanguageManager
import com.fgogotran.scriptmemory.ScriptMemory
import com.fgogotran.translation.SceneTranslateInput
import com.fgogotran.translation.SceneTranslateResult
import com.fgogotran.translation.TranslateResult
import com.fgogotran.util.FgoLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Matches OCR text against the downloaded chapter database and renders the result.
 *
 * Contract with `Translator`: while a chapter pack is active this resolver always
 * answers — a hit returns the translated text, a miss never falls through to the
 * API. A short grace window of one miss keeps ordinary OCR noise from flashing a
 * warning; two consecutive misses mean the opened FGO scene is almost certainly not
 * the selected chapter, and the user gets the "check the selected chapter" text.
 *
 * Exact normalized-hash lookup runs first. When it misses, the approximate matcher
 * absorbs OCR-only damage (dash width, one wrong or dropped glyph) and still only
 * answers when the winner is unambiguous; see [StoryFuzzyMatcher]. Between the two,
 * a stored `[%1]` player-name slot is matched on its template, because the capture
 * shows the rendered name.
 */
@Singleton
class StoryChapterResolver @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val repository: StoryChapterRepository
) {
    enum class LineKind { DIALOGUE, NAME, CHOICE }

    sealed interface LineResult {
        data class Hit(val text: String) : LineResult

        /** The chapter pack is active but this text was not found. */
        data class Miss(
            val chapterMismatch: Boolean,
            val warningText: String? = null
        ) : LineResult

        /** The chapter pack is off or no chapter is applied. */
        data object Inactive : LineResult
    }

    private val consecutiveMisses = AtomicInteger(0)
    private var lastScriptId: String? = null
    private var lastPosition = 0
    private var lastSeq = 0
    private var flowChapterId = ""

    suspend fun translateLine(text: String, speaker: String, kind: LineKind): LineResult {
        if (!repository.isEnabled()) return LineResult.Inactive
        val database = repository.activeDatabase()
        val locale = settingsRepository.targetLanguage.first()
        val playerName = settingsRepository.playerName.first()
        val gender = settingsRepository.playerGender.first()
        if (database == null) {
            // Active but the installed file is gone or broken: warn, never call the API.
            consecutiveMisses.set(WARNING_THRESHOLD)
            return LineResult.Miss(chapterMismatch = true, warningText = warningText())
        }
        resetFlowIfChapterChanged(database.chapterId)

        if (kind == LineKind.NAME) {
            val hit = resolveName(database, speaker, StoryLocale.of(locale))
            val rendered = hit?.let { StoryLocale.of(locale).name(it) }.orEmpty()
            if (rendered.isBlank()) return LineResult.Miss(chapterMismatch = false)
            return LineResult.Hit(render(rendered, playerName, gender))
        }

        val storyLocale = StoryLocale.of(locale)
        // A button frame is matched as a button first; the dialogue path only reads
        // dialogue rows, so a collision can never answer with the wrong kind.
        val row = if (kind == LineKind.CHOICE) {
            matchChoice(database, text, dialogueRow = null, storyLocale, playerName)
                ?: matchDialogueRow(database, text, speaker, storyLocale, playerName)
        } else {
            matchDialogueRow(database, text, speaker, storyLocale, playerName)
        }
        if (row == null) {
            val mismatch = registerMiss()
            return LineResult.Miss(
                chapterMismatch = mismatch,
                warningText = if (mismatch) warningText() else null
            )
        }
        resetMisses()
        remember(row)
        return LineResult.Hit(render(row.dialog(locale), playerName, gender))
    }

    /**
     * Translates a whole scene from the active chapter pack.
     *
     * @return null when the chapter pack is inactive; otherwise a complete result in
     * which misses keep the original text and repeated misses carry the warning.
     */
    suspend fun translateScene(input: SceneTranslateInput): SceneTranslateResult? {
        if (!repository.isEnabled()) return null
        val locale = settingsRepository.targetLanguage.first()
        val playerName = settingsRepository.playerName.first()
        val gender = settingsRepository.playerGender.first()
        val database = repository.activeDatabase()
        if (database == null) {
            consecutiveMisses.set(WARNING_THRESHOLD)
            return SceneTranslateResult(
                name = null,
                dialogue = warning(locale),
                choices = emptyList(),
                voiceHint = null,
                generalPreparedSourceDialogue = null
            )
        }
        resetFlowIfChapterChanged(database.chapterId)
        val storyLocale = StoryLocale.of(locale)

        val nameResult = input.name?.let { speaker ->
            resolveName(database, speaker, storyLocale)
                ?.let { targets -> storyLocale.name(targets) }
                ?.takeIf { it.isNotBlank() }
                ?.let { translated -> result(render(translated, playerName, gender), locale) }
        }

        val dialogueRow = input.dialogue?.let {
            matchDialogueRow(database, it, input.name.orEmpty(), storyLocale, playerName)
        }

        // The frame carries both plates and the matched row carries both answers, so a
        // line that resolved decides its speaker. A short plate is the weaker evidence:
        // a damaged or unknown name must not leave Japanese (or another character) next
        // to a line that came straight out of the pack.
        val rowName = if (input.name.isNullOrBlank()) null else dialogueRow
            ?.takeIf { !it.isChoice && it.jpName.isNotBlank() }
            ?.let { row -> row.name(locale).takeIf { it.isNotBlank() } }
            ?.let { name -> result(render(name, playerName, gender), locale) }

        val dialogueResult: TranslateResult? = when {
            dialogueRow != null -> {
                resetMisses()
                remember(dialogueRow)
                result(render(dialogueRow.dialog(locale), playerName, gender), locale)
            }
            input.dialogue.isNullOrBlank() -> null
            registerMiss() -> warning(locale)
            else -> null
        }

        val choiceResults = input.choices.map { choice ->
            val row = matchChoice(database, choice, dialogueRow, storyLocale, playerName)
            if (row == null) {
                result(choice, locale)
            } else {
                result(render(row.dialog(locale), playerName, gender), locale)
            }
        }

        return SceneTranslateResult(
            name = rowName ?: nameResult,
            dialogue = dialogueResult,
            choices = choiceResults,
            voiceHint = null,
            generalPreparedSourceDialogue = null
        )
    }

    private suspend fun matchDialogueRow(
        database: StoryChapterDatabase,
        text: String,
        speaker: String,
        locale: StoryLocale,
        playerName: String
    ): StoryChapterDatabase.Row? {
        val hash = StoryOcrNormalizer.hash(text)
        if (hash.isEmpty()) return null

        // The flow and the exact lookup both stay inside dialogue rows, so a button
        // whose text normalizes to the same line can never answer a dialogue frame.
        val expected = flowExpected(database)
        if (expected != null && expected.jpHash == hash) return expected

        val candidates = database.dialogueRowsForHash(hash)
        if (candidates.isNotEmpty()) return disambiguate(candidates, speaker)

        // A `[%1]` line can only match once the rendered name is removed, and the
        // template is exact, so it is tried before the coarser scene window.
        matchSlot(
            database = database,
            text = text,
            playerName = playerName,
            speaker = speaker,
            locale = locale,
            choice = false,
            flowScriptId = lastScriptId,
            flowSeq = lastSeq
        )?.let { return it }

        if (!fuzzyMatchingEnabled()) return null

        // The reader is inside a confirmed script: story order is the strongest
        // prior, so a partially recognized line resolves to its logical position
        // before the whole pack is searched.
        matchSceneWindow(database, text, speaker, locale)?.let { row ->
            FgoLogger.debug(
                tag,
                "Story dialogue matched by scene window: script=${row.scriptId} seq=${row.seq}"
            )
            return row
        }

        val row = database.fuzzyDialogue(
            key = StoryOcrNormalizer.fuzzyKey(text),
            locale = locale,
            flowScriptId = lastScriptId,
            flowSeq = lastSeq,
            speakerKey = StoryOcrNormalizer.nameKey(speaker)
        ) ?: return null
        FgoLogger.debug(tag, "Story dialogue matched approximately: script=${row.scriptId} seq=${row.seq}")
        return row
    }

    private fun matchSceneWindow(
        database: StoryChapterDatabase,
        text: String,
        speaker: String,
        locale: StoryLocale
    ): StoryChapterDatabase.Row? {
        val script = lastScriptId ?: return null
        val window = database.windowEntries(script, lastPosition, SCENE_WINDOW)
        if (window.isEmpty()) return null
        val query = StoryFuzzyMatcher.query(
            StoryOcrNormalizer.fuzzyKey(text),
            StoryOcrNormalizer.nameKey(speaker)
        )
        val match = StoryFuzzyMatcher.selectInWindow(query, window, locale) ?: return null
        return database.rowBySeq(match.entry.id)
    }

    private suspend fun matchChoice(
        database: StoryChapterDatabase,
        text: String,
        dialogueRow: StoryChapterDatabase.Row?,
        locale: StoryLocale,
        playerName: String
    ): StoryChapterDatabase.Row? {
        val hash = StoryOcrNormalizer.hash(text)
        if (hash.isNotEmpty()) {
            val candidates = database.choiceRowsForHash(hash)
            if (candidates.isNotEmpty()) {
                val anchor = dialogueRow?.seq ?: lastSeq
                val sameGroup = dialogueRow?.choiceGroup?.let { group ->
                    candidates.filter { it.choiceGroup == group }
                }.orEmpty()
                val pool = sameGroup.ifEmpty { candidates }
                return pool.minByOrNull { abs(it.seq - anchor) } ?: pool.first()
            }
        }
        matchSlot(
            database = database,
            text = text,
            playerName = playerName,
            speaker = "",
            locale = locale,
            choice = true,
            flowScriptId = dialogueRow?.scriptId ?: lastScriptId,
            flowSeq = dialogueRow?.seq ?: lastSeq
        )?.let { return it }
        if (!fuzzyMatchingEnabled()) return null
        val row = database.fuzzyChoice(
            key = StoryOcrNormalizer.fuzzyKey(text),
            locale = locale,
            flowScriptId = dialogueRow?.scriptId ?: lastScriptId,
            flowSeq = dialogueRow?.seq ?: lastSeq
        ) ?: return null
        FgoLogger.debug(tag, "Story choice matched approximately: script=${row.scriptId} seq=${row.seq}")
        return row
    }

    /**
     * Resolves a line whose `[%1]` slot the game rendered as the player's name.
     *
     * The stored row keeps the `%1` template, so the exact hash can never match the
     * rendered capture. Both sides are reduced to the template instead: the exact
     * template wins first, then the same approximate rule as an ordinary line.
     */
    private suspend fun matchSlot(
        database: StoryChapterDatabase,
        text: String,
        playerName: String,
        speaker: String,
        locale: StoryLocale,
        choice: Boolean,
        flowScriptId: String?,
        flowSeq: Int
    ): StoryChapterDatabase.Row? {
        val key = StoryOcrNormalizer.slotKey(text, playerName)
        if (key.isEmpty()) return null
        // Only a capture that actually rendered the name can use a slot template;
        // otherwise a short line would be matched against a stripped one.
        if (key == StoryOcrNormalizer.fuzzyKey(text)) return null
        val kind = if (choice) "choice" else "line"

        val candidates = database.slotRows(key, choice)
        if (candidates.isNotEmpty()) {
            val row = disambiguate(candidates, speaker)
            FgoLogger.debug(tag, "Story $kind matched by master-name slot: script=${row.scriptId} seq=${row.seq}")
            return row
        }
        if (!fuzzyMatchingEnabled()) return null

        val row = database.fuzzySlot(
            key = key,
            locale = locale,
            flowScriptId = flowScriptId,
            flowSeq = flowSeq,
            speakerKey = StoryOcrNormalizer.nameKey(speaker),
            choice = choice
        ) ?: return null
        FgoLogger.debug(
            tag,
            "Story $kind matched approximately by master-name slot: script=${row.scriptId} seq=${row.seq}"
        )
        return row
    }

    private fun disambiguate(
        candidates: List<StoryChapterDatabase.Row>,
        speaker: String
    ): StoryChapterDatabase.Row {
        if (candidates.size == 1) return candidates.first()
        val speakerKey = StoryOcrNormalizer.nameKey(speaker)
        val sameSpeaker = if (speakerKey.isEmpty()) emptyList()
        else candidates.filter { StoryOcrNormalizer.nameKey(it.jpName) == speakerKey }
        val pool = sameSpeaker.ifEmpty { candidates }
        val continuing = lastScriptId?.let { script ->
            pool.filter { it.scriptId == script }
                .minByOrNull { abs(it.seq - lastSeq) }
        }
        return continuing ?: pool.first()
    }

    /** Exact speaker lookup first; the approximate lookup only runs on a miss. */
    private suspend fun resolveName(
        database: StoryChapterDatabase,
        speaker: String,
        locale: StoryLocale
    ): StoryChapterDatabase.NameTargets? {
        database.nameTargets(speaker)?.let { return it }
        val key = StoryOcrNormalizer.nameKey(speaker)
        // A plate decoration glyph (`壱与A`) is common and a short name has too
        // few bigrams for the approximate lookup, so the prefix rule runs first.
        database.prefixNameTargets(key)?.let { targets ->
            FgoLogger.debug(tag, "Story speaker matched with a plate suffix: $key")
            return targets
        }
        if (!fuzzyMatchingEnabled()) return null
        val targets = database.fuzzyNameTargets(key, locale, lastScriptId, lastSeq) ?: return null
        FgoLogger.debug(tag, "Story speaker matched approximately: $key")
        return targets
    }

    private suspend fun fuzzyMatchingEnabled(): Boolean =
        settingsRepository.storyChapterFuzzyMatch.first()

    private fun flowExpected(database: StoryChapterDatabase): StoryChapterDatabase.Row? {
        val script = lastScriptId ?: return null
        // A button is not the next dialogue box, so the flow never hands one over.
        return database.nextAfter(script, lastPosition)?.takeIf { !it.isChoice }
    }

    private fun remember(row: StoryChapterDatabase.Row) {
        lastScriptId = row.scriptId
        lastPosition = row.position
        lastSeq = row.seq
    }

    private fun resetFlowIfChapterChanged(chapterId: String) {
        if (flowChapterId == chapterId) return
        flowChapterId = chapterId
        lastScriptId = null
        lastPosition = 0
        lastSeq = 0
        resetMisses()
    }

    private fun registerMiss(): Boolean = consecutiveMisses.incrementAndGet() >= WARNING_THRESHOLD

    private fun resetMisses() {
        consecutiveMisses.set(0)
    }

    private fun result(text: String, locale: String): TranslateResult = TranslateResult(
        translatedText = text,
        backend = "chapter-db",
        cached = false,
        targetLocale = locale,
        trustedForContext = false,
        isFailure = false,
        apiUsage = null,
        countsAsUsage = false
    )

    private suspend fun warningText(): String = AppLanguageManager.wrap(context)
        .getString(R.string.story_chapter_text_not_found, repository.activeChapterId().ifBlank { "?" })

    private suspend fun warning(locale: String): TranslateResult = TranslateResult(
        translatedText = warningText(),
        backend = "chapter-db",
        cached = false,
        targetLocale = locale,
        trustedForContext = false,
        isFailure = true,
        apiUsage = null,
        countsAsUsage = false
    )

    private fun render(text: String, playerName: String, gender: String): String =
        ScriptMemory.renderOfficial(text, playerName, gender)
            .replace("⟦", "")
            .replace("⟧", "")
            .trim()

    companion object {
        private const val tag = "StoryLibrary"
        private const val WARNING_THRESHOLD = 2
        private const val SCENE_WINDOW = 6
    }
}
