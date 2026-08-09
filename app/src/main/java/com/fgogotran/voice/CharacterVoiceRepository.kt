package com.fgogotran.voice

import android.content.Context
import com.fgogotran.util.FgoLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CharacterVoiceRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val tag = "VoiceProfiles"
    private val lock = Any()

    @Volatile
    private var snapshot: VoiceDataSnapshot? = null

    fun resolveProfileOrNull(
        speakerName: String?
    ): VoiceProfile? {
        val normalizedSpeakerName = speakerName
            ?.let(::normalizeSpeakerName)
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val data = currentSnapshot()

        findProfile(normalizedSpeakerName, data)?.let { return it }

        val japaneseName = mappedJapaneseNameFor(normalizedSpeakerName, data) ?: return null
        return findProfile(normalizeSpeakerName(japaneseName), data)
    }

    fun reload() {
        synchronized(lock) {
            snapshot = loadSnapshot()
        }
    }

    private fun currentSnapshot(): VoiceDataSnapshot {
        snapshot?.let { return it }
        return synchronized(lock) {
            snapshot ?: loadSnapshot().also { snapshot = it }
        }
    }

    private fun findProfile(
        normalizedSpeakerName: String,
        data: VoiceDataSnapshot
    ): VoiceProfile? {
        data.aliasToProfile[normalizedSpeakerName]?.let { return it }
        return data.aliasEntriesByLength
            .firstOrNull { (alias, _) ->
                alias.length >= MIN_PARTIAL_ALIAS_LENGTH && normalizedSpeakerName.contains(alias)
            }
            ?.second
    }

    private fun mappedJapaneseNameFor(
        normalizedSpeakerName: String,
        data: VoiceDataSnapshot
    ): String? {
        data.cnNameToJapaneseName[normalizedSpeakerName]?.let { return it }
        return data.cnNameEntriesByLength
            .firstOrNull { (cnName, _) ->
                cnName.length >= MIN_CHINESE_PARTIAL_ALIAS_LENGTH &&
                    normalizedSpeakerName.contains(cnName)
            }
            ?.second
    }

    private fun loadSnapshot(): VoiceDataSnapshot {
        return runCatching {
            val rows = loadInstalledRowsOrNull() ?: loadAssetRows()
            val profiles = readProfiles(rows.profileRows)
            val aliasToProfile = buildAliasMap(profiles)
            val cnNameToJapaneseName = buildChineseNameMap(rows.nameMapRows)
            FgoLogger.info(
                tag,
                "Loaded voice data source=${rows.source}, profiles=${profiles.size}, nameMap=${cnNameToJapaneseName.size}"
            )
            VoiceDataSnapshot(
                profiles = profiles,
                aliasToProfile = aliasToProfile,
                aliasEntriesByLength = aliasToProfile.entries
                    .map { it.key to it.value }
                    .sortedByDescending { it.first.length },
                cnNameToJapaneseName = cnNameToJapaneseName,
                cnNameEntriesByLength = cnNameToJapaneseName.entries
                    .map { it.key to it.value }
                    .sortedByDescending { it.first.length }
            )
        }.onFailure { e ->
            FgoLogger.warn(tag, "Failed to load voice data", e)
        }.getOrDefault(VoiceDataSnapshot.EMPTY)
    }

    private fun loadInstalledRowsOrNull(): LoadedVoiceRows? {
        if (!VoiceDataFiles.installedPackageExists(context)) return null
        return runCatching {
            val profileRows = readTsvFile(VoiceDataFiles.installedProfileFile(context))
            val nameMapRows = readTsvFile(VoiceDataFiles.installedNameMapFile(context))
            require(profileRows.isNotEmpty()) { "Installed voice profile TSV has no rows" }
            require(nameMapRows.isNotEmpty()) { "Installed JP/CN name map TSV has no rows" }
            LoadedVoiceRows(
                source = "installed",
                profileRows = profileRows,
                nameMapRows = nameMapRows
            )
        }.onFailure { e ->
            FgoLogger.warn(tag, "Installed voice data is invalid; falling back to bundled assets", e)
        }.getOrNull()
    }

    private fun loadAssetRows(): LoadedVoiceRows {
        return LoadedVoiceRows(
            source = "asset",
            profileRows = readTsvAsset(VoiceDataFiles.PROFILE_ASSET),
            nameMapRows = readTsvAsset(VoiceDataFiles.NAME_MAP_ASSET)
        )
    }

    private fun readProfiles(rows: List<List<String>>): List<CharacterVoiceProfile> {
        return rows.mapNotNull { columns ->
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

    private fun buildAliasMap(profiles: List<CharacterVoiceProfile>): Map<String, VoiceProfile> {
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

    private fun buildChineseNameMap(rows: List<List<String>>): Map<String, String> {
        return runCatching {
            buildMap {
                rows.forEach { columns ->
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
            .split(Regex("[|/&\\uFF06\\uFF0F]"))
            .map(String::trim)
            .filter(String::isNotBlank)
    }

    private fun readTsvAsset(assetPath: String): List<List<String>> {
        return context.assets.open(assetPath).bufferedReader(Charsets.UTF_8).useLines { lines ->
            parseTsvLines(lines)
        }
    }

    private fun readTsvFile(file: File): List<List<String>> {
        return file.bufferedReader(Charsets.UTF_8).useLines { lines ->
            parseTsvLines(lines)
        }
    }

    private fun parseTsvLines(lines: Sequence<String>): List<List<String>> {
        return lines.drop(1)
            .map { line -> line.trimEnd('\r', '\n') }
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { line -> line.split('\t') }
            .toList()
    }

    private fun normalizeSpeakerName(name: String): String {
        return VoiceNameNormalizer.normalize(name)
    }

    private companion object {
        const val AZURE_PROVIDER = "azure"
        const val MIN_PARTIAL_ALIAS_LENGTH = 3
        const val MIN_CHINESE_PARTIAL_ALIAS_LENGTH = 2
    }
}

private data class LoadedVoiceRows(
    val source: String,
    val profileRows: List<List<String>>,
    val nameMapRows: List<List<String>>
)

private data class VoiceDataSnapshot(
    val profiles: List<CharacterVoiceProfile>,
    val aliasToProfile: Map<String, VoiceProfile>,
    val aliasEntriesByLength: List<Pair<String, VoiceProfile>>,
    val cnNameToJapaneseName: Map<String, String>,
    val cnNameEntriesByLength: List<Pair<String, String>>
) {
    companion object {
        val EMPTY = VoiceDataSnapshot(
            profiles = emptyList(),
            aliasToProfile = emptyMap(),
            aliasEntriesByLength = emptyList(),
            cnNameToJapaneseName = emptyMap(),
            cnNameEntriesByLength = emptyList()
        )
    }
}

private data class CharacterVoiceProfile(
    val speakerId: String,
    val aliases: List<String>,
    val profile: VoiceProfile
)
