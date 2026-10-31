package com.fgogotran.voice

import java.util.Locale

/** The live API row, distinct from the persisted voice-package TSV schema. */
internal object TempVoiceProfileFormat {
    const val COLUMN_COUNT = 7
    const val MIN_RATE = 0.86
    const val MAX_RATE = 1.10
    const val DEFAULT_RATE = 1.00
    const val DEFAULT_VOLUME = 100

    fun columns(line: String): List<String>? = line.split('\t').map(String::trim)
        .takeIf { it.size == COLUMN_COUNT && line.none { char -> char == '\n' || char == '\r' } }

    fun pitch(raw: String): String {
        val value = raw.trim()
        if (!PITCH.matches(value)) return "+0%"
        val percent = value.dropLast(1).toIntOrNull()?.coerceIn(-8, 8) ?: return "+0%"
        return if (percent >= 0) "+$percent%" else "$percent%"
    }

    fun rate(raw: String): String {
        val value = raw.trim()
        // Accept correctly expressed legacy percent rates, but never treat 0/-5 as a multiplier.
        val multiplier = if (value.endsWith('%')) {
            value.dropLast(1).toDoubleOrNull()?.let { 1.0 + it / 100.0 }
        } else value.toDoubleOrNull()
        val safe = multiplier?.takeIf { it.isFinite() && it > 0.0 }
            ?.coerceIn(MIN_RATE, MAX_RATE) ?: DEFAULT_RATE
        return String.format(Locale.US, "%.2f", safe)
    }

    fun volume(raw: String): String {
        val value = raw.trim()
        return value.takeIf { VOLUME.matches(it) }?.toIntOrNull()
            ?.takeIf { it in 0..100 }?.toString() ?: DEFAULT_VOLUME.toString()
    }

    fun style(profile: VoiceProfile, raw: String): String {
        val requested = raw.trim().lowercase(Locale.US)
        if (requested in setOf("", "none", "normal", "general", "default")) return ""
        // An unsupported temporary preset must not become a different emotion (gentle -> cheerful).
        return VoiceEmotionStyle.resolveStyle(profile.copy(style = requested), null)
            .takeIf { it == requested }.orEmpty()
    }

    private val PITCH = Regex("[+-]?\\d+%")
    private val VOLUME = Regex("\\d{1,3}")
}
