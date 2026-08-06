package com.fgogotran.voice

internal object VoiceLocaleSupport {
    const val DEFAULT_CHINESE_LOCALE = "zh-CN"

    fun localeFromAzureVoiceName(
        voiceName: String,
        fallback: String = DEFAULT_CHINESE_LOCALE
    ): String {
        val parts = voiceName.trim().split('-')
        return if (parts.size >= 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
            "${parts[0]}-${parts[1]}"
        } else {
            fallback
        }
    }

    fun isChineseLocale(locale: String): Boolean {
        val language = locale.trim()
            .substringBefore('-')
            .lowercase()
        return language in CHINESE_LANGUAGE_PREFIXES
    }

    private val CHINESE_LANGUAGE_PREFIXES = setOf("zh", "yue", "wuu")
}
