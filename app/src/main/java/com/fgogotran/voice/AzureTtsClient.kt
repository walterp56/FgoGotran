package com.fgogotran.voice

import com.fgogotran.util.FgoLogger
import io.ktor.client.call.body
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

@Singleton
class AzureTtsClient @Inject constructor() {
    private val tag = "AzureTts"

    private val httpClient = HttpClient {
        install(HttpTimeout) {
            connectTimeoutMillis = AZURE_TTS_CONNECT_TIMEOUT_MS
            socketTimeoutMillis = AZURE_TTS_SOCKET_TIMEOUT_MS
            requestTimeoutMillis = AZURE_TTS_REQUEST_TIMEOUT_MS
        }
    }

    suspend fun synthesize(
        config: AzureSpeechConfig,
        profile: VoiceProfile,
        text: String,
        styleOverride: String? = null,
        rateOverride: String? = null,
        pitchOverride: String? = null,
        styleDegree: String? = null,
        pauseScale: Double? = null
    ): ByteArray {
        val region = config.region.trim().lowercase().ifBlank {
            throw IllegalArgumentException("Azure Speech region is blank")
        }
        val key = config.key.trim().ifBlank {
            throw IllegalArgumentException("Azure Speech key is blank")
        }
        val endpoint = "https://$region.tts.speech.microsoft.com/cognitiveservices/v1"
        val response = try {
            httpClient.post(endpoint) {
                header("Ocp-Apim-Subscription-Key", key)
                header("X-Microsoft-OutputFormat", AZURE_AUDIO_FORMAT)
                header("User-Agent", "FgoGotran")
                header("Accept", "audio/mpeg")
                contentType(ContentType.parse("application/ssml+xml"))
                setBody(buildSsml(profile, text, styleOverride, rateOverride, pitchOverride, styleDegree, pauseScale))
            }
        } catch (e: HttpRequestTimeoutException) {
            FgoLogger.warn(tag, "Azure TTS request timed out")
            throw e
        }

        if (!response.status.isSuccess()) {
            val message = runCatching { response.bodyAsText() }.getOrDefault("")
            throw IllegalStateException(
                "Azure TTS failed: HTTP ${response.status.value} ${message.take(160)}"
            )
        }
        return response.body()
    }

    private fun buildSsml(
        profile: VoiceProfile,
        text: String,
        styleOverride: String?,
        rateOverride: String?,
        pitchOverride: String?,
        styleDegree: String?,
        pauseScale: Double?
    ): String {
        val locale = profile.locale.ifBlank { "ja-JP" }
        val voiceName = profile.voiceName.ifBlank { "ja-JP-NanamiNeural" }
        val pitch = pitchOverride?.takeIf { it.isNotBlank() } ?: profile.pitch.ifBlank { "0%" }
        val rate = normalizeRate(rateOverride?.takeIf { it.isNotBlank() } ?: profile.rate)
        val style = ChineseVoiceEmotionStyle.resolveStyle(
            profile = profile,
            styleOverride = styleOverride
        )
        val dialogueContent = buildDialogueContent(
            text = text,
            naturalDialogue = VoiceLocaleSupport.isChineseLocale(locale),
            pauseScale = pauseScale ?: 1.0
        )
        val spokenContent = "<prosody pitch=\"$pitch\" rate=\"$rate\">$dialogueContent</prosody>"
        val content = if (style.isBlank()) {
            spokenContent
        } else {
            val styleDegreeAttribute = styleDegree
                ?.takeIf { it.isNotBlank() }
                ?.let { " styledegree=\"${escapeXml(it)}\"" }
                .orEmpty()
            "<mstts:express-as style=\"${escapeXml(style)}\"$styleDegreeAttribute>$spokenContent</mstts:express-as>"
        }
        val msttsNamespace = if (style.isBlank()) {
            ""
        } else {
            " xmlns:mstts=\"https://www.w3.org/2001/mstts\""
        }

        return """
            <speak version="1.0" xml:lang="$locale"$msttsNamespace>
                <voice xml:lang="$locale" name="${escapeXml(voiceName)}">$content</voice>
            </speak>
        """.trimIndent()
    }

    private fun buildDialogueContent(
        text: String,
        naturalDialogue: Boolean,
        pauseScale: Double
    ): String {
        val result = StringBuilder()
        var index = 0
        var spokenCharsSinceBreak = 0
        val normalizedPauseScale = pauseScale.coerceIn(MIN_PAUSE_SCALE, MAX_PAUSE_SCALE)

        fun appendBreak(milliseconds: Int) {
            val scaledMilliseconds = (milliseconds * normalizedPauseScale).roundToInt()
            if (scaledMilliseconds > 0) {
                result.append(dialogueBreak(scaledMilliseconds))
            }
            spokenCharsSinceBreak = 0
        }

        while (index < text.length) {
            when (val char = text[index]) {
                '…', '⋯' -> {
                    val start = index
                    while (index < text.length && (text[index] == '…' || text[index] == '⋯')) {
                        index++
                    }
                    result.append(escapeXml(text.substring(start, index)))
                    appendBreak(ELLIPSIS_BREAK_MS)
                    continue
                }
                '.', '．' -> {
                    val start = index
                    while (index < text.length && (text[index] == '.' || text[index] == '．')) {
                        index++
                    }
                    result.append(escapeXml(text.substring(start, index)))
                    if (index - start >= 2) {
                        appendBreak(ELLIPSIS_BREAK_MS)
                    } else {
                        spokenCharsSinceBreak = 0
                    }
                    continue
                }
                '、', ',', '，' -> {
                    result.append(escapeXml(char.toString()))
                    appendBreak(SHORT_BREAK_MS)
                }
                '。', '｡' -> {
                    result.append(escapeXml(char.toString()))
                    appendBreak(SENTENCE_BREAK_MS)
                }
                '！', '!', '？', '?' -> {
                    result.append(escapeXml(char.toString()))
                    appendBreak(EMOTIONAL_BREAK_MS)
                }
                '\n', '\r' -> {
                    appendBreak(LINE_BREAK_MS)
                }
                else -> {
                    result.append(escapeXml(char.toString()))
                    if (!char.isWhitespace()) {
                        spokenCharsSinceBreak++
                    }
                    if (
                        naturalDialogue &&
                        index < text.lastIndex &&
                        (
                            spokenCharsSinceBreak >= HARD_PHRASE_CHARS ||
                                (spokenCharsSinceBreak >= SOFT_PHRASE_CHARS && char in SOFT_PHRASE_END_CHARS)
                            )
                    ) {
                        appendBreak(PHRASE_BREAK_MS)
                    }
                }
            }
            index++
        }
        return result.toString()
    }

    private fun dialogueBreak(milliseconds: Int): String {
        return """<break time="${milliseconds}ms"/>"""
    }

    private fun normalizeRate(rawRate: String): String {
        val rate = rawRate.trim()
        if (rate.isBlank()) return "+0%"
        if (rate.endsWith("%") || rate in AZURE_RATE_WORDS) return rate
        val multiplier = rate.toDoubleOrNull() ?: return rate
        val percent = ((multiplier - 1.0) * 100.0).roundToInt()
        return if (percent >= 0) "+$percent%" else "$percent%"
    }

    private fun escapeXml(raw: String): String {
        return raw
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }

    private companion object {
        const val AZURE_AUDIO_FORMAT = "audio-24khz-160kbitrate-mono-mp3"
        const val AZURE_TTS_CONNECT_TIMEOUT_MS = 8_000L
        const val AZURE_TTS_SOCKET_TIMEOUT_MS = 20_000L
        const val AZURE_TTS_REQUEST_TIMEOUT_MS = 25_000L
        const val SHORT_BREAK_MS = 55
        const val SENTENCE_BREAK_MS = 170
        const val EMOTIONAL_BREAK_MS = 190
        const val ELLIPSIS_BREAK_MS = 360
        const val LINE_BREAK_MS = 190
        const val PHRASE_BREAK_MS = 85
        const val SOFT_PHRASE_CHARS = 46
        const val HARD_PHRASE_CHARS = 68
        const val MIN_PAUSE_SCALE = 0.4
        const val MAX_PAUSE_SCALE = 1.2
        val AZURE_RATE_WORDS = setOf("x-slow", "slow", "medium", "fast", "x-fast", "default")
        val SOFT_PHRASE_END_CHARS = setOf(
            '的',
            '了',
            '着',
            '过',
            '吗',
            '呢',
            '吧',
            '啊',
            '呀',
            '哦',
            '嘛',
            '啦',
            '也',
            '就',
            '而'
        )
    }
}
