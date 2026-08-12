package com.fgogotran.voice

internal object AzureVoiceModelTuning {
    fun forVoice(voiceName: String): VoiceModelTuning {
        val key = voiceName.trim()
        return TUNING_BY_VOICE[key] ?: fallbackFor(key)
    }

    fun allowedZhCnVoiceNames(): List<String> {
        return TUNING_BY_VOICE.keys
            .filter { it.startsWith("zh-CN-") }
            .sorted()
    }

    private fun fallbackFor(voiceName: String): VoiceModelTuning {
        return when {
            voiceName.contains(":DragonHDFlashLatestNeural") -> dragonHdFlashDialogue(voiceName)
            voiceName.contains("MultilingualNeural") -> multilingualDialogue(voiceName)
            voiceName.startsWith("zh-TW-") -> taiwanDialogue(voiceName)
            voiceName.startsWith("zh-HK-") ||
                voiceName.startsWith("yue-CN-") ||
                voiceName.startsWith("wuu-CN-") ||
                voiceName.startsWith("zh-CN-guangxi-") ||
                voiceName.startsWith("zh-CN-henan-") ||
                voiceName.startsWith("zh-CN-liaoning-") ||
                voiceName.startsWith("zh-CN-shaanxi-") ||
                voiceName.startsWith("zh-CN-shandong-") ||
                voiceName.startsWith("zh-CN-sichuan-") -> accentDialogue(voiceName)
            else -> cautiousDialogue(voiceName)
        }
    }

    data class VoiceModelTuning(
        val qualityTier: QualityTier,
        val rateBias: Double,
        val minRate: Double,
        val maxRate: Double,
        val maxStyleDegree: Double,
        val allowedStyles: Set<String>,
        val blockedStyles: Set<String> = BLOCKED_DIALOGUE_STYLES,
        val pitchBias: Double = 0.0,
        val pauseScale: Double = 1.0,
        val softenTypeRateFloor: Boolean = false
    ) {
        fun allowsStyle(style: String): Boolean {
            val normalized = style.trim()
            return normalized.isNotBlank() &&
                normalized in allowedStyles &&
                normalized !in blockedStyles &&
                maxStyleDegree >= MIN_EFFECTIVE_STYLE_DEGREE
        }
    }

    enum class QualityTier {
        A,
        B,
        C,
        D
    }

    private val FEMALE_DIALOGUE_STYLES = setOf(
        "affectionate",
        "angry",
        "chat",
        "chat-casual",
        "cheerful",
        "cute",
        "disgruntled",
        "embarrassed",
        "empathetic",
        "fearful",
        "gentle",
        "sad"
    )

    private val MALE_DIALOGUE_STYLES = setOf(
        "affectionate",
        "angry",
        "chat",
        "cheerful",
        "disgruntled",
        "embarrassed",
        "fearful",
        "gentle",
        "sad"
    )

    private val CHILD_DIALOGUE_STYLES = setOf(
        "affectionate",
        "angry",
        "chat",
        "cheerful",
        "cute",
        "gentle",
        "sad"
    )

    private val MULTILINGUAL_DIALOGUE_STYLES = setOf(
        "affectionate",
        "angry",
        "chat",
        "cheerful",
        "cute",
        "empathetic",
        "gentle",
        "sad"
    )

    private val CAUTIOUS_DIALOGUE_STYLES = setOf(
        "angry",
        "cheerful",
        "sad"
    )

    private val DRAGON_HD_FLASH_DIALOGUE_STYLES = setOf(
        "affectionate",
        "angry",
        "anxious",
        "chat",
        "cheerful",
        "comforting",
        "complaining",
        "curious",
        "cute",
        "cutesy",
        "debating",
        "disappointed",
        "empathetic",
        "encouraging",
        "excited",
        "fearful",
        "gentle",
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
        "whispering",
        "assassin",
        "captain",
        "cavalier",
        "game-narrator",
        "geomancer",
        "poet",
        "prince"
    )

    private val DRAGON_HD_FLASH_BLOCKED_STYLES = setOf(
        "customer-service",
        "live-commercial",
        "news",
        "poetry-reading",
        "voice-assistant"
    )

    private val BLOCKED_DIALOGUE_STYLES = setOf(
        "advertisement-upbeat",
        "assistant",
        "calm",
        "customer-service",
        "customerservice",
        "depressed",
        "documentary-narration",
        "excited",
        "friendly",
        "livecommercial",
        "lyrical",
        "narration-professional",
        "narration-relaxed",
        "newscast",
        "newscast-casual",
        "newscast-formal",
        "poetry-reading",
        "serious",
        "sorry",
        "sports-commentary",
        "sports-commentary-excited",
        "story",
        "story-telling",
        "voice-assistant",
        "whispering"
    )

    private fun femaleDialogue(voiceName: String): VoiceModelTuning = VoiceModelTuning(
        qualityTier = QualityTier.B,
        rateBias = 0.00,
        minRate = 0.96,
        maxRate = 1.07,
        maxStyleDegree = 0.22,
        allowedStyles = FEMALE_DIALOGUE_STYLES,
        pauseScale = 1.00
    )

    private fun multilingualDialogue(voiceName: String): VoiceModelTuning = VoiceModelTuning(
        qualityTier = QualityTier.A,
        rateBias = 0.00,
        minRate = 0.96,
        maxRate = 1.07,
        maxStyleDegree = 0.20,
        allowedStyles = MULTILINGUAL_DIALOGUE_STYLES,
        pauseScale = 1.00
    )

    private fun youngMaleDialogue(voiceName: String): VoiceModelTuning = VoiceModelTuning(
        qualityTier = QualityTier.B,
        rateBias = 0.00,
        minRate = 0.96,
        maxRate = 1.07,
        maxStyleDegree = 0.20,
        allowedStyles = MALE_DIALOGUE_STYLES,
        pauseScale = 1.00
    )

    private fun matureMaleDialogue(voiceName: String): VoiceModelTuning = VoiceModelTuning(
        qualityTier = QualityTier.C,
        rateBias = -0.02,
        minRate = 0.93,
        maxRate = 1.04,
        maxStyleDegree = 0.16,
        allowedStyles = MALE_DIALOGUE_STYLES,
        pauseScale = 1.05
    )

    private fun childDialogue(voiceName: String): VoiceModelTuning = VoiceModelTuning(
        qualityTier = QualityTier.B,
        rateBias = -0.01,
        minRate = 0.98,
        maxRate = 1.08,
        maxStyleDegree = 0.20,
        allowedStyles = CHILD_DIALOGUE_STYLES,
        pitchBias = -0.5,
        pauseScale = 1.00
    )

    private fun noStyleDialogue(voiceName: String): VoiceModelTuning = VoiceModelTuning(
        qualityTier = QualityTier.B,
        rateBias = -0.01,
        minRate = 0.95,
        maxRate = 1.06,
        maxStyleDegree = 0.0,
        allowedStyles = emptySet(),
        pauseScale = 1.02
    )

    private fun taiwanDialogue(voiceName: String): VoiceModelTuning = VoiceModelTuning(
        qualityTier = QualityTier.B,
        rateBias = 0.00,
        minRate = 0.92,
        maxRate = 1.04,
        maxStyleDegree = 0.0,
        allowedStyles = emptySet(),
        pauseScale = 1.08,
        softenTypeRateFloor = true
    )

    private fun dragonHdFlashDialogue(voiceName: String): VoiceModelTuning = VoiceModelTuning(
        qualityTier = QualityTier.A,
        rateBias = 0.00,
        minRate = 0.94,
        maxRate = 1.07,
        maxStyleDegree = 0.34,
        allowedStyles = DRAGON_HD_FLASH_DIALOGUE_STYLES,
        blockedStyles = DRAGON_HD_FLASH_BLOCKED_STYLES,
        pauseScale = 1.05,
        softenTypeRateFloor = true
    )

    private fun dragonHdFlashChildDialogue(voiceName: String): VoiceModelTuning = VoiceModelTuning(
        qualityTier = QualityTier.A,
        rateBias = -0.01,
        minRate = 0.97,
        maxRate = 1.08,
        maxStyleDegree = 0.32,
        allowedStyles = DRAGON_HD_FLASH_DIALOGUE_STYLES,
        blockedStyles = DRAGON_HD_FLASH_BLOCKED_STYLES,
        pitchBias = -0.5,
        pauseScale = 1.04,
        softenTypeRateFloor = true
    )

    private fun dragonHdFlashMaleDialogue(voiceName: String): VoiceModelTuning = VoiceModelTuning(
        qualityTier = QualityTier.A,
        rateBias = -0.01,
        minRate = 0.92,
        maxRate = 1.05,
        maxStyleDegree = 0.32,
        allowedStyles = DRAGON_HD_FLASH_DIALOGUE_STYLES,
        blockedStyles = DRAGON_HD_FLASH_BLOCKED_STYLES,
        pauseScale = 1.06,
        softenTypeRateFloor = true
    )

    private fun scenarioDialogue(voiceName: String): VoiceModelTuning = VoiceModelTuning(
        qualityTier = QualityTier.D,
        rateBias = -0.02,
        minRate = 0.94,
        maxRate = 1.04,
        maxStyleDegree = 0.0,
        allowedStyles = emptySet(),
        pauseScale = 1.08
    )

    private fun cautiousDialogue(voiceName: String): VoiceModelTuning = VoiceModelTuning(
        qualityTier = QualityTier.C,
        rateBias = -0.02,
        minRate = 0.94,
        maxRate = 1.05,
        maxStyleDegree = 0.12,
        allowedStyles = CAUTIOUS_DIALOGUE_STYLES,
        pauseScale = 1.06
    )

    private fun accentDialogue(voiceName: String): VoiceModelTuning = VoiceModelTuning(
        qualityTier = QualityTier.C,
        rateBias = -0.02,
        minRate = 0.93,
        maxRate = 1.04,
        maxStyleDegree = 0.0,
        allowedStyles = emptySet(),
        pauseScale = 1.08
    )

    private val TUNING_BY_VOICE = mapOf(
        "zh-TW-HsiaoChenNeural" to taiwanDialogue("zh-TW-HsiaoChenNeural"),
        "zh-TW-HsiaoYuNeural" to taiwanDialogue("zh-TW-HsiaoYuNeural"),
        "zh-TW-YunJheNeural" to taiwanDialogue("zh-TW-YunJheNeural"),
        "zh-CN-XiaoxiaoNeural" to femaleDialogue("zh-CN-XiaoxiaoNeural"),
        "zh-CN-XiaochenNeural" to scenarioDialogue("zh-CN-XiaochenNeural"),
        "zh-CN-XiaochenMultilingualNeural" to multilingualDialogue("zh-CN-XiaochenMultilingualNeural"),
        "zh-CN-XiaohanNeural" to femaleDialogue("zh-CN-XiaohanNeural"),
        "zh-CN-XiaomengNeural" to noStyleDialogue("zh-CN-XiaomengNeural"),
        "zh-CN-XiaomoNeural" to femaleDialogue("zh-CN-XiaomoNeural"),
        "zh-CN-XiaoqiuNeural" to cautiousDialogue("zh-CN-XiaoqiuNeural"),
        "zh-CN-XiaorouNeural" to cautiousDialogue("zh-CN-XiaorouNeural"),
        "zh-CN-XiaoruiNeural" to cautiousDialogue("zh-CN-XiaoruiNeural"),
        "zh-CN-XiaoshuangNeural" to childDialogue("zh-CN-XiaoshuangNeural"),
        "zh-CN-XiaoshuangMultilingualNeural" to childDialogue("zh-CN-XiaoshuangMultilingualNeural"),
        "zh-CN-XiaoxiaoMultilingualNeural" to multilingualDialogue("zh-CN-XiaoxiaoMultilingualNeural"),
        "zh-CN-XiaoyanNeural" to cautiousDialogue("zh-CN-XiaoyanNeural"),
        "zh-CN-XiaoyiNeural" to femaleDialogue("zh-CN-XiaoyiNeural"),
        "zh-CN-XiaoyouNeural" to childDialogue("zh-CN-XiaoyouNeural"),
        "zh-CN-XiaoyouMultilingualNeural" to childDialogue("zh-CN-XiaoyouMultilingualNeural"),
        "zh-CN-XiaoyuMultilingualNeural" to multilingualDialogue("zh-CN-XiaoyuMultilingualNeural"),
        "zh-CN-XiaozhenNeural" to cautiousDialogue("zh-CN-XiaozhenNeural"),
        "zh-CN-YunxiNeural" to youngMaleDialogue("zh-CN-YunxiNeural"),
        "zh-CN-YunjianNeural" to matureMaleDialogue("zh-CN-YunjianNeural"),
        "zh-CN-YunyangNeural" to scenarioDialogue("zh-CN-YunyangNeural"),
        "zh-CN-YunfanMultilingualNeural" to multilingualDialogue("zh-CN-YunfanMultilingualNeural"),
        "zh-CN-YunfengNeural" to matureMaleDialogue("zh-CN-YunfengNeural"),
        "zh-CN-YunhaoNeural" to scenarioDialogue("zh-CN-YunhaoNeural"),
        "zh-CN-YunjieNeural" to cautiousDialogue("zh-CN-YunjieNeural"),
        "zh-CN-YunxiaNeural" to youngMaleDialogue("zh-CN-YunxiaNeural"),
        "zh-CN-YunxiaoMultilingualNeural" to multilingualDialogue("zh-CN-YunxiaoMultilingualNeural"),
        "zh-CN-YunyeNeural" to matureMaleDialogue("zh-CN-YunyeNeural"),
        "zh-CN-YunyiMultilingualNeural" to multilingualDialogue("zh-CN-YunyiMultilingualNeural"),
        "zh-CN-YunzeNeural" to matureMaleDialogue("zh-CN-YunzeNeural"),
        "zh-HK-HiuMaanNeural" to accentDialogue("zh-HK-HiuMaanNeural"),
        "zh-HK-HiuGaaiNeural" to accentDialogue("zh-HK-HiuGaaiNeural"),
        "zh-HK-WanLungNeural" to accentDialogue("zh-HK-WanLungNeural"),
        "yue-CN-XiaoMinNeural" to accentDialogue("yue-CN-XiaoMinNeural"),
        "yue-CN-YunSongNeural" to accentDialogue("yue-CN-YunSongNeural"),
        "wuu-CN-YunzheNeural" to accentDialogue("wuu-CN-YunzheNeural"),
        "zh-CN-guangxi-YunqiNeural" to accentDialogue("zh-CN-guangxi-YunqiNeural"),
        "zh-CN-henan-YundengNeural" to accentDialogue("zh-CN-henan-YundengNeural"),
        "zh-CN-liaoning-XiaobeiNeural" to accentDialogue("zh-CN-liaoning-XiaobeiNeural"),
        "zh-CN-liaoning-YunbiaoNeural" to accentDialogue("zh-CN-liaoning-YunbiaoNeural"),
        "zh-CN-shaanxi-XiaoniNeural" to accentDialogue("zh-CN-shaanxi-XiaoniNeural"),
        "zh-CN-shandong-YunxiangNeural" to accentDialogue("zh-CN-shandong-YunxiangNeural"),
        "zh-CN-sichuan-YunxiNeural" to accentDialogue("zh-CN-sichuan-YunxiNeural"),
        "zh-CN-Xiaoxiao:DragonHDFlashLatestNeural" to dragonHdFlashDialogue("zh-CN-Xiaoxiao:DragonHDFlashLatestNeural"),
        "zh-CN-Xiaoxiao2:DragonHDFlashLatestNeural" to dragonHdFlashDialogue("zh-CN-Xiaoxiao2:DragonHDFlashLatestNeural"),
        "zh-CN-Xiaochen:DragonHDFlashLatestNeural" to dragonHdFlashDialogue("zh-CN-Xiaochen:DragonHDFlashLatestNeural"),
        "zh-CN-Xiaohan:DragonHDFlashLatestNeural" to dragonHdFlashDialogue("zh-CN-Xiaohan:DragonHDFlashLatestNeural"),
        "zh-CN-Xiaoke:DragonHDFlashLatestNeural" to dragonHdFlashDialogue("zh-CN-Xiaoke:DragonHDFlashLatestNeural"),
        "zh-CN-Xiaoshuang:DragonHDFlashLatestNeural" to dragonHdFlashChildDialogue("zh-CN-Xiaoshuang:DragonHDFlashLatestNeural"),
        "zh-CN-Xiaoyi:DragonHDFlashLatestNeural" to dragonHdFlashDialogue("zh-CN-Xiaoyi:DragonHDFlashLatestNeural"),
        "zh-CN-Xiaoyou:DragonHDFlashLatestNeural" to dragonHdFlashChildDialogue("zh-CN-Xiaoyou:DragonHDFlashLatestNeural"),
        "zh-CN-Xiaoyu:DragonHDFlashLatestNeural" to dragonHdFlashDialogue("zh-CN-Xiaoyu:DragonHDFlashLatestNeural"),
        "zh-CN-Yunhan:DragonHDFlashLatestNeural" to dragonHdFlashMaleDialogue("zh-CN-Yunhan:DragonHDFlashLatestNeural"),
        "zh-CN-Yunxi:DragonHDFlashLatestNeural" to dragonHdFlashMaleDialogue("zh-CN-Yunxi:DragonHDFlashLatestNeural"),
        "zh-CN-Yunxia:DragonHDFlashLatestNeural" to dragonHdFlashMaleDialogue("zh-CN-Yunxia:DragonHDFlashLatestNeural"),
        "zh-CN-Yunxiao:DragonHDFlashLatestNeural" to dragonHdFlashMaleDialogue("zh-CN-Yunxiao:DragonHDFlashLatestNeural"),
        "zh-CN-Yunyi:DragonHDFlashLatestNeural" to dragonHdFlashMaleDialogue("zh-CN-Yunyi:DragonHDFlashLatestNeural"),
        "zh-CN-Yunye:DragonHDFlashLatestNeural" to dragonHdFlashMaleDialogue("zh-CN-Yunye:DragonHDFlashLatestNeural")
    )

    private const val MIN_EFFECTIVE_STYLE_DEGREE = 0.01
}
