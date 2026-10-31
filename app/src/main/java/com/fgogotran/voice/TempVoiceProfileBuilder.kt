package com.fgogotran.voice

import com.fgogotran.data.SettingsRepository
import com.fgogotran.diagnostic.DiagnosticEventStore
import com.fgogotran.translation.Translator
import com.fgogotran.util.FgoLogger
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TempVoiceProfileBuilder @Inject constructor(
    private val translator: Translator,
    private val diagnosticEventStore: DiagnosticEventStore
) {
    private val tag = "TempVoiceBuilder"

    suspend fun build(server: String, nameBox: String, dialogue: String): TempVoiceProfileRow? {
        val cleanName = sanitizeField(nameBox, MAX_NAME_CHARS).takeIf { it.isNotBlank() }
            ?: return null
        val cleanDialogue = sanitizeField(dialogue, MAX_DIALOGUE_CHARS)
        val normalizedServer = SettingsRepository.normalizeGameServer(server)
        val rawResponse = translator.completeUtilityPrompt(
            systemPrompt = SYSTEM_PROMPT,
            userPrompt = buildUserPrompt(
                server = normalizedServer,
                nameBox = cleanName,
                dialogue = cleanDialogue
            ),
            maxTokens = 128
        )
        FgoLogger.debug(tag, "Temp voice API response: ${rawResponse.take(LOG_RESPONSE_CHARS)}")
        return parseResponse(rawResponse, normalizedServer, cleanName, cleanDialogue)
            ?.also {
                FgoLogger.info(
                    tag,
                    "Temp voice profile built: server=$normalizedServer name=$cleanName type=${it.voiceType} voice=${it.voiceName}"
                )
            }
    }

    private fun buildUserPrompt(server: String, nameBox: String, dialogue: String): String {
        return buildString {
            appendLine("server=$server")
            appendLine("name_box=$nameBox")
            appendLine("dialogue=${dialogue.ifBlank { "（空）" }}")
            appendLine("voice_type 只能选：${ALLOWED_VOICE_TYPES.joinToString(",")}")
            appendLine("只可使用这些 zh-CN Azure voice model：")
            appendLine(ALLOWED_VOICE_NAMES.joinToString(","))
            appendLine("只返回一行TSV，无表头，刚好7列、6个制表符，列顺序：")
            appendLine("voice_type\tcn_voice_name\tcn_style\tcn_pitch\tcn_rate\tcn_volume\treason")
            appendLine("cn_style 填 none，不留空列；cn_pitch 为 -8% 到 +8%（中性 +0%）。")
            appendLine("cn_rate 为 0.86 到 1.10 的小数倍速（中性 1.00），不用百分比或 0；cn_volume 填 100。")
            appendLine("reason 用中文不超过30字；不要猜测未体现的性别。")
        }
    }

    private fun parseResponse(
        rawResponse: String,
        server: String,
        nameBox: String,
        dialogue: String
    ): TempVoiceProfileRow? {
        val candidateLines = responseLines(rawResponse)
            .filter { line ->
                line.isNotBlank() &&
                    !line.startsWith("voice_type", ignoreCase = true) &&
                    !line.startsWith("```")
        }
        candidateLines.forEach { line ->
            val columns = TempVoiceProfileFormat.columns(line) ?: return@forEach
            parseColumns(columns, server, nameBox, dialogue)?.let { return it }
        }

        val preview = candidateLines.firstOrNull().orEmpty().take(LOG_RESPONSE_CHARS)
        FgoLogger.warn(tag, "Temp voice API returned no usable TSV row: $preview")
        diagnosticEventStore.record(
            level = DiagnosticEventStore.LEVEL_WARNING,
            category = DiagnosticEventStore.CATEGORY_TEMP_VOICE_API,
            eventId = "temp_voice_api_bad_tsv",
            title = "API返回格式不正确",
            message = "没有可用 TSV 行",
            server = server,
            speaker = nameBox,
            detail = preview,
            textPreview = dialogue.previewText()
        )
        return null
    }

    private fun parseColumns(
        columns: List<String>,
        server: String,
        nameBox: String,
        dialogue: String
    ): TempVoiceProfileRow? {
        if (columns.size != TempVoiceProfileFormat.COLUMN_COUNT) return null

        val voiceType = normalizeVoiceType(columns[0]) ?: run {
            diagnosticEventStore.record(
                level = DiagnosticEventStore.LEVEL_WARNING,
                category = DiagnosticEventStore.CATEGORY_TEMP_VOICE_API,
                eventId = "temp_voice_api_unsupported_type",
                title = "API返回了不支持的 voice_type",
                message = columns[0],
                server = server,
                speaker = nameBox,
                detail = columns.joinToString("\t").take(LOG_RESPONSE_CHARS),
                textPreview = dialogue.previewText()
            )
            return null
        }
        val voiceName = columns[1].takeIf { it in ALLOWED_VOICE_NAME_SET } ?: run {
            FgoLogger.warn(tag, "Temp voice API returned unsupported voice: ${columns[1]}")
            diagnosticEventStore.record(
                level = DiagnosticEventStore.LEVEL_ERROR,
                category = DiagnosticEventStore.CATEGORY_TEMP_VOICE_API,
                eventId = "temp_voice_api_unsupported_voice",
                title = "API返回了不允许的语音模型",
                message = columns[1],
                server = server,
                speaker = nameBox,
                detail = columns.joinToString("\t").take(LOG_RESPONSE_CHARS),
                voiceType = voiceType,
                voiceName = columns[1],
                textPreview = dialogue.previewText()
            )
            return null
        }
        return rowFromFields(
            nameBox = nameBox,
            dialogue = dialogue,
            voiceType = voiceType,
            voiceName = voiceName,
            style = columns[2],
            pitch = columns[3],
            rate = columns[4],
            volume = columns[5],
            reason = columns[6]
        )
    }

    private fun rowFromFields(
        nameBox: String,
        dialogue: String,
        voiceType: String,
        voiceName: String,
        style: String,
        pitch: String,
        rate: String,
        volume: String,
        reason: String
    ): TempVoiceProfileRow {
        val normalizedPitch = TempVoiceProfileFormat.pitch(pitch)
        val normalizedRate = TempVoiceProfileFormat.rate(rate)
        val normalizedVolume = TempVoiceProfileFormat.volume(volume)
        val profileForStyle = VoiceProfile(
            profileId = "temp-preview",
            provider = "azure",
            locale = VoiceLocaleSupport.localeFromAzureVoiceName(voiceName),
            voiceName = voiceName,
            style = style.trim(),
            pitch = normalizedPitch,
            rate = normalizedRate,
            volume = normalizedVolume,
            description = voiceType
        )
        val normalizedStyle = TempVoiceProfileFormat.style(profileForStyle, style)
        val normalizedReason = sanitizeField(reason, MAX_REASON_CHARS).ifBlank {
            "API临时生成"
        }

        return TempVoiceProfileRow(
            nameBox = nameBox,
            voiceType = voiceType,
            voiceName = voiceName,
            style = normalizedStyle,
            pitch = normalizedPitch,
            rate = normalizedRate,
            volume = normalizedVolume,
            reason = normalizedReason,
            sourceDialogue = dialogue
        )
    }

    private fun responseLines(rawResponse: String): List<String> {
        return rawResponse
            .replace("\r", "")
            .lines()
            .map { it.trim() }
            .filterNot { it == "```" || it == "```tsv" || it == "```text" }
    }

    private fun normalizeVoiceType(rawType: String): String? {
        val key = rawType.trim().lowercase(Locale.US)
            .replace('-', '_')
            .replace(' ', '_')
        return when (key) {
            in ALLOWED_VOICE_TYPES -> key
            "female", "woman", "girl" -> "young_female"
            "male", "man", "boy" -> "young_male"
            "child", "kid" -> "child_female"
            "elder", "old" -> "elder_male"
            "unknown", "neutral" -> "androgynous"
            else -> {
                FgoLogger.warn(tag, "Temp voice API returned unsupported voice_type: $rawType")
                null
            }
        }
    }

    private fun String.previewText(): String {
        return replace(Regex("\\s+"), " ")
            .trim()
            .take(TEXT_PREVIEW_CHARS)
    }

    private fun sanitizeField(value: String, maxChars: Int): String {
        return value
            .replace('\t', ' ')
            .replace('\r', ' ')
            .replace('\n', ' ')
            .trim()
            .take(maxChars)
    }

    private companion object {
        val ALLOWED_VOICE_NAMES = AzureVoiceModelTuning.allowedZhCnVoiceNames()
        val ALLOWED_VOICE_NAME_SET = ALLOWED_VOICE_NAMES.toSet()
        val ALLOWED_VOICE_TYPES = setOf(
            "child_female",
            "child_male",
            "young_female",
            "young_male",
            "mature_female",
            "mature_male",
            "elder_female",
            "elder_male",
            "androgynous",
            "mechanical",
            "monster",
            "narrator"
        )
        const val SYSTEM_PROMPT =
            "你为FGO临时选择中文Azure语音。只用给定zh-CN模型。返回一行TSV，不要解释。reason用中文不超过30字。"
        const val MAX_NAME_CHARS = 64
        const val MAX_DIALOGUE_CHARS = 120
        const val MAX_REASON_CHARS = 40
        const val LOG_RESPONSE_CHARS = 240
        const val TEXT_PREVIEW_CHARS = 80
    }
}
