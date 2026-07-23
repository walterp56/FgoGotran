package com.fgogotran.voice

object ChineseVoiceEmotionStyle {
    fun styleFor(profile: VoiceProfile, text: String): String? {
        if (!profile.locale.equals("zh-CN", ignoreCase = true)) return null
        val style = detectStyle(text) ?: return null
        return style.takeIf { it in supportedStylesFor(profile.voiceName) }
    }

    private fun detectStyle(text: String): String? {
        val normalized = text.replace(Regex("\\s+"), "")
        return when {
            normalized.hasAny(SAD_HINTS) -> "sad"
            normalized.hasAny(FEARFUL_HINTS) -> "fearful"
            normalized.hasAny(ANGRY_HINTS) -> "angry"
            normalized.hasAny(CHEERFUL_HINTS) -> "cheerful"
            normalized.hasAny(DISGRUNTLED_HINTS) -> "disgruntled"
            normalized.hasAny(SERIOUS_HINTS) -> "serious"
            else -> null
        }
    }

    private fun supportedStylesFor(voiceName: String): Set<String> {
        return SUPPORTED_STYLES_BY_VOICE[voiceName] ?: COMMON_CN_STYLES
    }

    private fun String.hasAny(hints: Set<String>): Boolean {
        return hints.any { contains(it) }
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
        "zh-CN-XiaoxiaoNeural" to COMMON_CN_STYLES + setOf("gentle", "chat", "calm"),
        "zh-CN-XiaoyiNeural" to COMMON_CN_STYLES + setOf("gentle", "embarrassed"),
        "zh-CN-YunyeNeural" to COMMON_CN_STYLES + setOf("calm", "embarrassed"),
        "zh-CN-YunxiNeural" to COMMON_CN_STYLES + setOf("chat", "embarrassed", "depressed"),
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
}
