package com.fgogotran.storylibrary

import android.database.sqlite.SQLiteDatabase
import java.io.File

/**
 * Read-only reader for a downloaded Story Library chapter database.
 *
 * Schema (`PRAGMA user_version = 1`) is produced by
 * `story_builder/build-story-db.mjs`: `meta`, `scripts`, `entries`, and
 * `script_openings`, with indexes on `jp_hash`, `jp_norm`, `(script_id, position)`.
 *
 * Matching order mirrors the published contract: exact normalized hash first, then
 * the stored story flow (`script_id` + `position`), with speaker and sequence used
 * to disambiguate repeated lines. A line with a `[%1]` player-name slot cannot hash
 * to its rendered capture, so it is also matched on the slot-free template; see
 * [slotRows]. `script_openings` supports the wrong-chapter warning. The approximate
 * index is built on the first miss and folds OCR-only damage; see
 * [StoryFuzzyMatcher].
 */
internal class StoryChapterDatabase private constructor(
    private val db: SQLiteDatabase,
    val chapterId: String,
    val contentVersion: String,
    val status: String,
    val entryCount: Int,
    private val names: Map<String, NameTargets>
) {
    data class NameTargets(val cn: String, val tw: String, val en: String)

    /** Built on the first approximate miss; exact lookups never pay for it. */
    private val fuzzyIndex: FuzzyIndex by lazy { buildFuzzyIndex() }

    /** Rows whose line carries a `[%1]` slot; see [StoryOcrNormalizer.slotKey]. */
    private val slotIndex: SlotIndex by lazy { buildSlotIndex() }

    private val nameEntries: List<StoryFuzzyMatcher.Entry> by lazy {
        names.entries.mapIndexed { index, (key, targets) ->
            StoryFuzzyMatcher.Entry(index, key, key, "", -1, targets.cn, targets.tw, targets.en)
        }
    }

    private class FuzzyIndex(
        val dialogue: List<StoryFuzzyMatcher.Entry>,
        val choices: List<StoryFuzzyMatcher.Entry>
    )

    private class SlotIndex(
        val dialogue: List<StoryFuzzyMatcher.Entry>,
        val choices: List<StoryFuzzyMatcher.Entry>
    )

    data class Row(
        val seq: Int,
        val scriptId: String,
        val position: Int,
        val type: String,
        val jpName: String,
        val jpHash: String,
        val choiceGroup: String?,
        val cnName: String,
        val cnDialog: String,
        val twName: String,
        val twDialog: String,
        val enName: String,
        val enDialog: String
    ) {
        fun name(targetLocale: String): String = when (StoryLocale.of(targetLocale)) {
            StoryLocale.CN -> cnName
            StoryLocale.TW -> twName
            StoryLocale.EN -> enName
        }

        fun dialog(targetLocale: String): String = when (StoryLocale.of(targetLocale)) {
            StoryLocale.CN -> cnDialog
            StoryLocale.TW -> twDialog
            StoryLocale.EN -> enDialog
        }

        val isChoice: Boolean get() = type == "choice"
    }

    /**
     * Dialogue rows for an exact hash.
     *
     * The frame carries its own kind, so a dialogue box never answers with a
     * button's row. Punctuation is not part of the key, which makes short lines
     * collide across kinds: the pilot pack normalizes `だって……、` (dialogue) and
     * `だって……！` (choice) to the same hash.
     */
    fun dialogueRowsForHash(hash: String): List<Row> {
        if (hash.isEmpty()) return emptyList()
        return query(
            "WHERE jp_hash = ? AND type <> 'choice' ORDER BY seq LIMIT $MAX_CANDIDATES",
            arrayOf(hash)
        )
    }

    fun choiceRowsForHash(hash: String): List<Row> {
        if (hash.isEmpty()) return emptyList()
        return query(
            "WHERE jp_hash = ? AND type = 'choice' ORDER BY seq LIMIT $MAX_CANDIDATES",
            arrayOf(hash)
        )
    }

    /**
     * Exact template lookup for a line whose `[%1]` slot the game rendered as the
     * player's name; [key] must come from [StoryOcrNormalizer.slotKey].
     */
    fun slotRows(key: String, choice: Boolean): List<Row> {
        if (key.isEmpty()) return emptyList()
        val entries = if (choice) slotIndex.choices else slotIndex.dialogue
        val rows = ArrayList<Row>(2)
        for (entry in entries) {
            if (entry.key != key) continue
            rowBySeq(entry.seq)?.let { rows.add(it) }
            if (rows.size >= MAX_CANDIDATES) break
        }
        return rows
    }

    /** Approximate template lookup; same ambiguity rule as [fuzzyChoice]. */
    fun fuzzySlot(
        key: String,
        locale: StoryLocale,
        flowScriptId: String?,
        flowSeq: Int,
        speakerKey: String,
        choice: Boolean
    ): Row? {
        if (key.isEmpty()) return null
        val match = StoryFuzzyMatcher.select(
            query = StoryFuzzyMatcher.query(key, speakerKey),
            candidates = if (choice) slotIndex.choices else slotIndex.dialogue,
            threshold = if (choice) StoryFuzzyMatcher.CHOICE_THRESHOLD
            else StoryFuzzyMatcher.DIALOGUE_THRESHOLD,
            locale = locale,
            flowScriptId = flowScriptId,
            flowSeq = flowSeq
        ) ?: return null
        return rowBySeq(match.entry.seq)
    }

    fun nameTargets(speaker: String): NameTargets? {
        val key = StoryOcrNormalizer.nameKey(speaker)
        if (key.isEmpty()) return null
        return names[key]
    }

    /**
     * Name lookup for a capture with an extra plate glyph after the name.
     *
     * The name plate sometimes OCRs a decoration (`壱与A`). A two-character name
     * carries too few bigrams for [fuzzyNameTargets], so the stored name that
     * starts the capture wins when it is the longest such name and the capture
     * adds at most [MAX_NAME_SUFFIX] characters.
     */
    fun prefixNameTargets(key: String): NameTargets? {
        if (key.isEmpty()) return null
        var bestKey: String? = null
        var best: NameTargets? = null
        for ((name, targets) in names) {
            if (name.length >= key.length) continue
            if (key.length - name.length > MAX_NAME_SUFFIX) continue
            if (!key.startsWith(name)) continue
            val current = bestKey
            if (current == null || name.length > current.length) {
                bestKey = name
                best = targets
            } else if (name.length == current.length && targets != best) {
                return null
            }
        }
        return best
    }

    /**
     * Approximate speaker-name lookup; only used after the exact lookup misses.
     * Returns null when two different names are close enough to be confused.
     */
    fun fuzzyNameTargets(
        key: String,
        locale: StoryLocale,
        flowScriptId: String?,
        flowSeq: Int
    ): NameTargets? {
        if (key.isEmpty()) return null
        val match = StoryFuzzyMatcher.select(
            query = StoryFuzzyMatcher.query(key),
            candidates = nameEntries,
            threshold = StoryFuzzyMatcher.NAME_THRESHOLD,
            locale = locale,
            flowScriptId = flowScriptId,
            flowSeq = flowSeq
        ) ?: return null
        return NameTargets(
            cn = match.entry.text(StoryLocale.CN),
            tw = match.entry.text(StoryLocale.TW),
            en = match.entry.text(StoryLocale.EN)
        )
    }

    /** Approximate dialogue lookup; null when nothing clears the ambiguity rule. */
    fun fuzzyDialogue(
        key: String,
        locale: StoryLocale,
        flowScriptId: String?,
        flowSeq: Int,
        speakerKey: String = ""
    ): Row? {
        if (key.isEmpty()) return null
        val match = StoryFuzzyMatcher.select(
            query = StoryFuzzyMatcher.query(key, speakerKey),
            candidates = fuzzyIndex.dialogue,
            threshold = StoryFuzzyMatcher.DIALOGUE_THRESHOLD,
            locale = locale,
            flowScriptId = flowScriptId,
            flowSeq = flowSeq
        ) ?: return null
        return rowBySeq(match.entry.id)
    }

    /** Approximate choice lookup; null when nothing clears the ambiguity rule. */
    fun fuzzyChoice(
        key: String,
        locale: StoryLocale,
        flowScriptId: String?,
        flowSeq: Int
    ): Row? {
        if (key.isEmpty()) return null
        val match = StoryFuzzyMatcher.select(
            query = StoryFuzzyMatcher.query(key),
            candidates = fuzzyIndex.choices,
            threshold = StoryFuzzyMatcher.CHOICE_THRESHOLD,
            locale = locale,
            flowScriptId = flowScriptId,
            flowSeq = flowSeq
        ) ?: return null
        return rowBySeq(match.entry.id)
    }

    fun rowBySeq(seq: Int): Row? =
        query("WHERE seq = ? LIMIT 1", arrayOf(seq.toString())).firstOrNull()

    /**
     * The next dialogue lines after [position] inside [scriptId], in story order.
     * Choices are excluded because they are rendered as buttons, not dialogue.
     */
    fun windowEntries(scriptId: String, position: Int, limit: Int): List<StoryFuzzyMatcher.Entry> {
        val entries = ArrayList<StoryFuzzyMatcher.Entry>(limit)
        db.rawQuery(
            "SELECT seq, script_id, jp_name, jp_norm, cn_dialog, tw_dialog, en_dialog FROM entries " +
                "WHERE script_id = ? AND position > ? AND type <> 'choice' AND jp_norm <> '' " +
                "ORDER BY position LIMIT ?",
            arrayOf(scriptId, position.toString(), limit.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                entries.add(
                    StoryFuzzyMatcher.Entry(
                        id = cursor.getInt(0),
                        key = StoryOcrNormalizer.foldKey(cursor.getString(3) ?: ""),
                        speakerKey = StoryOcrNormalizer.nameKey(cursor.getString(2) ?: ""),
                        scriptId = cursor.getString(1) ?: "",
                        seq = cursor.getInt(0),
                        cn = cursor.getString(4) ?: "",
                        tw = cursor.getString(5) ?: "",
                        en = cursor.getString(6) ?: ""
                    )
                )
            }
        }
        return entries
    }

    private fun buildSlotIndex(): SlotIndex {
        val dialogue = ArrayList<StoryFuzzyMatcher.Entry>(16)
        val choices = ArrayList<StoryFuzzyMatcher.Entry>(4)
        db.rawQuery(
            "SELECT seq, script_id, jp_name, jp_norm, cn_dialog, tw_dialog, en_dialog, type " +
                "FROM entries WHERE instr(jp_norm, '%') > 0 ORDER BY seq",
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val template = StoryOcrNormalizer.slotTemplateKey(cursor.getString(3) ?: "")
                if (template.isEmpty()) continue
                val entry = StoryFuzzyMatcher.Entry(
                    id = cursor.getInt(0),
                    key = template,
                    speakerKey = StoryOcrNormalizer.normalizeName(cursor.getString(2) ?: ""),
                    scriptId = cursor.getString(1) ?: "",
                    seq = cursor.getInt(0),
                    cn = cursor.getString(4) ?: "",
                    tw = cursor.getString(5) ?: "",
                    en = cursor.getString(6) ?: ""
                )
                if (cursor.getString(7) == "choice") choices.add(entry) else dialogue.add(entry)
            }
        }
        return SlotIndex(dialogue, choices)
    }

    private fun buildFuzzyIndex(): FuzzyIndex {
        val dialogue = ArrayList<StoryFuzzyMatcher.Entry>(entryCount)
        val choices = ArrayList<StoryFuzzyMatcher.Entry>(32)
        db.rawQuery(
            "SELECT seq, script_id, jp_name, jp_norm, cn_dialog, tw_dialog, en_dialog, type " +
                "FROM entries WHERE jp_norm <> '' ORDER BY seq",
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val entry = StoryFuzzyMatcher.Entry(
                    id = cursor.getInt(0),
                    key = StoryOcrNormalizer.foldKey(cursor.getString(3) ?: ""),
                    speakerKey = StoryOcrNormalizer.normalizeName(cursor.getString(2) ?: ""),
                    scriptId = cursor.getString(1) ?: "",
                    seq = cursor.getInt(0),
                    cn = cursor.getString(4) ?: "",
                    tw = cursor.getString(5) ?: "",
                    en = cursor.getString(6) ?: ""
                )
                if (entry.key.isEmpty()) continue
                if (cursor.getString(7) == "choice") choices.add(entry) else dialogue.add(entry)
            }
        }
        return FuzzyIndex(dialogue, choices)
    }

    fun nextAfter(scriptId: String, position: Int): Row? {
        return query(
            "WHERE script_id = ? AND position > ? ORDER BY position LIMIT 1",
            arrayOf(scriptId, position.toString())
        ).firstOrNull()
    }

    fun openingHashes(): List<String> {
        val result = ArrayList<String>()
        db.rawQuery("SELECT jp_norm FROM script_openings", null).use { cursor ->
            while (cursor.moveToNext()) {
                val normalized = cursor.getString(0) ?: continue
                if (normalized.isNotEmpty()) result.add(StoryOcrNormalizer.sha1(normalized))
            }
        }
        return result
    }

    fun close() {
        runCatching { db.close() }
    }

    private fun query(where: String, args: Array<String>): List<Row> {
        val sql = "SELECT seq, script_id, position, type, jp_name, jp_hash, choice_group, " +
            "cn_name, cn_dialog, tw_name, tw_dialog, en_name, en_dialog FROM entries $where"
        val rows = ArrayList<Row>()
        db.rawQuery(sql, args).use { cursor ->
            while (cursor.moveToNext()) {
                rows.add(
                    Row(
                        seq = cursor.getInt(0),
                        scriptId = cursor.getString(1) ?: "",
                        position = cursor.getInt(2),
                        type = cursor.getString(3) ?: "",
                        jpName = cursor.getString(4) ?: "",
                        jpHash = cursor.getString(5) ?: "",
                        choiceGroup = cursor.getString(6),
                        cnName = cursor.getString(7) ?: "",
                        cnDialog = cursor.getString(8) ?: "",
                        twName = cursor.getString(9) ?: "",
                        twDialog = cursor.getString(10) ?: "",
                        enName = cursor.getString(11) ?: "",
                        enDialog = cursor.getString(12) ?: ""
                    )
                )
            }
        }
        return rows
    }

    companion object {
        private const val MAX_CANDIDATES = 24
        private const val SCHEMA_VERSION = 1

        /** Extra plate glyphs a name capture may carry and still match. */
        private const val MAX_NAME_SUFFIX = 2

        /** Opens and validates [file]; throws [IllegalStateException] when unusable. */
        fun open(file: File, expectedChapterId: String?): StoryChapterDatabase {
            require(file.isFile && file.length() > 0L) { "Chapter database is missing or empty" }
            val db = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            try {
                val schema = db.rawQuery("PRAGMA user_version", null).use { cursor ->
                    if (cursor.moveToFirst()) cursor.getInt(0) else 0
                }
                check(schema == SCHEMA_VERSION) { "Unsupported chapter schema: $schema" }
                check(tableExists(db, "entries")) { "Chapter database has no entries table" }
                check(tableExists(db, "meta")) { "Chapter database has no meta table" }
                val chapterId = meta(db, "chapter_id")
                check(chapterId.isNotBlank()) { "Chapter database has no chapter_id" }
                if (expectedChapterId != null) {
                    check(chapterId == expectedChapterId) {
                        "Chapter database belongs to $chapterId, expected $expectedChapterId"
                    }
                }
                val entryCount = db.rawQuery("SELECT COUNT(*) FROM entries", null).use { cursor ->
                    if (cursor.moveToFirst()) cursor.getInt(0) else 0
                }
                val expiring = entriesCount(db)
                check(entryCount > 0 && entryCount == expiring) { "Chapter database entry count mismatch" }
                return StoryChapterDatabase(
                    db = db,
                    chapterId = chapterId,
                    contentVersion = meta(db, "content_version"),
                    status = meta(db, "status"),
                    entryCount = entryCount,
                    names = loadNames(db)
                )
            } catch (error: Throwable) {
                runCatching { db.close() }
                throw error
            }
        }

        private fun entriesCount(db: SQLiteDatabase): Int = db.rawQuery(
            "SELECT COALESCE(CAST(value AS INTEGER), 0) FROM meta WHERE key = 'entry_count'", null
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }

        private fun meta(db: SQLiteDatabase, key: String): String = db.rawQuery(
            "SELECT value FROM meta WHERE key = ?", arrayOf(key)
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) ?: "" else "" }

        private fun tableExists(db: SQLiteDatabase, name: String): Boolean = db.rawQuery(
            "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = ?", arrayOf(name)
        ).use { cursor -> cursor.moveToFirst() && cursor.getInt(0) > 0 }

        private fun loadNames(db: SQLiteDatabase): Map<String, NameTargets> {
            val names = HashMap<String, NameTargets>()
            db.rawQuery(
                "SELECT jp_name, cn_name, tw_name, en_name FROM entries WHERE jp_name <> '' GROUP BY jp_name",
                null
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val key = StoryOcrNormalizer.nameKey(cursor.getString(0) ?: "")
                    if (key.isEmpty() || names.containsKey(key)) continue
                    names[key] = NameTargets(
                        cn = cursor.getString(1) ?: "",
                        tw = cursor.getString(2) ?: "",
                        en = cursor.getString(3) ?: ""
                    )
                }
            }
            return names
        }
    }
}

internal enum class StoryLocale {
    CN, TW, EN;

    fun name(targets: StoryChapterDatabase.NameTargets): String = when (this) {
        CN -> targets.cn
        TW -> targets.tw
        EN -> targets.en
    }

    companion object {
        fun of(targetLocale: String): StoryLocale =
            when (com.fgogotran.data.SettingsRepository.normalizeTargetLanguage(targetLocale)) {
                com.fgogotran.data.SettingsRepository.TARGET_LANGUAGE_TRADITIONAL -> TW
                com.fgogotran.data.SettingsRepository.TARGET_LANGUAGE_ENGLISH -> EN
                else -> CN
            }
    }
}
