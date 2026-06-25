package com.fgogotran.translation

import android.content.Context
import com.fgogotran.util.FgoLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.BufferedReader
import java.io.InputStreamReader
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Optional local JP -> official CN script memory.
 *
 * Put a UTF-8 TSV file at one of:
 * - assets/translation_memory/official_cn.tsv
 * - assets/official_cn.tsv
 * - assets/translation_memory.tsv
 *
 * Format: Japanese text<TAB>Official Chinese text
 * Use "\n" inside a field to represent line breaks.
 */
@Singleton
class TranslationMemory @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val tag = "TranslationMemory"

    @Volatile
    private var loadedEntries: Map<String, String>? = null

    fun lookup(japaneseText: String): String? {
        val normalized = TextNormalizer.normalizeForTranslation(japaneseText)
        return lookupNormalized(normalized)
    }

    fun lookupNormalized(normalizedJapaneseText: String): String? {
        if (normalizedJapaneseText.isBlank()) return null
        val loadedEntries = entries()
        loadedEntries[normalizedJapaneseText]?.let { return it }
        val withoutRuby = TextNormalizer.stripRubyAnnotations(normalizedJapaneseText)
        return if (withoutRuby != normalizedJapaneseText) {
            loadedEntries[withoutRuby]
        } else {
            null
        }
    }

    private fun entries(): Map<String, String> {
        loadedEntries?.let { return it }
        synchronized(this) {
            loadedEntries?.let { return it }
            val loaded = loadEntries()
            loadedEntries = loaded
            return loaded
        }
    }

    private fun loadEntries(): Map<String, String> {
        val assets = context.assets
        val candidates = listOf(
            "translation_memory/official_cn.tsv",
            "official_cn.tsv",
            "translation_memory.tsv"
        )

        for (path in candidates) {
            val entries = runCatching {
                assets.open(path).use { input ->
                    BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { reader ->
                        parseTsv(reader)
                    }
                }
            }.getOrNull()

            if (!entries.isNullOrEmpty()) {
                FgoLogger.info(tag, "Loaded ${entries.size} official CN memory entries from $path")
                return entries
            }
        }

        FgoLogger.info(tag, "No official CN translation memory asset found")
        return emptyMap()
    }

    private fun parseTsv(reader: BufferedReader): Map<String, String> {
        val result = linkedMapOf<String, String>()
        reader.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isBlank() || line.startsWith("#")) return@forEach

            val columns = line.split('\t', limit = 2)
            if (columns.size < 2) return@forEach

            val japanese = columns[0].unescapeLineBreaks()
            val chinese = columns[1].unescapeLineBreaks()
            val key = TextNormalizer.normalizeForTranslation(japanese)
            if (key.isNotBlank() && chinese.isNotBlank()) {
                result[key] = chinese
            }
        }
        return result
    }

    private fun String.unescapeLineBreaks(): String {
        return trim().replace("\\n", "\n")
    }
}
