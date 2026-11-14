package com.fgogotran.scriptmemory

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.fgogotran.data.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import java.text.Normalizer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * OCR-time translation memory built from official Atlas scripts (P1/P2).
 *
 * Packs are opt-in by presence: `filesDir/script_memory/<CN|TW|NA>/all.db`.
 * When a pack is absent every lookup returns null and the caller keeps using the API.
 *
 * Matching: exact normalized hash, then trigram candidates scored by Dice
 * coefficient, length ratio, speaker and previous-script continuity. Ambiguous
 * candidates return null so the API fallback produces the answer instead of a guess.
 */
@Singleton
class ScriptMemory @Inject constructor(
    @ApplicationContext private val context: Context
) {
    data class Hit(
        val text: String,
        val scriptId: String,
        val lineIndex: Int,
        val exact: Boolean
    )

    private data class Row(
        val id: Long,
        val scriptId: String,
        val lineIndex: Int,
        val speaker: String,
        val norm: String,
        val target: String,
        val gramCount: Int
    )

    private val databases = HashMap<String, SQLiteDatabase?>()
    private var lastScript: String? = null

    fun lookup(rawText: String, speaker: String, targetLocale: String): Hit? {
        val language = packLanguage(targetLocale) ?: return null
        val db = database(language) ?: return null
        val normalized = normalizeOcr(rawText)
        if (normalized.length < MIN_QUERY_LENGTH) return null
        synchronized(this) {
            exact(db, normalized, speaker)?.let { row ->
                lastScript = row.scriptId
                return Hit(row.target, row.scriptId, row.lineIndex, exact = true)
            }
            val fuzzy = fuzzy(db, normalized, speaker) ?: return null
            lastScript = fuzzy.scriptId
            return Hit(fuzzy.target, fuzzy.scriptId, fuzzy.lineIndex, exact = false)
        }
    }

    private fun packLanguage(locale: String): String? = when (locale) {
        SettingsRepository.TARGET_LANGUAGE_SIMPLIFIED -> "CN"
        SettingsRepository.TARGET_LANGUAGE_TRADITIONAL -> "TW"
        SettingsRepository.TARGET_LANGUAGE_ENGLISH -> "NA"
        else -> null
    }

    private fun database(language: String): SQLiteDatabase? = databases.getOrPut(language) {
        val file = File(File(context.filesDir, "script_memory/$language"), "all.db")
        if (!file.isFile) return@getOrPut null
        runCatching {
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
        }.getOrNull()
    }

    private fun exact(db: SQLiteDatabase, normalized: String, speaker: String): Row? {
        val rows = queryRows(db, "jp_hash = ?", arrayOf(sha1(normalized)), 12)
        if (rows.isEmpty()) return null
        if (rows.size == 1) return rows.first()
        val normalizedSpeaker = normalizeSpeaker(speaker)
        val sameSpeaker = if (normalizedSpeaker.isEmpty()) emptyList()
        else rows.filter { it.speaker == normalizedSpeaker }
        val pool = sameSpeaker.ifEmpty { rows }
        if (pool.map { it.target }.distinct().size == 1) return pool.first()
        val continuing = lastScript?.let { script -> pool.firstOrNull { it.scriptId == script } }
        return continuing
    }

    private fun fuzzy(db: SQLiteDatabase, normalized: String, speaker: String): Row? {
        val grams = trigrams(normalized).map(::javaHash)
        if (grams.isEmpty()) return null
        val placeholders = grams.joinToString(",") { "?" }
        val candidates = ArrayList<Pair<Long, Int>>(64)
        db.rawQuery(
            "SELECT line_id, COUNT(*) AS shared FROM grams WHERE gram_hash IN ($placeholders) " +
                "GROUP BY line_id ORDER BY shared DESC LIMIT 60",
            grams.map { it.toString() }.toTypedArray()
        ).use { cursor ->
            while (cursor.moveToNext()) candidates.add(cursor.getLong(0) to cursor.getInt(1))
        }
        if (candidates.isEmpty()) return null
        val byId = queryByIds(db, candidates.map { it.first })
        if (byId.isEmpty()) return null

        var best: Row? = null
        var bestScore = 0.0
        var rival = 0.0
        var rivalTarget: String? = null
        for ((id, shared) in candidates) {
            val row = byId[id] ?: continue
            val dice = (2.0 * shared) / (grams.size + row.gramCount)
            val lengthPenalty = minOf(normalized.length, row.norm.length).toDouble() /
                maxOf(normalized.length, row.norm.length)
            var score = dice * (0.7 + 0.3 * lengthPenalty)
            if (speaker.isNotEmpty() && row.speaker == normalizeSpeaker(speaker)) score += 0.05
            if (lastScript != null && row.scriptId == lastScript) score += 0.02
            if (score > bestScore) {
                if (best != null && best.target != row.target) { rival = bestScore; rivalTarget = best.target }
                best = row
                bestScore = score
            } else if (row.target != best?.target && score > rival) {
                rival = score
                rivalTarget = row.target
            }
        }
        val bestRow = best ?: return null
        if (bestScore < MIN_SCORE) return null
        if (rivalTarget != null && rivalTarget != bestRow.target && bestScore - rival < AMBIGUITY_MARGIN) return null
        return bestRow
    }

    private fun queryRows(db: SQLiteDatabase, where: String, args: Array<String>, limit: Int): List<Row> {
        val rows = ArrayList<Row>(limit)
        db.rawQuery(
            "SELECT id, script_id, line_index, speaker_norm, jp_norm, target_raw, gram_count " +
                "FROM lines WHERE $where LIMIT $limit",
            args
        ).use { cursor ->
            while (cursor.moveToNext()) rows.add(readRow(cursor))
        }
        return rows
    }

    private fun queryByIds(db: SQLiteDatabase, ids: List<Long>): Map<Long, Row> {
        val result = HashMap<Long, Row>(ids.size)
        ids.chunked(400).forEach { chunk ->
            val placeholders = chunk.joinToString(",") { "?" }
            db.rawQuery(
                "SELECT id, script_id, line_index, speaker_norm, jp_norm, target_raw, gram_count " +
                    "FROM lines WHERE id IN ($placeholders)",
                chunk.map { it.toString() }.toTypedArray()
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val row = readRow(cursor)
                    result[row.id] = row
                }
            }
        }
        return result
    }

    private fun readRow(cursor: android.database.Cursor) = Row(
        id = cursor.getLong(0),
        scriptId = cursor.getString(1),
        lineIndex = cursor.getInt(2),
        speaker = cursor.getString(3) ?: "",
        norm = cursor.getString(4) ?: "",
        target = cursor.getString(5) ?: "",
        gramCount = cursor.getInt(6)
    )

    private fun speakerOf(normalized: String): String = normalizeSpeaker(normalized)
    companion object {
        internal const val MIN_QUERY_LENGTH = 4
        internal const val MIN_SCORE = 0.55
        internal const val AMBIGUITY_MARGIN = 0.03

        private val ruby = Regex("〈[^〉]*〉")
        private val spaces = Regex("[\\s\\u3000]+")
        private val punctuation = Regex("[。、，．,.！？!?…‥・：:；;「」『』（）()\\[\\]］［〈〉《》]")

        /** OCR text may carry ruby as base〈reading〉; the corpus stores only the base. */
        internal fun normalizeOcr(raw: String): String {
            var value = raw.replace(ruby, "")
            value = Normalizer.normalize(value, Normalizer.Form.NFKC)
            value = value.replace(spaces, "")
            value = value.replace(punctuation, "")
            return value
        }

        internal fun normalizeSpeaker(raw: String): String =
            raw.replace(spaces, "").trim()

        internal fun trigrams(value: String): Set<String> {
            if (value.length <= 3) return if (value.isEmpty()) emptySet() else setOf(value)
            val result = LinkedHashSet<String>(value.length)
            for (index in 0..value.length - 3) result.add(value.substring(index, index + 3))
            return result
        }

        internal fun javaHash(value: String): Int {
            var hash = 0
            for (index in value.indices) hash = 31 * hash + value[index].code
            return hash
        }

        internal fun sha1(value: String): String =
            MessageDigest.getInstance("SHA-1").digest(value.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }

        /** Convert script markup in the official text to the overlay's display form. */
        internal fun renderOfficial(raw: String, playerName: String, gender: String): String {
            var value = raw.replace(Regex("\\[#([^:\\]]+):([^\\]]+)\\]"), "$1〈$2〉")
            value = value.replace(Regex("\\[&([^:\\]]+):([^\\]]+)\\]")) { match ->
                if (gender == "female") match.groupValues[2] else match.groupValues[1]
            }
            val name = playerName.ifBlank { "藤丸立香" }
            value = value.replace("[%1]", name)
            value = value.replace(Regex("\\[line \\d+\\]"), "")
            value = value.replace(Regex("\\[(?:[0-9a-fA-F]{6}|-|sr)\\]"), "")
            value = value.replace("[r]", "\n")
            value = value.replace(Regex("\\[[a-z]+ [^\\]]*\\]"), "")
            return value.trim()
        }
    }
}
