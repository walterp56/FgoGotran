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
            appendLine("第一列必须是 voice_type，不要把 voice model 放第一列。")
            appendLine("voice_type 只能选：${ALLOWED_VOICE_TYPES.joinToString(",")}")
            appendLine("只可使用这些 zh-CN Azure voice model：")
            appendLine(ALLOWED_VOICE_NAMES.joinToString(","))
            appendLine("只返回TSV一行，列顺序必须是：")
            appendLine("voice_type\tcn_voice_name\tcn_style\tcn_pitch\tcn_rate\tcn_volume\treason")
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
            val columns = line.split('\t').map(String::trim)
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
        if (columns.firstOrNull() in ALLOWED_VOICE_NAME_SET) {
            return parseVoiceFirstColumns(columns, nameBox, dialogue)
        }
        if (columns.size < RESPONSE_COLUMN_COUNT) return null

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

    private fun parseVoiceFirstColumns(
        columns: List<String>,
        nameBox: String,
        dialogue: String
    ): TempVoiceProfileRow? {
        val voiceName = columns.firstOrNull()?.takeIf { it in ALLOWED_VOICE_NAME_SET }
            ?: return null
        val tail = columns.drop(1)
            .filterNot { it == voiceName }
        val numericFields = tail.filter(::isNumericVoiceSetting)
        val reason = tail.lastOrNull { looksLikeReason(it) }.orEmpty()
        val style = tail.firstOrNull { looksLikeStyleCandidate(it) }.orEmpty()
        val inferredVoiceType = inferVoiceType(nameBox, voiceName)
        FgoLogger.info(
            tag,
            "Temp voice API used voice-first row; inferred type=$inferredVoiceType voice=$voiceName"
        )
        return rowFromFields(
            nameBox = nameBox,
            dialogue = dialogue,
            voiceType = inferredVoiceType,
            voiceName = voiceName,
            style = style,
            pitch = numericFields.getOrNull(0).orEmpty(),
            rate = numericFields.getOrNull(1).orEmpty(),
            volume = numericFields.getOrNull(2).orEmpty(),
            reason = reason
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
        val normalizedPitch = normalizePitch(pitch)
        val normalizedRate = normalizeRate(rate)
        val normalizedVolume = normalizeVolume(volume)
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
        val normalizedStyle = ChineseVoiceEmotionStyle.resolveStyle(profileForStyle, styleOverride = null)
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

    private fun normalizePitch(rawPitch: String): String {
        val pitch = rawPitch.trim().ifBlank { return "0%" }
        val numeric = pitch.removeSuffix("%").toIntOrNull() ?: return "0%"
        val safe = numeric.coerceIn(MIN_PITCH_PERCENT, MAX_PITCH_PERCENT)
        return if (safe >= 0) "+$safe%" else "$safe%"
    }

    private fun normalizeRate(rawRate: String): String {
        val trimmed = rawRate.trim()
        val multiplier = if (trimmed.endsWith("%")) {
            val percent = trimmed.removeSuffix("%").toDoubleOrNull() ?: DEFAULT_RATE
            1.0 + percent / 100.0
        } else {
            trimmed.toDoubleOrNull() ?: DEFAULT_RATE
        }
        val safe = multiplier.coerceIn(MIN_RATE, MAX_RATE)
        return String.format(Locale.US, "%.2f", safe)
    }

    private fun normalizeVolume(rawVolume: String): String {
        val trimmed = rawVolume.trim()
        if (trimmed.startsWith("+") || trimmed.startsWith("-")) return DEFAULT_VOLUME.toString()
        val volume = trimmed.removeSuffix("%").toIntOrNull() ?: DEFAULT_VOLUME
        return volume.coerceIn(MIN_VOLUME, MAX_VOLUME).toString()
    }

    private fun isNumericVoiceSetting(value: String): Boolean {
        val trimmed = value.trim()
        return trimmed.removePrefix("+")
            .removePrefix("-")
            .removeSuffix("%")
            .toDoubleOrNull() != null
    }

    private fun looksLikeStyleCandidate(value: String): Boolean {
        val trimmed = value.trim()
        return trimmed.isNotBlank() &&
            trimmed !in ALLOWED_VOICE_NAME_SET &&
            trimmed.lowercase(Locale.US) !in GENERIC_STYLE_WORDS &&
            !isNumericVoiceSetting(trimmed) &&
            trimmed.length <= MAX_STYLE_CHARS &&
            trimmed.none(::isCjkCharacter)
    }

    private fun looksLikeReason(value: String): Boolean {
        val trimmed = value.trim()
        return trimmed.length > MAX_STYLE_CHARS ||
            trimmed.any(::isCjkCharacter)
    }

    private fun inferVoiceType(nameBox: String, voiceName: String): String {
        val normalizedName = nameBox.trim()
        return when {
            normalizedName.containsAny("老婆婆", "老婦", "老妇", "老女", "老太") -> "elder_female"
            normalizedName.containsAny("老人", "老翁", "老爺", "老爷", "老男") -> "elder_male"
            normalizedName.containsAny("小女孩", "女孩", "少女", "女童") -> "child_female"
            normalizedName.containsAny("小男孩", "男孩", "少年", "男童") -> "child_male"
            normalizedName.containsAny("女人", "女性", "女子", "婦人", "妇人", "女") -> "young_female"
            normalizedName.containsAny("武士", "士兵", "男人", "男性", "男子", "男") -> "mature_male"
            voiceName.contains("-Xiaoyou") || voiceName.contains("-Xiaoshuang") -> "child_female"
            voiceName.contains("-Xiao") -> "young_female"
            voiceName.contains("-Yun") -> "young_male"
            else -> "androgynous"
        }
    }

    private fun String.containsAny(vararg values: String): Boolean {
        return values.any { contains(it) }
    }

    private fun String.previewText(): String {
        return replace(Regex("\\s+"), " ")
            .trim()
            .take(TEXT_PREVIEW_CHARS)
    }

    private fun isCjkCharacter(char: Char): Boolean {
        return Character.UnicodeScript.of(char.code) in CJK_SCRIPTS
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
        const val RESPONSE_COLUMN_COUNT = 7
        const val MAX_NAME_CHARS = 64
        const val MAX_DIALOGUE_CHARS = 120
        const val MAX_STYLE_CHARS = 24
        const val MAX_REASON_CHARS = 40
        const val LOG_RESPONSE_CHARS = 240
        const val TEXT_PREVIEW_CHARS = 80
        const val MIN_PITCH_PERCENT = -8
        const val MAX_PITCH_PERCENT = 8
        const val MIN_RATE = 0.86
        const val MAX_RATE = 1.10
        const val DEFAULT_RATE = 0.98
        const val MIN_VOLUME = 0
        const val MAX_VOLUME = 100
        const val DEFAULT_VOLUME = 100
        val GENERIC_STYLE_WORDS = setOf("default", "general", "none", "normal")
        val CJK_SCRIPTS = setOf(
            Character.UnicodeScript.HAN,
            Character.UnicodeScript.HIRAGANA,
            Character.UnicodeScript.KATAKANA,
            Character.UnicodeScript.HANGUL
        )
    }
}
