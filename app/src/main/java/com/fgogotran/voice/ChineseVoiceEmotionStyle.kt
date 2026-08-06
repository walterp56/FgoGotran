package com.fgogotran.voice

import java.util.Locale
import kotlin.math.abs

object ChineseVoiceEmotionStyle {
    fun expressionFor(profile: VoiceProfile, text: String): VoiceExpression? {
        if (!VoiceLocaleSupport.isChineseLocale(profile.locale)) return null

        val normalized = text.replace(Regex("\\s+"), "")
        val detectedStyle = detectStyle(normalized)
        val baseStyle = resolveStyle(profile, styleOverride = null)
        val styleOverride = detectedStyle
            ?.takeIf { it != baseStyle }
            ?.takeIf { supportsStyle(profile.voiceName, it) }
        val rateOverride = rateOverrideFor(
            baseRate = profile.rate,
            voiceType = profile.description,
            text = normalized,
            detectedStyle = detectedStyle
        )

        return VoiceExpression(
            styleOverride = styleOverride,
            rateOverride = rateOverride
        ).takeIf { it.styleOverride != null || it.rateOverride != null }
    }

    fun styleFor(profile: VoiceProfile, text: String): String? {
        return expressionFor(profile, text)?.styleOverride
    }

    fun resolveStyle(profile: VoiceProfile, styleOverride: String?): String {
        if (!VoiceLocaleSupport.isChineseLocale(profile.locale)) {
            return styleOverride?.takeIf(String::isNotBlank)
                ?: profile.style.takeIf(String::isNotBlank)
                ?: ""
        }

        return sequenceOf(styleOverride, profile.style)
            .mapNotNull { it?.trim()?.takeIf(String::isNotBlank) }
            .firstOrNull { supportsStyle(profile.voiceName, it) }
            .orEmpty()
    }

    private fun detectStyle(normalizedText: String): String? {
        return when {
            normalizedText.hasAny(SAD_HINTS) -> "sad"
            normalizedText.hasAny(FEARFUL_HINTS) -> "fearful"
            normalizedText.hasAny(ANGRY_HINTS) -> "angry"
            normalizedText.hasAny(CHEERFUL_HINTS) -> "cheerful"
            normalizedText.hasAny(DISGRUNTLED_HINTS) -> "disgruntled"
            normalizedText.hasAny(SERIOUS_HINTS) -> "serious"
            normalizedText.shortExcitedLine() -> "cheerful"
            else -> null
        }
    }

    private fun rateOverrideFor(
        baseRate: String,
        voiceType: String,
        text: String,
        detectedStyle: String?
    ): String? {
        val base = rateMultiplier(baseRate) ?: return null
        var delta = 0.0

        when (detectedStyle) {
            "sad", "fearful" -> delta -= 0.04
            "serious", "disgruntled" -> delta -= 0.03
            "angry", "cheerful" -> delta += 0.03
        }
        if (text.hasLongPause()) delta -= 0.04
        if (text.hasExcitedMark()) delta += 0.02
        if (text.length >= LONG_EXPOSITION_LENGTH) delta -= 0.02
        if (text.length <= SHORT_LINE_LENGTH && detectedStyle in setOf("angry", "cheerful")) {
            delta += 0.02
        }

        val adjusted = (base + delta).coerceIn(
            minimumValue = minRateFor(voiceType),
            maximumValue = maxRateFor(voiceType)
        )
        if (abs(adjusted - base) < MIN_RATE_DELTA) return null
        return String.format(Locale.US, "%.2f", adjusted)
    }

    private fun rateMultiplier(rawRate: String): Double? {
        val rate = rawRate.trim()
        if (rate.isBlank()) return 1.0
        if (rate in AZURE_RATE_WORDS) return null
        if (rate.endsWith("%")) {
            val percent = rate.dropLast(1).toDoubleOrNull() ?: return null
            return 1.0 + percent / 100.0
        }
        return rate.toDoubleOrNull()
    }

    private fun minRateFor(voiceType: String): Double {
        return when (voiceType) {
            "elder_male" -> 0.84
            "mature_male", "mature_female" -> 0.88
            else -> 0.86
        }
    }

    private fun maxRateFor(voiceType: String): Double {
        return when (voiceType) {
            "child", "young_female" -> 1.10
            "young_male", "androgynous" -> 1.08
            else -> 1.06
        }
    }

    private fun supportsStyle(voiceName: String, style: String): Boolean {
        return style in supportedStylesFor(voiceName)
    }

    private fun supportedStylesFor(voiceName: String): Set<String> {
        return SUPPORTED_STYLES_BY_VOICE[voiceName] ?: emptySet()
    }

    private fun String.hasAny(hints: Set<String>): Boolean {
        return hints.any { contains(it) }
    }

    private fun String.hasLongPause(): Boolean {
        return contains("……") ||
            contains("⋯⋯") ||
            contains("...") ||
            contains("。。。") ||
            count { it == '…' || it == '⋯' } >= 2
    }

    private fun String.hasExcitedMark(): Boolean {
        return any { it == '!' || it == '！' }
    }

    private fun String.shortExcitedLine(): Boolean {
        return length <= SHORT_LINE_LENGTH && count { it == '!' || it == '！' } >= 2
    }

    private val COMMON_CN_STYLES = setOf(
        "angry",
        "cheerful",
        "disgruntled",
        "fearful",
        "sad",
        "serious"
    )

    private val SUPPORTED_STYLES_BY_VOICE = mapOf(
        "zh-CN-XiaohanNeural" to setOf(
            "affectionate",
            "angry",
            "calm",
            "cheerful",
            "disgruntled",
            "embarrassed",
            "fearful",
            "gentle",
            "sad",
            "serious"
        ),
        "zh-CN-XiaomengNeural" to setOf("chat"),
        "zh-CN-XiaomoNeural" to setOf(
            "affectionate",
            "angry",
            "calm",
            "cheerful",
            "depressed",
            "disgruntled",
            "embarrassed",
            "envious",
            "fearful",
            "gentle",
            "sad",
            "serious"
        ),
        "zh-CN-XiaoruiNeural" to setOf("angry", "calm", "fearful", "sad"),
        "zh-CN-XiaoshuangNeural" to setOf("chat"),
        "zh-CN-XiaoshuangMultilingualNeural" to setOf("chat"),
        "zh-CN-XiaoxiaoNeural" to setOf(
            "affectionate",
            "angry",
            "assistant",
            "calm",
            "chat",
            "chat-casual",
            "cheerful",
            "customerservice",
            "disgruntled",
            "excited",
            "fearful",
            "friendly",
            "gentle",
            "lyrical",
            "newscast",
            "poetry-reading",
            "sad",
            "serious",
            "sorry",
            "whispering"
        ),
        "zh-CN-XiaoxiaoMultilingualNeural" to setOf(
            "affectionate",
            "cheerful",
            "empathetic",
            "excited",
            "poetry-reading",
            "sorry",
            "story"
        ),
        "zh-CN-XiaoyiNeural" to setOf(
            "affectionate",
            "angry",
            "cheerful",
            "disgruntled",
            "embarrassed",
            "fearful",
            "gentle",
            "sad",
            "serious"
        ),
        "zh-CN-XiaoyouMultilingualNeural" to setOf(
            "angry",
            "chat",
            "cheerful",
            "cute",
            "poetry-reading",
            "sad",
            "story"
        ),
        "zh-CN-XiaozhenNeural" to COMMON_CN_STYLES,
        "zh-CN-YunfengNeural" to setOf(
            "angry",
            "cheerful",
            "depressed",
            "disgruntled",
            "fearful",
            "sad",
            "serious"
        ),
        "zh-CN-YunjianNeural" to setOf(
            "angry",
            "cheerful",
            "depressed",
            "disgruntled",
            "documentary-narration",
            "narration-relaxed",
            "sad",
            "serious",
            "sports-commentary",
            "sports-commentary-excited"
        ),
        "zh-CN-YunxiNeural" to setOf(
            "angry",
            "assistant",
            "chat",
            "cheerful",
            "depressed",
            "disgruntled",
            "embarrassed",
            "fearful",
            "narration-relaxed",
            "newscast",
            "sad",
            "serious"
        ),
        "zh-CN-YunxiaNeural" to setOf("angry", "calm", "cheerful", "fearful", "sad"),
        "zh-CN-YunyeNeural" to setOf(
            "angry",
            "calm",
            "cheerful",
            "disgruntled",
            "embarrassed",
            "fearful",
            "sad",
            "serious"
        ),
        "zh-CN-YunzeNeural" to setOf(
            "angry",
            "calm",
            "cheerful",
            "depressed",
            "disgruntled",
            "documentary-narration",
            "fearful",
            "sad",
            "serious"
        )
    )

    private val SAD_HINTS = setOf(
        "对不起",
        "抱歉",
        "难过",
        "伤心",
        "悲伤",
        "痛苦",
        "哭",
        "眼泪",
        "遗憾",
        "再见",
        "牺牲"
    )

    private val FEARFUL_HINTS = setOf(
        "害怕",
        "可怕",
        "恐怖",
        "救命",
        "不要",
        "不行",
        "糟了",
        "危险"
    )

    private val ANGRY_HINTS = setOf(
        "可恶",
        "混蛋",
        "住口",
        "闭嘴",
        "愤怒",
        "生气",
        "讨厌",
        "不可原谅",
        "开什么玩笑"
    )

    private val CHEERFUL_HINTS = setOf(
        "哈哈",
        "呵呵",
        "嘿嘿",
        "嘻嘻",
        "太好了",
        "好耶",
        "开心",
        "高兴",
        "谢谢",
        "感谢",
        "没问题"
    )

    private val DISGRUNTLED_HINTS = setOf(
        "真是",
        "麻烦",
        "烦",
        "够了",
        "唉",
        "啧",
        "不满",
        "抱怨",
        "为什么"
    )

    private val SERIOUS_HINTS = setOf(
        "必须",
        "一定要",
        "绝对",
        "真相",
        "使命",
        "责任",
        "命令",
        "报告"
    )

    private const val LONG_EXPOSITION_LENGTH = 80
    private const val SHORT_LINE_LENGTH = 28
    private const val MIN_RATE_DELTA = 0.005
    private val AZURE_RATE_WORDS = setOf("x-slow", "slow", "medium", "fast", "x-fast", "default")
}
