package com.fgogotran.storylibrary

import android.content.Context
import com.fgogotran.data.SettingsRepository
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
 * answers — a hit returns the translated text, a miss returns nothing at all and
 * never falls through to the API. A miss is normal there: the game keeps painting its
 * own line, and the overlay never repeats the Japanese source or shows a warning.
 * Consecutive misses still steer the flow (the next frame may start a new scene).
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
            val chapterMismatch: Boolean
        ) : LineResult

        /** The chapter pack is off or no chapter is applied. */
        data object Inactive : LineResult
    }

    private val consecutiveMisses = AtomicInteger(0)
    private var lastScriptId: String? = null
    private var lastPosition = 0
    private var lastSeq = 0
    private var flowChapterId = ""

    /**
     * Single-line entry: resolves one confirmed story line and keeps the flow in step.
     *
     * Production story translation reaches the pack through [translateScene] only, so that
     * crop, battle and subtitle surfaces cannot pick up chapter text. This entry stays
     * module-visible for the resolver's own tests and for a future story surface that
     * already holds one confirmed line; a caller from another surface would have to pass a
     * confirmed line it does not have.
     */
    internal suspend fun translateLine(text: String, speaker: String, kind: LineKind): LineResult {
        if (!repository.isEnabled()) return LineResult.Inactive
        val database = repository.activeDatabase()
        val locale = settingsRepository.targetLanguage.first()
        val playerName = settingsRepository.playerName.first()
        val gender = settingsRepository.playerGender.first()
        if (database == null) {
            // Active but the installed file is gone or broken: stay silent, never call the API.
            consecutiveMisses.set(WARNING_THRESHOLD)
            return LineResult.Miss(chapterMismatch = true)
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
            // A button never takes the dialogue inference: it answers with a button, or
            // with nothing. The dialogue fallback below is for wording, not for the flow.
            matchChoice(database, text, dialogueRow = null, storyLocale, playerName)
                ?: matchDialogueRow(
                    database, text, speaker, storyLocale, playerName, allowFlowInference = false
                )
        } else {
            matchDialogueRow(database, text, speaker, storyLocale, playerName)
        }
        if (row == null) {
            return LineResult.Miss(chapterMismatch = registerMiss())
        }
        resetMisses()
        remember(row)
        return LineResult.Hit(render(row.dialog(locale), playerName, gender))
    }

    /**
     * Translates a whole scene from the active chapter pack.
     *
     * @return null when the chapter pack is inactive; otherwise a result in which every
     * miss is blank, so the game's own text stays and no warning is painted. A choice
     * frame is all-or-nothing: one plate the pack cannot answer leaves the whole frame
     * unpainted instead of mixing translations with the original.
     */
    suspend fun translateScene(input: SceneTranslateInput): SceneTranslateResult? {
        if (!repository.isEnabled()) return null
        val locale = settingsRepository.targetLanguage.first()
        val playerName = settingsRepository.playerName.first()
        val gender = settingsRepository.playerGender.first()
        val database = repository.activeDatabase()
        if (database == null) {
            // Enabled but the installed pack is gone or broken: keep the game's text.
            consecutiveMisses.set(WARNING_THRESHOLD)
            return SceneTranslateResult(
                name = null,
                dialogue = null,
                choices = emptyList(),
                chapterPack = true
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
            ?.let { name -> result(render(name, playerName, gender), locale, dialogueRow.jpName) }

        val dialogueResult: TranslateResult? = when {
            dialogueRow != null -> {
                resetMisses()
                remember(dialogueRow)
                result(
                    render(dialogueRow.dialog(locale), playerName, gender),
                    locale,
                    render(dialogueRow.jpDialog, playerName, gender)
                )
            }
            input.dialogue.isNullOrBlank() -> null
            else -> {
                // A miss stays blank: the game's own line remains, with no warning text.
                registerMiss()
                null
            }
        }

        // All-or-nothing: a choice frame is painted only when every plate resolves, so a
        // half-recognised frame never mixes translations with the game's source text and
        // a pack miss never repaints the Japanese button.
        val choiceRows = input.choices.map { choice ->
            if (choice.isBlank()) null else matchChoice(database, choice, dialogueRow, storyLocale, playerName)
        }
        val everyChoiceResolved = input.choices.indices.none { index ->
            input.choices[index].isNotBlank() && choiceRows[index] == null
        }
        val choiceResults = input.choices.mapIndexed { index, _ ->
            val row = choiceRows[index]
            if (!everyChoiceResolved || row == null) {
                result("", locale)
            } else {
                result(
                    render(row.dialog(locale), playerName, gender),
                    locale,
                    render(row.jpDialog, playerName, gender)
                )
            }
        }

        return SceneTranslateResult(
            name = rowName ?: nameResult,
            dialogue = dialogueResult,
            choices = choiceResults,
            chapterPack = true
        )
    }

    /**
     * Answers whether [text] resolves in the active pack, without changing the flow.
     *
     * The Story Library OCR accepts a fast read only when the pack agrees with it, so a
     * miss still reaches the full pipeline. This must stay read-only: it runs before the
     * real lookup and shares this resolver's flow state.
     */
    internal suspend fun probe(text: String, speaker: String, kind: LineKind): Boolean {
        if (text.isBlank() || !repository.isEnabled()) return false
        val database = repository.activeDatabase() ?: return false
        val storyLocale = StoryLocale.of(settingsRepository.targetLanguage.first())
        val playerName = settingsRepository.playerName.first()
        return when (kind) {
            LineKind.NAME -> resolveName(database, text, storyLocale) != null
            LineKind.CHOICE -> matchChoice(database, text, null, storyLocale, playerName) != null
            LineKind.DIALOGUE ->
                matchDialogueRow(database, text, speaker, storyLocale, playerName) != null
        }
    }

    private suspend fun matchDialogueRow(
        database: StoryChapterDatabase,
        text: String,
        speaker: String,
        locale: StoryLocale,
        playerName: String,
        allowFlowInference: Boolean = true
    ): StoryChapterDatabase.Row? {
        val hash = StoryOcrNormalizer.hash(text)
        if (hash.isEmpty()) return punctuationRow(database, text, speaker, locale, choice = false)

        // The flow and the exact lookup both stay inside dialogue rows, so a button
        // whose text normalizes to the same line can never answer a dialogue frame.
        val expected = flowExpected(database)
        if (expected != null && expected.jpHash == hash) return expected

        val candidates = database.dialogueRowsForHash(hash)
        if (candidates.isNotEmpty()) {
            return disambiguate(candidates, speaker, locale)
                ?: openingOfCandidates(database, candidates, speaker)
        }

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
        )
        if (row == null) {
            return if (allowFlowInference) {
                // A new scene starts with its own opening line, which identifies the script
                // even when the previous scene is still the confirmed flow.
                matchOpening(database, text, speaker)
                    ?: flowLengthRow(database, expected, text, speaker)
            } else {
                null
            }
        }
        if (!keepsOrEntersFlow(row)) {
            FgoLogger.debug(
                tag,
                "Story dialogue fuzzy hit rejected outside the confirmed script: " +
                    "script=${row.scriptId} seq=${row.seq} flow=$lastScriptId"
            )
            return null
        }
        FgoLogger.debug(tag, "Story dialogue matched approximately: script=${row.scriptId} seq=${row.seq}")
        return row
    }

    /**
     * Identifies a new scene by its opening line.
     *
     * A scene starts right after the previous line, so the confirmed flow still points at
     * the old script and no window row explains the frame. The opening line is nearly
     * unique to its script, and the pack stores it in `script_openings`, so comparing
     * against those rows is the only place that can name the script on screen.
     *
     * The captured text is usually damaged, so the comparison is normalized edit distance
     * rather than the exact hash: one missing or extra character still lands on the right
     * opening. Anything short of one clear best stays a miss, which keeps the original
     * line on screen instead of guessing.
     */
    private fun matchOpening(
        database: StoryChapterDatabase,
        text: String,
        speaker: String
    ): StoryChapterDatabase.Row? {
        val query = StoryOcrNormalizer.fuzzyKey(text)
        if (query.codePointCount(0, query.length) < MIN_OPENING_CHARS) return null
        val openings = database.openingRows()
        if (openings.isEmpty()) return null
        var best: StoryChapterDatabase.Opening? = null
        var bestScore = 0.0
        var secondScore = 0.0
        for (opening in openings) {
            val score = editScore(query, opening.key)
            if (score > bestScore) {
                secondScore = bestScore
                bestScore = score
                best = opening
            } else if (score > secondScore) {
                secondScore = score
            }
        }
        val opening = best ?: return null
        if (bestScore < OPENING_MIN_SCORE) return null
        if (bestScore - secondScore < OPENING_MARGIN) return null
        val row = database.rowBySeq(opening.seq) ?: return null
        if (StoryOcrNormalizer.nameKey(speaker) != StoryOcrNormalizer.nameKey(row.jpName)) return null
        FgoLogger.debug(
            tag,
            "Story scene identified by its opening line: script=${row.scriptId} " +
                "seq=${row.seq} score=$bestScore read=\"$text\""
        )
        return row
    }

    /**
     * Separates two rows that normalize to the same line with different renderings.
     *
     * `絶剱無穹一閃` is both a scene's first line and a line hundreds of rows into another
     * scene, so the speaker cannot tell them apart. Only one of those scripts starts with
     * the line, which settles it when the opening belongs to exactly one candidate.
     */
    private fun openingOfCandidates(
        database: StoryChapterDatabase,
        candidates: List<StoryChapterDatabase.Row>,
        speaker: String
    ): StoryChapterDatabase.Row? {
        val opening = candidates.filter { database.isScriptOpening(it) }
        if (opening.size != 1) return null
        val row = opening.single()
        if (StoryOcrNormalizer.nameKey(speaker) != StoryOcrNormalizer.nameKey(row.jpName)) return null
        FgoLogger.debug(
            tag,
            "Story line disambiguated by its scene opening: script=${row.scriptId} seq=${row.seq}"
        )
        return row
    }

    /** `1 - distance / longest`, so identical strings score 1.0 and one edit in six 0.83. */
    private fun editScore(a: String, b: String): Double {
        val longest = maxOf(a.length, b.length)
        if (longest == 0) return 0.0
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + cost)
            }
            val swap = previous
            previous = current
            current = swap
        }
        return 1.0 - previous[b.length].toDouble() / longest
    }

    /**
     * Accepts the line the confirmed flow expects when the glyphs themselves defeated the
     * recognizer.
     *
     * A stylized `オオオォ……。` reads as `才才才才……。` with high confidence: the two share
     * no bigram, so no score threshold reaches them, and both crops return the same wrong
     * characters. The pack still identifies the frame on independent axes, and the rule
     * takes the expected row only when all of them agree: the previous line resolved, the
     * read and the stored line have the same length after normalization, the speaker plate
     * matches, and no row anywhere in the pack carries the mangled text — a line that the
     * pack does contain would have matched exactly and never reached this point. Anything
     * less stays a miss, which keeps the original text on screen instead of guessing.
     */
    private fun flowLengthRow(
        database: StoryChapterDatabase,
        expected: StoryChapterDatabase.Row?,
        text: String,
        speaker: String
    ): StoryChapterDatabase.Row? {
        if (expected == null || expected.isChoice) return null
        // After a miss the reader may have skipped a line, so the flow no longer speaks
        // for the frame on screen.
        if (consecutiveMisses.get() > 0) return null
        val read = StoryOcrNormalizer.normalize(text)
        val readLength = read.codePointCount(0, read.length)
        if (readLength < MIN_FLOW_LENGTH) return null
        val stored = database.normalizedDialog(expected.seq) ?: return null
        if (stored.codePointCount(0, stored.length) != readLength) return null
        if (StoryOcrNormalizer.nameKey(speaker) != StoryOcrNormalizer.nameKey(expected.jpName)) {
            return null
        }
        if (database.hasAnyRow(StoryOcrNormalizer.hash(text))) return null
        FgoLogger.debug(
            tag,
            "Story line accepted from flow by length: script=${expected.scriptId} " +
                "seq=${expected.seq} read=\"$text\""
        )
        return expected
    }

    /**
     * A confirmed script is the strongest prior the reader has. The approximate lookup
     * searches the whole pack, so it can prefer a similar line from another script; taking
     * it would also move the flow there and make every following line look wrong. Leaving
     * the script is allowed once a line has already missed, because a new scene starts
     * exactly that way.
     */
    private fun keepsOrEntersFlow(row: StoryChapterDatabase.Row): Boolean {
        val script = lastScriptId ?: return true
        if (row.scriptId == script) return true
        return consecutiveMisses.get() > 0
    }

    /**
     * A punctuation-only line (`……`, `！？`) keeps no characters after normalization, so
     * it has no hash to look up even though the pack stores it. The frame kind still
     * separates a dialogue row from a button.
     */
    private fun punctuationRow(
        database: StoryChapterDatabase,
        text: String,
        speaker: String,
        locale: StoryLocale,
        choice: Boolean
    ): StoryChapterDatabase.Row? {
        val key = StoryOcrNormalizer.visualKey(text)
        val candidates = database.punctuationRows(key, choice)
        if (candidates.isEmpty()) return null
        val row = disambiguate(candidates, speaker, locale) ?: return null
        FgoLogger.debug(
            tag,
            "Story line matched by punctuation key: script=${row.scriptId} seq=${row.seq}"
        )
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
        if (hash.isEmpty()) return punctuationRow(database, text, "", locale, choice = true)
        val candidates = database.choiceRowsForHash(hash)
        if (candidates.isNotEmpty()) {
            val anchor = dialogueRow?.seq ?: lastSeq
            val sameGroup = dialogueRow?.choiceGroup?.let { group ->
                candidates.filter { it.choiceGroup == group }
            }.orEmpty()
            val pool = sameGroup.ifEmpty { candidates }
            return pool.minByOrNull { abs(it.seq - anchor) } ?: pool.first()
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
        // A choice has no speaker and only a few characters, so a whole-pack approximate
        // hit is another scene's button far more often than an OCR slip. Only the script
        // of the confirmed flow may answer; without one, only an exact row may.
        val script = dialogueRow?.scriptId ?: lastScriptId ?: return null
        val row = database.fuzzyChoice(
            key = StoryOcrNormalizer.fuzzyKey(text),
            locale = locale,
            flowScriptId = script,
            flowSeq = dialogueRow?.seq ?: lastSeq,
            scriptId = script
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
            val row = disambiguate(candidates, speaker, locale)
            if (row != null) {
                FgoLogger.debug(tag, "Story $kind matched by master-name slot: script=${row.scriptId} seq=${row.seq}")
                return row
            }
        }
        if (!fuzzyMatchingEnabled()) return null
        // A choice slot obeys the same script-bound rule as an ordinary choice.
        if (choice && flowScriptId == null) return null

        val row = database.fuzzySlot(
            key = key,
            locale = locale,
            flowScriptId = flowScriptId,
            flowSeq = flowSeq,
            speakerKey = StoryOcrNormalizer.nameKey(speaker),
            choice = choice,
            scriptId = if (choice) flowScriptId else null
        ) ?: return null
        if (!choice && !keepsOrEntersFlow(row)) return null
        FgoLogger.debug(
            tag,
            "Story $kind matched approximately by master-name slot: script=${row.scriptId} seq=${row.seq}"
        )
        return row
    }

    /**
     * Narrows rows that share one lookup key.
     *
     * Selection order is evidence strength: the same speaker, then an identical
     * rendering (every candidate would answer the same), then the confirmed flow.
     * When nothing separates different renderings the line stays a miss, because
     * borrowing another scene's translation is worse than leaving the original.
     */
    private fun disambiguate(
        candidates: List<StoryChapterDatabase.Row>,
        speaker: String,
        locale: StoryLocale
    ): StoryChapterDatabase.Row? {
        if (candidates.size == 1) return candidates.first()
        val speakerKey = StoryOcrNormalizer.nameKey(speaker)
        val sameSpeaker = if (speakerKey.isEmpty()) emptyList()
        else candidates.filter { StoryOcrNormalizer.nameKey(it.jpName) == speakerKey }
        val pool = sameSpeaker.ifEmpty { candidates }
        val continuing = lastScriptId?.let { script ->
            pool.filter { it.scriptId == script }
                .minByOrNull { abs(it.seq - lastSeq) }
        }
        if (continuing != null) return continuing
        if (sameSpeaker.isNotEmpty()) return sameSpeaker.first()
        if (candidates.map { it.dialogText(locale) }.toSet().size == 1) return candidates.first()
        FgoLogger.debug(
            tag,
            "Story lookup stayed ambiguous across ${candidates.size} rows: seqs=" +
                candidates.take(4).joinToString(",") { it.seq.toString() }
        )
        return null
    }

    /** The rendered line for [locale], used to tell an ambiguous hit from a real one. */
    private fun StoryChapterDatabase.Row.dialogText(locale: StoryLocale): String = when (locale) {
        StoryLocale.CN -> cnDialog
        StoryLocale.TW -> twDialog
        StoryLocale.EN -> enDialog
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

    private fun result(text: String, locale: String, sourceText: String? = null): TranslateResult = TranslateResult(
        translatedText = text,
        backend = "chapter-db",
        cached = false,
        targetLocale = locale,
        trustedForContext = false,
        isFailure = false,
        apiUsage = null,
        countsAsUsage = false,
        sourceText = sourceText?.takeIf { it.isNotBlank() }
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

        /** Shortest line the flow may substitute for a failed read; shorter lines collide too easily. */
        private const val MIN_FLOW_LENGTH = 4

        /** Shortest read the opening lookup may use; a shorter line is too common to identify a scene. */
        private const val MIN_OPENING_CHARS = 4

        /** How close the read must be to a scene opening before the scene is adopted. */
        private const val OPENING_MIN_SCORE = 0.80

        /** How far the best opening must lead the runner-up; two similar scenes stay a miss. */
        private const val OPENING_MARGIN = 0.10
    }
}
