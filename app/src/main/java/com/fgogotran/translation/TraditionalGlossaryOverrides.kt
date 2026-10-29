package com.fgogotran.translation

import com.fgogotran.data.SettingsRepository
import java.security.MessageDigest
import java.text.Normalizer

internal const val TRADITIONAL_GLOSSARY_ASSET = "translation/traditional_glossary_overrides.tsv"

internal fun usesTraditionalGlossaryOverrides(targetLanguage: String, general: Boolean): Boolean =
    general && SettingsRepository.normalizeTargetLanguage(targetLanguage) ==
        SettingsRepository.TARGET_LANGUAGE_TRADITIONAL

internal data class TraditionalGlossaryOverride(
    val source: String,
    val reference: String,
    val target: String
)

/** Character names and terms share this index, after an existing glossary entry was matched. */
internal class TraditionalGlossaryOverrides private constructor(
    private val entries: Map<String, TraditionalGlossaryOverride>,
    private val sourceKey: (String) -> String
) {
    val cacheIdentity: String = "general-hant-glossary-v1:" + MessageDigest.getInstance("SHA-256")
        .digest(entries.entries.sortedBy { it.key }.joinToString("\n") { (key, row) ->
            "$key\t${row.reference}\t${row.target}"
        }.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    fun findTarget(
        sources: List<String>,
        reference: String,
        convertedReference: ((String) -> String)? = null
    ): String? {
        val actual = reference.trim()
        for (source in sources) {
            val entry = entries[sourceKey(source)] ?: continue
            if (actual == entry.reference || actual == entry.target ||
                (convertedReference != null && actual == convertedReference(entry.reference).trim())
            ) return entry.target
        }
        return null
    }

    companion object {
        private val header = listOf("jp_source", "cn_reference", "zh_hant")
        val EMPTY = parse(emptySequence())

        fun parse(
            lines: Sequence<String>,
            sourceKey: (String) -> String = { Normalizer.normalize(it, Normalizer.Form.NFKC).trim() }
        ): TraditionalGlossaryOverrides {
            val rows = lines.mapIndexed { index, line -> index + 1 to line.removeSuffix("\r") }
                .filter { (_, line) -> line.isNotBlank() && !line.trimStart().startsWith('#') }
                .toList()
            if (rows.isEmpty()) return TraditionalGlossaryOverrides(emptyMap(), sourceKey)
            require(rows.first().second.removePrefix("\uFEFF").split('\t') == header) {
                "Traditional glossary header must be: ${header.joinToString("\\t")}"
            }
            val entries = linkedMapOf<String, TraditionalGlossaryOverride>()
            for ((number, line) in rows.drop(1)) {
                val fields = line.split('\t').map(String::trim)
                require(fields.size == 3 && fields.none(String::isBlank)) {
                    "Traditional glossary line $number must have three nonempty fields"
                }
                require(fields.none { value -> value.any { it.isISOControl() } }) {
                    "Control character in traditional glossary line $number"
                }
                val key = sourceKey(fields[0])
                require(key.isNotBlank() && key !in entries) {
                    "Duplicate or blank traditional glossary source at line $number"
                }
                entries[key] = TraditionalGlossaryOverride(fields[0], fields[1], fields[2])
            }
            return TraditionalGlossaryOverrides(entries, sourceKey)
        }
    }
}
