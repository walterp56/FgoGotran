package com.fgogotran.voice

import com.fgogotran.util.FgoLogger
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Cheap gross-truncation guard for our fixed 160 kbit/s MP3 output; not a speech recognizer. */
internal object AzureTtsAudioPolicy {
    const val AUDIO_FORMAT = "audio-24khz-160kbitrate-mono-mp3"
    const val CACHE_VERSION = "azure-cbr160-short-audio-v1"

    suspend fun synthesize(text: String, rate: String, request: suspend () -> ByteArray): ByteArray {
        var reason: String? = null
        repeat(2) { attempt ->
            currentCoroutineContext().ensureActive()
            val audio = request() // HTTP failures and cancellation are not retried here.
            currentCoroutineContext().ensureActive()
            reason = suspiciousReason(audio, text, rate)
            if (reason == null) return audio
            if (attempt == 0) FgoLogger.warn("AzureTts", "Suspicious audio: $reason; retrying once")
        }
        throw IllegalStateException("Azure TTS returned suspicious audio after 2 attempts: $reason")
    }

    fun suspiciousReason(audio: ByteArray, text: String, rate: String): String? {
        if (audio.isEmpty()) return "empty audio"
        // A short name, interjection or creature sound can legitimately produce a very short clip.
        val cjk = text.filter(::isCjkLetter)
        val words = WORD.findAll(text).take(12).map { it.value.lowercase() }.toList()
        // Repeated cries/laughter can be many printed letters but only one brief sound.
        val longLine = (cjk.length >= 12 && cjk.toSet().size >= 6) ||
            (words.size >= 12 && words.toSet().size >= 6)
        if (!longLine) return null
        // At CBR 160 kbit/s, 20,000 bytes is one second. Metadata can only make this conservative.
        // Fast user playback lowers the floor; slow playback never raises it beyond one second.
        val minimumBytes = (BYTES_PER_SECOND / rateMultiplier(rate).coerceAtLeast(1.0)).toInt()
        return if (audio.size < minimumBytes) {
            "${audio.size} bytes for a long line (minimum $minimumBytes at this rate)"
        } else null
    }

    private fun rateMultiplier(raw: String): Double = when (val value = raw.trim()) {
        "fast" -> 1.55
        "x-fast" -> 2.0
        else -> (if (value.endsWith('%')) value.dropLast(1).toDoubleOrNull()?.let { 1.0 + it / 100.0 }
            else value.toDoubleOrNull())?.takeIf { it.isFinite() && it > 0.0 } ?: 1.0
    }

    private fun isCjkLetter(char: Char): Boolean = Character.UnicodeScript.of(char.code) in CJK_SCRIPTS
    private val CJK_SCRIPTS = setOf(Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA,
        Character.UnicodeScript.KATAKANA)
    private val WORD = Regex("[\\p{L}\\p{M}]+(?:['’][\\p{L}\\p{M}]+)*")
    private const val BYTES_PER_SECOND = 20_000.0
}
