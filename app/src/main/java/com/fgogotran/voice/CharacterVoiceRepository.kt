package com.fgogotran.voice

import android.content.Context
import com.fgogotran.util.FgoLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import java.text.Normalizer
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CharacterVoiceRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val tag = "VoiceProfiles"

    private val profiles: List<CharacterVoiceProfile> by lazy { loadProfiles() }
    private val aliasToProfile: Map<String, VoiceProfile> by lazy { loadAliasMap() }
    private val cnNameToJapaneseName: Map<String, String> by lazy { loadChineseNameMap() }

    fun resolveProfileOrNull(
        speakerName: String?
    ): VoiceProfile? {
        val normalizedSpeakerName = speakerName
            ?.let(::normalizeSpeakerName)
            ?.takeIf { it.isNotBlank() }
            ?: return null

        findProfile(normalizedSpeakerName)?.let { return it }

        val japaneseName = mappedJapaneseNameFor(normalizedSpeakerName) ?: return null
        return findProfile(normalizeSpeakerName(japaneseName))
    }

    private fun findProfile(normalizedSpeakerName: String): VoiceProfile? {
        aliasToProfile[normalizedSpeakerName]?.let { return it }
        return aliasToProfile.entries
            .sortedByDescending { it.key.length }
            .firstOrNull { (alias, _) ->
                alias.length >= MIN_PARTIAL_ALIAS_LENGTH && normalizedSpeakerName.contains(alias)
            }
            ?.value
    }

    private fun mappedJapaneseNameFor(normalizedSpeakerName: String): String? {
        cnNameToJapaneseName[normalizedSpeakerName]?.let { return it }
        return cnNameToJapaneseName.entries
            .sortedByDescending { it.key.length }
            .firstOrNull { (cnName, _) ->
                cnName.length >= MIN_CHINESE_PARTIAL_ALIAS_LENGTH &&
                    normalizedSpeakerName.contains(cnName)
            }
            ?.value
    }

    private fun loadProfiles(): List<CharacterVoiceProfile> {
        return runCatching {
            readProfileAsset(CHARACTER_VOICE_PROFILES_CN_ASSET)
        }.onFailure { e ->
            FgoLogger.warn(tag, "Failed to load CN character voice profiles TSV", e)
        }.getOrDefault(emptyList())
    }

    private fun readProfileAsset(assetPath: String): List<CharacterVoiceProfile> {
        return readTsv(assetPath).mapNotNull { columns ->
            if (columns.size < 8) return@mapNotNull null
            val speakerId = columns[0].trim()
            val gender = columns[2].trim()
            val voiceName = columns[3].trim()
            if (speakerId.isBlank()) return@mapNotNull null
            CharacterVoiceProfile(
                speakerId = speakerId,
                aliases = columns[1]
                    .split('|')
                    .map(String::trim)
                    .filter(String::isNotBlank),
                profile = VoiceProfile(
                    profileId = speakerId,
                    provider = AZURE_PROVIDER,
                    locale = VoiceLocaleSupport.localeFromAzureVoiceName(voiceName),
                    voiceName = voiceName,
                    style = columns[4].trim(),
                    pitch = columns[5].trim().ifBlank { "0%" },
                    rate = columns[6].trim().ifBlank { "1.00" },
                    volume = columns[7].trim().ifBlank { "100" },
                    description = gender
                )
            )
        }
    }

    private fun loadAliasMap(): Map<String, VoiceProfile> {
        return runCatching {
            buildMap {
                profiles.forEach { characterProfile ->
                    (listOf(characterProfile.speakerId) + characterProfile.aliases).forEach { name ->
                        val key = normalizeSpeakerName(name)
                        if (key.isNotBlank()) put(key, characterProfile.profile)
                    }
                }
            }
        }.onFailure { e ->
            FgoLogger.warn(tag, "Failed to build CN character voice alias map", e)
        }.getOrDefault(emptyMap())
    }

    private fun loadChineseNameMap(): Map<String, String> {
        return runCatching {
            buildMap {
                readTsv(JP_CN_NAME_MAP_ASSET).forEach { columns ->
                    if (columns.size < 3) return@forEach
                    val japaneseName = columns[0].trim()
                    if (japaneseName.isBlank()) return@forEach

                    listOf(columns[1], columns[2])
                        .flatMap(::splitNameMapAliases)
                        .map(::normalizeSpeakerName)
                        .filter(String::isNotBlank)
                        .forEach { cnName ->
                            putIfAbsent(cnName, japaneseName)
                        }
                }
            }
        }.onFailure { e ->
            FgoLogger.warn(tag, "Failed to load JP/CN voice name map TSV", e)
        }.getOrDefault(emptyMap())
    }

    private fun splitNameMapAliases(value: String): List<String> {
        return value
            .split(Regex("[|/&＆／]"))
            .map(String::trim)
            .filter(String::isNotBlank)
    }

    private fun readTsv(assetPath: String): List<List<String>> {
        return context.assets.open(assetPath).bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.drop(1)
                .map(String::trim)
                .filter { it.isNotBlank() && !it.startsWith("#") }
                .map { line -> line.split('\t') }
                .toList()
        }
    }

    private fun normalizeSpeakerName(name: String): String {
        return Normalizer.normalize(name, Normalizer.Form.NFKC)
            .trim()
            .trim('「', '」', '『', '』', '【', '】', '[', ']', '（', '）', '(', ')')
            .replace(Regex("[\\u30FB\\uFF65\\u00B7\\u2022\\u2219]"), "")
            .replace(Regex("\\s+"), "")
    }

    private companion object {
        const val CHARACTER_VOICE_PROFILES_CN_ASSET = "voice/character_voice_profiles_cn.tsv"
        const val JP_CN_NAME_MAP_ASSET = "voice/jp_cn_name_map.tsv"
        const val AZURE_PROVIDER = "azure"
        const val MIN_PARTIAL_ALIAS_LENGTH = 3
        const val MIN_CHINESE_PARTIAL_ALIAS_LENGTH = 2
    }
}

private data class CharacterVoiceProfile(
    val speakerId: String,
    val aliases: List<String>,
    val profile: VoiceProfile
)
