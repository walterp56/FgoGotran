package com.fgogotran.voice

import java.io.File

/**
 * Pure TSV integrity checks for the voice data package.
 *
 * Extracted from [VoiceDataUpdateManager] so the header/schema rules can be unit tested
 * without an Android context. The name map may ship as the legacy 4-column layout or as
 * the 5-column layout that adds the English (NA) name box column.
 */
internal object VoiceDataTsvValidator {
    fun validateFile(
        file: File,
        expectedHeaders: List<List<String>>,
        expectedMinColumns: Int,
        expectedCount: Int,
        validateRow: (lineNo: Int, columns: List<String>) -> Unit
    ): Int {
        require(file.exists() && file.length() > 0L) { "Missing TSV file: ${file.name}" }
        val rows = file.bufferedReader(Charsets.UTF_8).useLines { lines ->
            val iterator = lines.iterator()
            require(iterator.hasNext()) { "Missing TSV header: ${file.name}" }
            val header = parseLine(iterator.next().removePrefix("\uFEFF"))
            require(header in expectedHeaders) {
                "Unexpected TSV header for ${file.name}: ${header.joinToString("|")}"
            }
            var count = 0
            val seenKeys = mutableSetOf<String>()
            while (iterator.hasNext()) {
                val line = iterator.next().trimEnd('\r', '\n')
                if (line.isBlank() || line.startsWith("#")) continue
                val columns = parseLine(line)
                require(columns.size >= expectedMinColumns) {
                    "Too few TSV columns in ${file.name}:$count"
                }
                validateRow(count + 2, columns)
                val key = columns.first().trim()
                require(key !in seenKeys) { "Duplicate key $key in ${file.name}" }
                seenKeys.add(key)
                count += 1
            }
            count
        }
        require(rows == expectedCount) {
            "TSV row count mismatch for ${file.name}: expected=$expectedCount, actual=$rows"
        }
        return rows
    }

    fun validateProfileRow(lineNo: Int, columns: List<String>) {
        require(columns[0].trim().isNotBlank()) { "Blank speaker_id at profile:$lineNo" }
        require(columns[3].trim().isNotBlank()) { "Blank voice name at profile:$lineNo" }
    }

    fun validateNameMapRow(lineNo: Int, columns: List<String>) {
        require(columns[0].trim().isNotBlank()) { "Blank jp_name at name map:$lineNo" }
        require(!containsKana(columns[1])) {
            "Japanese kana in cn_name_simp at name map:$lineNo"
        }
        if (columns.size >= 5) {
            require(!containsKana(columns[3])) {
                "Japanese kana in en_name at name map:$lineNo"
            }
        }
    }

    fun containsKana(value: String): Boolean {
        return value.any { char -> char in '\u3040'..'\u30ff' }
    }

    private fun parseLine(line: String): List<String> {
        return line.split('\t')
    }
}
