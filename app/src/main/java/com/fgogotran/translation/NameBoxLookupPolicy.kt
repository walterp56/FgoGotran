package com.fgogotran.translation

import com.fgogotran.terminology.CharacterNameEntity
import java.text.Normalizer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray

/** Whole visible labels may use the DB; partial names belong only in API glossary guidance. */
internal object NameBoxLookupPolicy {
    // Keep scene-name and standalone-name requests separate, and bypass pre-policy API caches.
    const val SCENE_CACHE_POLICY = "scene-name-label-v2"
    const val SINGLE_CACHE_POLICY = "name-only-label-v3"

    private val repeatedWhitespace = Regex("""\s+""")

    fun findTranslation(
        sourceText: String,
        characters: List<CharacterNameEntity>,
        english: Boolean
    ): String? {
        val sourceKey = lookupKey(sourceText)
        if (sourceKey.isBlank()) return null
        // Rows already have local-profile overrides applied. Preserve their existing priority.
        for (character in characters) {
            val target = if (english) character.enName else character.cnName
            if (target.isBlank()) continue
            if (lookupKey(character.jpName) == sourceKey ||
                storedAliases(character.aliases).any { lookupKey(it) == sourceKey }
            ) {
                return target
            }
        }
        return null
    }

    private fun lookupKey(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFKC)
            .trim()
            .replace(repeatedWhitespace, " ")

    private fun storedAliases(rawAliases: String?): List<String> {
        val raw = rawAliases.orEmpty().trim()
        if (raw.isBlank() || raw == "[]") return emptyList()
        if (raw.startsWith('[')) {
            // JSON aliases may contain commas or quotes; never turn those into invented parts.
            return try {
                Json.parseToJsonElement(raw).jsonArray.mapNotNull { element ->
                    (element as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
                }
            } catch (_: IllegalArgumentException) {
                emptyList()
            }
        }
        // Local/TSV entries also support the existing comma-separated alias format.
        return raw.split(',').map { it.trim().removeSurrounding("\"") }.filter(String::isNotBlank)
    }
}
