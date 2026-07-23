package com.fgogotran.voice

import android.content.Context
import com.fgogotran.data.SettingsRepository
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

    private val profiles: Map<String, VoiceProfile> by lazy { loadProfiles() }
    private val aliasToProfileIds: Map<String, VoiceProfileIds> by lazy { loadAliasMap() }

    fun resolveProfileOrNull(
        speakerName: String?,
        voiceLanguage: String
    ): VoiceProfile? {
        val profileIds = speakerName
            ?.let(::normalizeSpeakerName)
            ?.takeIf { it.isNotBlank() }
            ?.let(::findProfileIds)
            ?: return null
        val profileId = profileIds.forVoiceLanguage(voiceLanguage)

        return profiles[profileId]
    }

    fun resolveProfile(speakerName: String?, voiceLanguage: String): VoiceProfile {
        val defaultProfileId = defaultProfileIdFor(voiceLanguage)
        val profileId = speakerName
            ?.let(::normalizeSpeakerName)
            ?.takeIf { it.isNotBlank() }
            ?.let(::findProfileIds)
            ?.forVoiceLanguage(voiceLanguage)
            ?: defaultProfileId

        return profiles[profileId]
            ?: profiles[defaultProfileId]
            ?: fallbackProfile()
    }

    private fun findProfileIds(normalizedSpeakerName: String): VoiceProfileIds? {
        aliasToProfileIds[normalizedSpeakerName]?.let { return it }
        return aliasToProfileIds.entries
            .sortedByDescending { it.key.length }
            .firstOrNull { (alias, _) ->
                alias.length >= MIN_PARTIAL_ALIAS_LENGTH && normalizedSpeakerName.contains(alias)
            }
            ?.value
    }

    private fun loadProfiles(): Map<String, VoiceProfile> {
        return runCatching {
            readTsv(VOICE_PROFILES_ASSET).mapNotNull { columns ->
                if (columns.size < 9) return@mapNotNull null
                VoiceProfile(
                    profileId = columns[0],
                    provider = columns[1],
                    locale = columns[2],
                    voiceName = columns[3],
                    style = columns[4],
                    pitch = columns[5],
                    rate = columns[6],
                    volume = columns[7],
                    description = columns[8]
                )
            }.associateBy { it.profileId }
        }.onFailure { e ->
            FgoLogger.warn(tag, "Failed to load voice profiles TSV", e)
        }.getOrDefault(emptyMap())
    }

    private fun loadAliasMap(): Map<String, VoiceProfileIds> {
        return runCatching {
            buildMap {
                readTsv(CHARACTER_VOICE_MAP_ASSET).forEach { columns ->
                    if (columns.size < 4) return@forEach
                    val jpName = columns[0]
                    val cnName = columns[1]
                    val aliases = columns[2]
                        .split('|')
                        .map(String::trim)
                        .filter(String::isNotBlank)
                    val jpProfileId = columns[3]
                    val cnProfileId = columns.getOrNull(4)?.takeIf { it.isNotBlank() } ?: jpProfileId
                    val profileIds = VoiceProfileIds(jpProfileId, cnProfileId)
                    (listOf(jpName, cnName) + aliases).forEach { name ->
                        val key = normalizeSpeakerName(name)
                        if (key.isNotBlank()) put(key, profileIds)
                    }
                }
            }
        }.onFailure { e ->
            FgoLogger.warn(tag, "Failed to load character voice map TSV", e)
        }.getOrDefault(emptyMap())
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

    private fun VoiceProfileIds.forVoiceLanguage(voiceLanguage: String): String {
        return when (SettingsRepository.normalizeAiVoiceLanguage(voiceLanguage)) {
            SettingsRepository.AI_VOICE_LANGUAGE_CN_TRANSLATION -> cnProfileId
            else -> jpProfileId
        }
    }

    private fun defaultProfileIdFor(voiceLanguage: String): String {
        return when (SettingsRepository.normalizeAiVoiceLanguage(voiceLanguage)) {
            SettingsRepository.AI_VOICE_LANGUAGE_CN_TRANSLATION -> DEFAULT_CN_PROFILE_ID
            else -> DEFAULT_JP_PROFILE_ID
        }
    }

    private fun fallbackProfile(): VoiceProfile = VoiceProfile(
        profileId = DEFAULT_JP_PROFILE_ID,
        provider = "azure",
        locale = "ja-JP",
        voiceName = "ja-JP-NanamiNeural",
        style = "",
        pitch = "0%",
        rate = "1.00",
        volume = "100",
        description = "Default Japanese narrator style"
    )

    private companion object {
        const val VOICE_PROFILES_ASSET = "voice/voice_profiles.tsv"
        const val CHARACTER_VOICE_MAP_ASSET = "voice/character_voice_map.tsv"
        const val DEFAULT_JP_PROFILE_ID = "default_narrator"
        const val DEFAULT_CN_PROFILE_ID = "default_cn_narrator"
        const val MIN_PARTIAL_ALIAS_LENGTH = 3
    }
}

private data class VoiceProfileIds(
    val jpProfileId: String,
    val cnProfileId: String
)
