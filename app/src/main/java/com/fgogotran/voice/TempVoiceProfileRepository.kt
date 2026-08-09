package com.fgogotran.voice

import android.content.Context
import com.fgogotran.data.SettingsRepository
import com.fgogotran.diagnostic.DiagnosticEventStore
import com.fgogotran.util.FgoLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

data class TempVoiceProfileRow(
    val nameBox: String,
    val voiceType: String,
    val voiceName: String,
    val style: String,
    val pitch: String,
    val rate: String,
    val volume: String,
    val reason: String,
    val sourceDialogue: String,
    val createdAt: String = "",
    val updatedAt: String = ""
)

@Singleton
class TempVoiceProfileRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val diagnosticEventStore: DiagnosticEventStore
) {
    private val tag = "TempVoiceProfiles"
    private val mutex = Mutex()

    suspend fun resolveProfileOrNull(server: String, nameBox: String): VoiceProfile? {
        val normalizedName = VoiceNameNormalizer.normalize(nameBox).takeIf { it.isNotBlank() }
            ?: return null
        val normalizedServer = SettingsRepository.normalizeGameServer(server)
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                val row = readRows(normalizedServer)[normalizedName] ?: return@withLock null
                FgoLogger.debug(tag, "Temp voice profile hit: server=$normalizedServer name=${row.nameBox}")
                row.toVoiceProfile(normalizedServer)
            }
        }
    }

    suspend fun upsert(server: String, row: TempVoiceProfileRow): VoiceProfile? {
        val normalizedName = VoiceNameNormalizer.normalize(row.nameBox).takeIf { it.isNotBlank() }
            ?: return null
        val normalizedServer = SettingsRepository.normalizeGameServer(server)
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                val rows = readRows(normalizedServer).toMutableMap()
                val now = Instant.now().toString()
                val existing = rows[normalizedName]
                val saved = row.copy(
                    createdAt = existing?.createdAt?.takeIf { it.isNotBlank() } ?: now,
                    updatedAt = now
                )
                rows[normalizedName] = saved
                writeRows(normalizedServer, rows.values)
                FgoLogger.info(
                    tag,
                    "Temp voice profile saved: server=$normalizedServer name=${saved.nameBox} voice=${saved.voiceName}"
                )
                saved.toVoiceProfile(normalizedServer)
            }
        }
    }

    private fun readRows(server: String): Map<String, TempVoiceProfileRow> {
        val file = fileForServer(server)
        if (!file.exists()) return emptyMap()
        return runCatching {
            buildMap {
                file.readLines(Charsets.UTF_8)
                    .asSequence()
                    .map(String::trim)
                    .filter { it.isNotBlank() && !it.startsWith("#") }
                    .filterNot { it.startsWith(HEADER_PREFIX) }
                    .forEach { line ->
                        parseRow(line)?.let { row ->
                            val key = VoiceNameNormalizer.normalize(row.nameBox)
                            if (key.isNotBlank()) put(key, row)
                        }
                    }
            }
        }.onFailure { e ->
            FgoLogger.warn(tag, "Failed to read temp voice profile TSV: ${file.name}", e)
            diagnosticEventStore.record(
                level = DiagnosticEventStore.LEVEL_ERROR,
                category = DiagnosticEventStore.CATEGORY_TEMP_VOICE_API,
                eventId = "temp_voice_tsv_read_failed",
                title = "临时语音档案读取失败",
                message = e.message.orEmpty().ifBlank { e::class.java.simpleName },
                server = SettingsRepository.normalizeGameServer(server),
                detail = file.name
            )
        }.getOrDefault(emptyMap())
    }

    private fun writeRows(server: String, rows: Collection<TempVoiceProfileRow>) {
        try {
            val file = fileForServer(server)
            file.parentFile?.mkdirs()
            val tempFile = File(file.parentFile, "${file.name}.tmp")
            tempFile.bufferedWriter(Charsets.UTF_8).use { writer ->
                writer.appendLine(HEADER)
                rows.sortedBy { VoiceNameNormalizer.normalize(it.nameBox) }.forEach { row ->
                    writer.appendLine(row.toTsvLine())
                }
            }
            if (file.exists() && !file.delete()) {
                throw IllegalStateException("Could not replace ${file.name}")
            }
            if (!tempFile.renameTo(file)) {
                throw IllegalStateException("Could not write ${file.name}")
            }
        } catch (e: Exception) {
            diagnosticEventStore.record(
                level = DiagnosticEventStore.LEVEL_ERROR,
                category = DiagnosticEventStore.CATEGORY_TEMP_VOICE_API,
                eventId = "temp_voice_tsv_write_failed",
                title = "临时语音档案写入失败",
                message = e.message.orEmpty().ifBlank { e::class.java.simpleName },
                server = SettingsRepository.normalizeGameServer(server)
            )
            throw e
        }
    }

    private fun fileForServer(server: String): File {
        val normalizedServer = SettingsRepository.normalizeGameServer(server)
        return File(File(context.filesDir, "voice"), "temp_voice_profile_$normalizedServer.tsv")
    }

    private fun parseRow(line: String): TempVoiceProfileRow? {
        val columns = line.split('\t')
        if (columns.size < COLUMN_COUNT) return null
        return TempVoiceProfileRow(
            nameBox = columns[0],
            voiceType = columns[1],
            voiceName = columns[2],
            style = columns[3],
            pitch = columns[4],
            rate = columns[5],
            volume = columns[6],
            reason = columns[7],
            sourceDialogue = columns[8],
            createdAt = columns[9],
            updatedAt = columns.getOrNull(10).orEmpty()
        )
    }

    private fun TempVoiceProfileRow.toVoiceProfile(server: String): VoiceProfile {
        return VoiceProfile(
            profileId = "temp:$server:${VoiceNameNormalizer.normalize(nameBox)}",
            provider = AZURE_PROVIDER,
            locale = VoiceLocaleSupport.localeFromAzureVoiceName(voiceName),
            voiceName = voiceName,
            style = style,
            pitch = pitch.ifBlank { "0%" },
            rate = rate.ifBlank { "1.00" },
            volume = volume.ifBlank { "100" },
            description = voiceType
        )
    }

    private fun TempVoiceProfileRow.toTsvLine(): String {
        return listOf(
            nameBox,
            voiceType,
            voiceName,
            style,
            pitch,
            rate,
            volume,
            reason,
            sourceDialogue,
            createdAt,
            updatedAt
        ).joinToString("\t") { sanitizeTsvField(it) }
    }

    private fun sanitizeTsvField(value: String): String {
        return value
            .replace('\t', ' ')
            .replace('\r', ' ')
            .replace('\n', ' ')
            .trim()
    }

    private companion object {
        const val AZURE_PROVIDER = "azure"
        const val COLUMN_COUNT = 10
        const val HEADER_PREFIX = "name_box\t"
        const val HEADER = "name_box\tvoice_type\tcn_voice_name\tcn_style\tcn_pitch\tcn_rate\tcn_volume\treason\tsource_dialogue\tcreated_at\tupdated_at"
    }
}
