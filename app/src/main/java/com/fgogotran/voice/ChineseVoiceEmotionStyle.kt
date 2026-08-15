package com.fgogotran.voice

import com.fgogotran.translation.VoiceLineHint
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

object ChineseVoiceEmotionStyle {
    fun expressionFor(
        profile: VoiceProfile,
        text: String,
        voiceHint: VoiceLineHint? = null,
        baseSpeedMultiplier: Double = 1.0
    ): VoiceExpression? {
        if (!VoiceLocaleSupport.isChineseLocale(profile.locale)) return null

        val normalized = text.replace(Regex("\\s+"), "")
        val voiceTuning = AzureVoiceModelTuning.forVoice(profile.voiceName)
        val trustedVoiceHint = trustedVoiceHint(voiceHint)
        val expression = buildExpression(
            profile = profile,
            normalizedText = normalized,
            voiceTuning = voiceTuning,
            trustedVoiceHint = trustedVoiceHint,
            baseSpeedMultiplier = baseSpeedMultiplier
        )
        if (trustedVoiceHint == null) return expression

        val localExpression = buildExpression(
            profile = profile,
            normalizedText = normalized,
            voiceTuning = voiceTuning,
            trustedVoiceHint = null,
            baseSpeedMultiplier = baseSpeedMultiplier
        )
        return expression.copy(
            voiceHintApplied = expression.differsFrom(localExpression)
        )
    }

    private fun buildExpression(
        profile: VoiceProfile,
        normalizedText: String,
        voiceTuning: AzureVoiceModelTuning.VoiceModelTuning,
        trustedVoiceHint: VoiceLineHint?,
        baseSpeedMultiplier: Double
    ): VoiceExpression {
        val baseStyle = resolveStyle(profile, styleOverride = null)
        val hintedStyles = styleCandidatesFromVoiceHint(trustedVoiceHint, profile.voiceName)
        val localStyle = detectStyle(normalizedText)
        val detectedStyle = preferredStyleFor(
            profile = profile,
            voiceTuning = voiceTuning,
            hintedStyles = hintedStyles,
            localStyle = localStyle
        )
        val styleOverride = detectedStyle
            ?.takeIf { it != baseStyle }
            ?.takeIf { canApplyStyle(profile.voiceName, voiceTuning, it) }
        val resolvedStyle = resolveStyle(profile, styleOverride)
        val rateOverride = rateOverrideFor(
            baseRate = profile.rate,
            voiceType = profile.description,
            text = normalizedText,
            detectedStyle = detectedStyle,
            voiceTuning = voiceTuning,
            voiceHint = trustedVoiceHint,
            baseSpeedMultiplier = baseSpeedMultiplier
        )
        val pitchOverride = pitchOverrideFor(
            basePitch = profile.pitch,
            voiceType = profile.description,
            detectedStyle = detectedStyle,
            voiceTuning = voiceTuning,
            voiceHint = trustedVoiceHint
        )
        val styleDegree = resolvedStyle
            .takeIf { it.isNotBlank() }
            ?.let {
                styleDegreeFor(
                    style = it,
                    voiceTuning = voiceTuning,
                    voiceHint = trustedVoiceHint,
                    hintControlsStyle = resolvedStyle in hintedStyles
                )
            }

        return VoiceExpression(
            styleOverride = styleOverride,
            rateOverride = rateOverride,
            pitchOverride = pitchOverride,
            styleDegree = styleDegree,
            pauseScale = pauseScaleFor(voiceTuning.pauseScale, trustedVoiceHint),
            ssmlModeVersion = NATURAL_DIALOGUE_MODE_VERSION
        )
    }

    private fun VoiceExpression.differsFrom(localExpression: VoiceExpression): Boolean {
        return styleOverride != localExpression.styleOverride ||
            rateOverride != localExpression.rateOverride ||
            pitchOverride != localExpression.pitchOverride ||
            styleDegree != localExpression.styleDegree ||
            !sameNullableDouble(pauseScale, localExpression.pauseScale)
    }

    private fun sameNullableDouble(left: Double?, right: Double?): Boolean {
        if (left == null || right == null) return left == right
        return abs(left - right) < MIN_EXPRESSION_DOUBLE_DELTA
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

        styleOverride
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?.takeIf { isNaturalDialogueStyle(it) }
            ?.takeIf { AzureVoiceModelTuning.forVoice(profile.voiceName).allowsStyle(it) }
            ?.takeIf { supportsStyle(profile.voiceName, it) }
            ?.let { return it }

        val voiceTuning = AzureVoiceModelTuning.forVoice(profile.voiceName)
        return profile.style
            .trim()
            .takeIf(String::isNotBlank)
            ?.takeIf { isNaturalDialogueStyle(it) }
            ?.takeIf(voiceTuning::allowsStyle)
            ?.takeIf { supportsStyle(profile.voiceName, it) }
            .orEmpty()
    }

    private fun detectStyle(normalizedText: String): String? {
        return when {
            normalizedText.hasAny(SAD_HINTS) -> "sad"
            normalizedText.hasAny(FEARFUL_HINTS) -> "fearful"
            normalizedText.hasAny(ANGRY_HINTS) -> "angry"
            normalizedText.hasAny(CHEERFUL_HINTS) -> "cheerful"
            normalizedText.hasAny(DISGRUNTLED_HINTS) -> "disgruntled"
            normalizedText.shortExcitedLine() -> "cheerful"
            else -> null
        }
    }

    private fun preferredStyleFor(
        profile: VoiceProfile,
        voiceTuning: AzureVoiceModelTuning.VoiceModelTuning,
        hintedStyles: List<String>,
        localStyle: String?
    ): String? {
        return (hintedStyles + listOfNotNull(localStyle))
            .firstOrNull { canApplyStyle(profile.voiceName, voiceTuning, it) }
            ?: localStyle
    }

    private fun canApplyStyle(
        voiceName: String,
        voiceTuning: AzureVoiceModelTuning.VoiceModelTuning,
        style: String
    ): Boolean {
        return isNaturalDialogueStyle(style) &&
            voiceTuning.allowsStyle(style) &&
            supportsStyle(voiceName, style)
    }

    private fun trustedVoiceHint(voiceHint: VoiceLineHint?): VoiceLineHint? {
        return voiceHint?.takeUnless { (it.confidence ?: 1.0) < MIN_HINT_CONFIDENCE }
    }

    private fun styleCandidatesFromVoiceHint(
        voiceHint: VoiceLineHint?,
        voiceName: String
    ): List<String> {
        if (voiceHint == null) return emptyList()
        val candidates = mutableListOf<String>()
        if (isDragonHdFlashVoice(voiceName)) {
            candidates += voiceHint.dragonStyles
        }
        candidates += voiceHint.styles
        return candidates
            .mapNotNull(::normalizeStyleCandidate)
            .distinct()
    }

    private fun normalizeStyleCandidate(rawStyle: String): String? {
        val style = rawStyle
            .trim()
            .lowercase(Locale.US)
            .replace('_', '-')
            .takeIf { it.isNotBlank() && it != "normal" && it != "neutral" }
            ?: return null
        return style.takeIf { isNaturalDialogueStyle(it) }
    }

    private fun isDragonHdFlashVoice(voiceName: String): Boolean {
        return voiceName.contains(":DragonHDFlashLatestNeural")
    }

    private fun rateBiasFor(voiceHint: VoiceLineHint?): Double {
        return (voiceHint?.rate ?: 0)
            .coerceIn(HINT_MIN_DELTA, HINT_MAX_DELTA) * HINT_RATE_STEP
    }

    private fun pitchBiasFor(voiceHint: VoiceLineHint?): Double {
        return (voiceHint?.pitch ?: 0)
            .coerceIn(HINT_MIN_DELTA, HINT_MAX_DELTA) * HINT_PITCH_STEP
    }

    private fun rateOverrideFor(
        baseRate: String,
        voiceType: String,
        text: String,
        detectedStyle: String?,
        voiceTuning: AzureVoiceModelTuning.VoiceModelTuning,
        voiceHint: VoiceLineHint?,
        baseSpeedMultiplier: Double
    ): String? {
        val base = rateMultiplier(baseRate) ?: return null
        val normalizedBaseSpeed = baseSpeedMultiplier.coerceIn(MIN_USER_SPEED_MULTIPLIER, MAX_USER_SPEED_MULTIPLIER)
        val typeMaxRate = maxDialogueRateFor(voiceType)
        val typeMinRate = minDialogueRateFor(voiceType)
        val positiveUserBias = (normalizedBaseSpeed - 1.0).coerceAtLeast(0.0)
        val negativeUserBias = (normalizedBaseSpeed - 1.0).coerceAtMost(0.0)
        val tuningMinRate = (voiceTuning.minRate + negativeUserBias).coerceAtMost(typeMaxRate)
        val minimumRate = if (voiceTuning.softenTypeRateFloor) {
            minOf(typeMinRate, tuningMinRate)
        } else {
            maxOf(typeMinRate, tuningMinRate)
        }
        val maximumRate = minOf(typeMaxRate, voiceTuning.maxRate + positiveUserBias).coerceAtLeast(minimumRate)
        val hintedRateBias = rateBiasFor(voiceHint)
        var adjusted = (base * normalizedBaseSpeed + voiceTuning.rateBias + hintedRateBias)
            .coerceIn(minimumRate, maximumRate)

        when (detectedStyle) {
            "sad", "fearful" -> adjusted -= 0.02
            "angry", "cheerful" -> adjusted += 0.01
        }
        if (text.hasExcitedMark()) adjusted += 0.01
        if (text.length <= SHORT_LINE_LENGTH && detectedStyle in setOf("angry", "cheerful")) {
            adjusted += 0.01
        }

        adjusted = adjusted.coerceIn(minimumRate, maximumRate)
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

    private fun minDialogueRateFor(@Suppress("UNUSED_PARAMETER") voiceType: String): Double =
        MIN_USER_SPEED_MULTIPLIER

    private fun maxDialogueRateFor(@Suppress("UNUSED_PARAMETER") voiceType: String): Double =
        MAX_USER_SPEED_MULTIPLIER

    private fun pitchOverrideFor(
        basePitch: String,
        voiceType: String,
        detectedStyle: String?,
        voiceTuning: AzureVoiceModelTuning.VoiceModelTuning,
        voiceHint: VoiceLineHint?
    ): String? {
        val base = pitchPercent(basePitch) ?: return null
        val minimumPitch = minPitchFor(voiceType)
        val maximumPitch = maxPitchFor(voiceType)
        val hintedPitchBias = pitchBiasFor(voiceHint)
        var adjusted = (base + voiceTuning.pitchBias + hintedPitchBias).coerceIn(minimumPitch, maximumPitch)

        when (detectedStyle) {
            "sad", "fearful" -> adjusted -= 0.5
            "angry", "cheerful" -> adjusted += 0.5
        }

        adjusted = adjusted.coerceIn(minimumPitch, maximumPitch)
        val rounded = adjusted.roundToInt()
        if (abs(rounded - base) < MIN_PITCH_DELTA) return null
        return if (rounded >= 0) "+$rounded%" else "$rounded%"
    }

    private fun pitchPercent(rawPitch: String): Double? {
        val pitch = rawPitch.trim()
        if (pitch.isBlank()) return 0.0
        if (pitch.endsWith("%")) {
            return pitch.dropLast(1).toDoubleOrNull()
        }
        return pitch.toDoubleOrNull()
    }

    private fun minPitchFor(voiceType: String): Double {
        return when (voiceType) {
            "elder_male", "elder_female", "monster", "mechanical" -> -4.0
            "mature_male", "mature_female" -> -3.0
            else -> -2.0
        }
    }

    private fun maxPitchFor(voiceType: String): Double {
        return when (voiceType) {
            "child_female", "child_male" -> 4.0
            "young_female", "young_male", "androgynous" -> 3.0
            else -> 2.0
        }
    }

    private fun styleDegreeFor(
        style: String,
        voiceTuning: AzureVoiceModelTuning.VoiceModelTuning,
        voiceHint: VoiceLineHint?,
        hintControlsStyle: Boolean
    ): String {
        val baseDegree = when (style) {
            "angry", "cheerful", "fearful" -> 0.25
            "sad", "disgruntled" -> 0.22
            "cute" -> 0.24
            "cutesy" -> 0.24
            "story-telling" -> 0.18
            "shy", "sorry", "tired", "whispering" -> 0.20
            "assassin", "captain", "cavalier", "game-narrator", "geomancer", "poet", "prince" -> 0.18
            else -> 0.25
        }
        val hintedBase = voiceHint?.intensity
            ?.takeIf { hintControlsStyle }
            ?.let { 0.14 + it.coerceIn(0.0, 1.0) * 0.18 }
            ?.coerceAtMost(baseDegree)
            ?: baseDegree
        val minDegree = minOf(0.12, baseDegree)
        val maxDegree = baseDegree + 0.05
        val degree = hintedBase.coerceIn(minDegree, maxDegree)
        return String.format(Locale.US, "%.2f", minOf(degree, voiceTuning.maxStyleDegree))
    }

    private fun pauseScaleFor(
        basePauseScale: Double,
        voiceHint: VoiceLineHint?
    ): Double {
        val multiplier = 1.0 + (voiceHint?.pause ?: 0)
            .coerceIn(HINT_MIN_DELTA, HINT_MAX_DELTA) * HINT_PAUSE_STEP
        return (basePauseScale * multiplier).coerceIn(MIN_HINTED_PAUSE_SCALE, MAX_HINTED_PAUSE_SCALE)
    }

    private fun supportsStyle(voiceName: String, style: String): Boolean {
        return style in supportedStylesFor(voiceName)
    }

    private fun supportedStylesFor(voiceName: String): Set<String> {
        return SUPPORTED_STYLES_BY_VOICE[voiceName] ?: emptySet()
    }

    private fun isNaturalDialogueStyle(style: String): Boolean {
        return style in NATURAL_DIALOGUE_STYLES
    }

    private fun String.hasAny(hints: Set<String>): Boolean {
        return hints.any { contains(it) }
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

    private val BASE_DIALOGUE_STYLES = setOf(
        "affectionate",
        "chat",
        "chat-casual",
        "comforting",
        "cute",
        "cutesy",
        "curious",
        "empathetic",
        "encouraging",
        "gentle"
    )

    private val EMOTION_DIALOGUE_STYLES = setOf(
        "angry",
        "anxious",
        "cheerful",
        "complaining",
        "debating",
        "disappointed",
        "disgruntled",
        "embarrassed",
        "fearful",
        "guilty",
        "lonely",
        "nervous",
        "sad",
        "sentimental",
        "serious",
        "shy",
        "sorry",
        "story-telling",
        "strict",
        "surprised",
        "tired",
        "whispering"
    )

    private val ROLE_DIALOGUE_STYLES = setOf(
        "assassin",
        "captain",
        "cavalier",
        "game-narrator",
        "geomancer",
        "poet",
        "prince"
    )

    private val NATURAL_DIALOGUE_STYLES = BASE_DIALOGUE_STYLES + EMOTION_DIALOGUE_STYLES + ROLE_DIALOGUE_STYLES

    private val SUPPORTED_STYLES_BY_VOICE = mapOf(
        "zh-CN-Xiaoxiao:DragonHDFlashLatestNeural" to setOf(
            "angry",
            "chat",
            "cheerful",
            "comforting",
            "customer-service",
            "debating",
            "disappointed",
            "excited",
            "fearful",
            "sad",
            "shy",
            "sorry",
            "strict",
            "voice-assistant",
            "whispering"
        ),
        "zh-CN-Xiaoxiao2:DragonHDFlashLatestNeural" to setOf(
            "affectionate",
            "angry",
            "anxious",
            "cheerful",
            "curious",
            "disappointed",
            "empathetic",
            "encouraging",
            "excited",
            "fearful",
            "guilty",
            "lonely",
            "poetry-reading",
            "sad",
            "sentimental",
            "sorry",
            "story-telling",
            "surprised",
            "tired",
            "whispering"
        ),
        "zh-CN-Xiaochen:DragonHDFlashLatestNeural" to setOf(
            "cheerful",
            "debating",
            "empathetic",
            "live-commercial",
            "poetry-reading",
            "sad",
            "sorry"
        ),
        "zh-CN-Xiaohan:DragonHDFlashLatestNeural" to setOf(
            "affectionate",
            "angry",
            "cheerful",
            "complaining",
            "fearful",
            "gentle",
            "sad",
            "shy",
            "strict"
        ),
        "zh-CN-Xiaoke:DragonHDFlashLatestNeural" to setOf(
            "angry",
            "customer-service",
            "cutesy",
            "excited",
            "fearful",
            "sad",
            "sorry",
            "whispering"
        ),
        "zh-CN-Xiaoshuang:DragonHDFlashLatestNeural" to setOf("chat"),
        "zh-CN-Xiaoyi:DragonHDFlashLatestNeural" to setOf(
            "angry",
            "cheerful",
            "complaining",
            "cute",
            "gentle",
            "nervous",
            "sad",
            "shy",
            "strict"
        ),
        "zh-CN-Xiaoyou:DragonHDFlashLatestNeural" to setOf(
            "angry",
            "chat",
            "cheerful",
            "cute",
            "poetry-reading",
            "sad",
            "story-telling"
        ),
        "zh-CN-Xiaoyu:DragonHDFlashLatestNeural" to setOf(
            "angry",
            "cheerful",
            "comforting",
            "debating",
            "sad",
            "sorry"
        ),
        "zh-CN-Yunhan:DragonHDFlashLatestNeural" to setOf(
            "angry",
            "cheerful",
            "curious",
            "empathetic",
            "encouraging",
            "excited",
            "guilty",
            "lonely",
            "sad",
            "serious",
            "sorry",
            "surprised",
            "tired",
            "whispering"
        ),
        "zh-CN-Yunxi:DragonHDFlashLatestNeural" to setOf(
            "angry",
            "chat",
            "cheerful",
            "complaining",
            "depressed",
            "fearful",
            "news",
            "sad",
            "shy",
            "strict",
            "voice-assistant"
        ),
        "zh-CN-Yunxia:DragonHDFlashLatestNeural" to setOf(
            "affectionate",
            "angry",
            "cheerful",
            "comforting",
            "encouraging",
            "excited",
            "fearful",
            "sad",
            "surprised"
        ),
        "zh-CN-Yunxiao:DragonHDFlashLatestNeural" to emptySet(),
        "zh-CN-Yunyi:DragonHDFlashLatestNeural" to setOf(
            "assassin",
            "captain",
            "cavalier",
            "game-narrator",
            "geomancer",
            "poet",
            "prince"
        ),
        "zh-CN-Yunye:DragonHDFlashLatestNeural" to emptySet(),
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

    private const val SHORT_LINE_LENGTH = 28
    private const val MIN_RATE_DELTA = 0.005
    private const val MIN_PITCH_DELTA = 0.5
    private const val MIN_EXPRESSION_DOUBLE_DELTA = 0.0001
    private const val MIN_HINTED_PAUSE_SCALE = 0.4
    private const val MAX_HINTED_PAUSE_SCALE = 1.2
    private const val MIN_HINT_CONFIDENCE = 0.55
    private const val HINT_MIN_DELTA = -2
    private const val HINT_MAX_DELTA = 2
    private const val HINT_RATE_STEP = 0.015
    private const val HINT_PITCH_STEP = 0.5
    private const val HINT_PAUSE_STEP = 0.07
    private const val MIN_USER_SPEED_MULTIPLIER = 0.80
    private const val MAX_USER_SPEED_MULTIPLIER = 1.50
    private const val NATURAL_DIALOGUE_MODE_VERSION = "natural_dialogue_v13"
    private val AZURE_RATE_WORDS = setOf("x-slow", "slow", "medium", "fast", "x-fast", "default")
}
