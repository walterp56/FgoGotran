package com.fgogotran.translation

import android.content.Context
import com.fgogotran.util.FgoLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import java.text.Normalizer
import javax.inject.Inject
import javax.inject.Singleton

data class CharacterContextProfile internal constructor(
    val speakerId: String,
    val aliases: List<String>,
    val generalPrompt: String,
    val sakuraPrompt: String
) {
    internal fun promptFor(isSakuraModel: Boolean): String =
        if (isSakuraModel) sakuraPrompt else generalPrompt

    internal fun cacheIdentityFor(isSakuraModel: Boolean): String = listOf(
        speakerId,
        if (isSakuraModel) "sakura" else "general",
        promptFor(isSakuraModel)
    ).joinToString("\u001D")
}

@Singleton
class CharacterContextRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val index by lazy(LazyThreadSafetyMode.SYNCHRONIZED, ::loadIndex)

    fun warmUp() {
        index
    }

    fun resolveProfileOrNull(japaneseSpeakerName: String?): CharacterContextProfile? {
        val key = normalizeCharacterContextSpeakerName(japaneseSpeakerName.orEmpty())
        if (key.isBlank()) return null
        return index.profilesByAlias[key]
    }

    private fun loadIndex(): CharacterContextIndex {
        return runCatching {
            context.assets.open(ASSET_PATH).bufferedReader(Charsets.UTF_8).use { reader ->
                buildCharacterContextIndex(parseCharacterContextProfiles(reader.lineSequence()))
            }
        }.onSuccess { loaded ->
            FgoLogger.info(
                TAG,
                "Loaded character contexts: profiles=${loaded.profileCount}, " +
                    "aliases=${loaded.profilesByAlias.size}, ambiguous=${loaded.ambiguousAliasCount}"
            )
        }.onFailure { error ->
            FgoLogger.warn(TAG, "Failed to load character context profiles", error)
        }.getOrDefault(CharacterContextIndex.EMPTY)
    }

    private companion object {
        const val TAG = "CharacterContext"
        const val ASSET_PATH = "translation/character_context_prompts.tsv"
    }
}

internal data class CharacterContextIndex(
    val profilesByAlias: Map<String, CharacterContextProfile>,
    val profileCount: Int,
    val ambiguousAliasCount: Int
) {
    companion object {
        val EMPTY = CharacterContextIndex(emptyMap(), 0, 0)
    }
}

internal fun parseCharacterContextProfiles(
    lines: Sequence<String>
): List<CharacterContextProfile> {
    val rows = lines
        .mapIndexed { index, line -> index + 1 to line.trimEnd('\r', '\n') }
        .filter { (_, line) -> line.isNotBlank() && !line.startsWith('#') }
        .toList()
    if (rows.isEmpty()) return emptyList()

    val header = rows.first().second.removePrefix("\uFEFF").split('\t')
    require(header == CHARACTER_CONTEXT_HEADER) {
        "Character context TSV header must be: ${CHARACTER_CONTEXT_HEADER.joinToString("\\t")}"
    }

    return rows.drop(1).map { (lineNumber, line) ->
        val columns = line.split('\t', limit = CHARACTER_CONTEXT_HEADER.size)
        require(columns.size == CHARACTER_CONTEXT_HEADER.size) {
            "Character context TSV line $lineNumber must have ${CHARACTER_CONTEXT_HEADER.size} columns"
        }
        val speakerId = columns[0].trim()
        val aliases = columns[1]
            .split('|')
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
        val generalPrompt = columns[2].normalizeCharacterContextPrompt()
        val sakuraPrompt = columns[3].normalizeCharacterContextPrompt()
        require(speakerId.isNotBlank()) { "Blank speaker_id at character context TSV line $lineNumber" }
        require(generalPrompt.isNotBlank()) {
            "Blank prompt_general at character context TSV line $lineNumber"
        }
        require(sakuraPrompt.isNotBlank()) {
            "Blank prompt_sakura at character context TSV line $lineNumber"
        }
        require(generalPrompt.length <= CHARACTER_CONTEXT_PROMPT_MAX_CHARS) {
            "Character context prompt_general is too long at line $lineNumber: ${generalPrompt.length} chars"
        }
        require(sakuraPrompt.length <= CHARACTER_CONTEXT_PROMPT_MAX_CHARS) {
            "Character context prompt_sakura is too long at line $lineNumber: ${sakuraPrompt.length} chars"
        }
        CharacterContextProfile(
            speakerId = speakerId,
            aliases = aliases,
            generalPrompt = generalPrompt,
            sakuraPrompt = sakuraPrompt
        )
    }
}

internal fun buildCharacterContextIndex(
    profiles: List<CharacterContextProfile>
): CharacterContextIndex {
    val duplicateSpeakerIds = profiles
        .groupBy { normalizeCharacterContextSpeakerName(it.speakerId) }
        .filterValues { it.size > 1 }
        .keys
    require(duplicateSpeakerIds.isEmpty()) {
        "Duplicate character context speaker_id: ${duplicateSpeakerIds.joinToString()}"
    }

    val candidatesByAlias = linkedMapOf<String, MutableList<CharacterContextProfile>>()
    profiles.forEach { profile ->
        (listOf(profile.speakerId) + profile.aliases).forEach { alias ->
            val key = normalizeCharacterContextSpeakerName(alias)
            if (key.isNotBlank()) {
                candidatesByAlias.getOrPut(key, ::mutableListOf).add(profile)
            }
        }
    }

    var ambiguousAliasCount = 0
    val profilesByAlias = buildMap {
        candidatesByAlias.forEach { (alias, candidates) ->
            val uniqueCandidates = candidates.distinctBy(CharacterContextProfile::speakerId)
            if (uniqueCandidates.size == 1) {
                put(alias, uniqueCandidates.single())
            } else {
                ambiguousAliasCount++
            }
        }
    }
    return CharacterContextIndex(
        profilesByAlias = profilesByAlias,
        profileCount = profiles.distinctBy(CharacterContextProfile::speakerId).size,
        ambiguousAliasCount = ambiguousAliasCount
    )
}

internal fun normalizeCharacterContextSpeakerName(name: String): String {
    return Normalizer.normalize(name, Normalizer.Form.NFKC)
        .replace(Regex("[\\s　]+"), "")
        .trim()
}

private fun String.normalizeCharacterContextPrompt(): String =
    replace(Regex("\\s+"), " ").trim()

private val CHARACTER_CONTEXT_HEADER =
    listOf("speaker_id", "aliases", "prompt_general", "prompt_sakura")
private const val CHARACTER_CONTEXT_PROMPT_MAX_CHARS = 800
