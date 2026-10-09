package com.fgogotran.voice

import com.fgogotran.translation.VoiceLineHint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VoiceEmotionStyleTest {
    @Test
    fun `english cues use the english dictionaries`() {
        assertEquals("sad", VoiceEmotionStyle.detectStyle("I'm sorry, I didn't mean to..."))
        assertEquals("cheerful", VoiceEmotionStyle.detectStyle("Thank you so much!"))
        assertEquals("fearful", VoiceEmotionStyle.detectStyle("No! Stay away, I'm scared."))
        assertEquals("angry", VoiceEmotionStyle.detectStyle("Damn it, shut up!"))
        assertEquals("disgruntled", VoiceEmotionStyle.detectStyle("Good grief, what a pain."))
        assertNull(VoiceEmotionStyle.detectStyle("The sun is rising over the hills."))
    }

    @Test
    fun `english typographic apostrophes still match`() {
        assertEquals("sad", VoiceEmotionStyle.detectStyle("I\u2019m sorry to hear that."))
    }

    @Test
    fun `chinese cues keep the existing dictionaries`() {
        assertEquals("sad", VoiceEmotionStyle.detectStyle("对不起……"))
        assertEquals("cheerful", VoiceEmotionStyle.detectStyle("太好了！谢谢你！"))
        assertNull(VoiceEmotionStyle.detectStyle("太阳升起来了。"))
    }

    @Test
    fun `english read text keeps single spaces while chinese text stays compact`() {
        assertEquals("I'm sorry", VoiceEmotionStyle.normalizedTextForStyleMatching("I'm   sorry"))
        assertEquals("对不起", VoiceEmotionStyle.normalizedTextForStyleMatching("  对不起  "))
        assertEquals("Mashです", VoiceEmotionStyle.normalizedTextForStyleMatching("Mash  です"))
    }

    @Test
    fun `unsupported profile styles fall back to the nearest supported style`() {
        assertEquals(
            "comforting",
            VoiceEmotionStyle.resolveStyle(profile("zh-CN-Xiaoxiao:DragonHDFlashLatestNeural", "gentle"), null)
        )
        assertEquals(
            "empathetic",
            VoiceEmotionStyle.resolveStyle(profile("zh-CN-Xiaoxiao2:DragonHDFlashLatestNeural", "gentle"), null)
        )
        assertEquals(
            "cheerful",
            VoiceEmotionStyle.resolveStyle(profile("zh-CN-Yunhan:DragonHDFlashLatestNeural", "chat"), null)
        )
        assertEquals(
            "strict",
            VoiceEmotionStyle.resolveStyle(profile("zh-CN-Yunxi:DragonHDFlashLatestNeural", "debating"), null)
        )
    }

    @Test
    fun `supported styles are kept unchanged`() {
        assertEquals(
            "sad",
            VoiceEmotionStyle.resolveStyle(profile("zh-CN-Xiaoxiao:DragonHDFlashLatestNeural", "sad"), null)
        )
        assertEquals(
            "assassin",
            VoiceEmotionStyle.resolveStyle(profile("zh-CN-Yunyi:DragonHDFlashLatestNeural", "assassin"), null)
        )
    }

    @Test
    fun `voices without a matching style keep an empty style`() {
        assertEquals(
            "",
            VoiceEmotionStyle.resolveStyle(profile("zh-CN-Yunyi:DragonHDFlashLatestNeural", "serious"), null)
        )
        assertEquals(
            "",
            VoiceEmotionStyle.resolveStyle(profile("zh-CN-Yunyi:DragonHDFlashLatestNeural", "chat"), null)
        )
    }

    @Test
    fun `API cutesy stays cutesy rather than being mapped to cute`() {
        val profile = profile(XIAOKE_FLASH, "sad")
        val expression = assertNotNull(VoiceEmotionStyle.expressionFor(
            profile, NEUTRAL_LINE, VoiceLineHint(styles = listOf("cutesy"), confidence = 0.9)
        ))
        assertEquals("cutesy", expression.styleOverride)
        assertEquals("cutesy", VoiceEmotionStyle.resolveStyle(profile, expression.styleOverride))
        assertTrue(expression.voiceHintApplied)
    }

    @Test
    fun `exact Xiaoxiao2 API styles retain their distinct emotions`() {
        for (style in listOf("anxious", "affectionate", "empathetic")) {
            val expression = assertNotNull(VoiceEmotionStyle.expressionFor(
                profile(XIAOXIAO2_FLASH, "sad"), NEUTRAL_LINE,
                VoiceLineHint(styles = listOf(style), confidence = 0.9)
            ))
            assertEquals(style, expression.styleOverride)
        }
    }

    @Test
    fun `unsupported API strict and serious do not become disappointed`() {
        val profile = profile(XIAOXIAO2_FLASH, "anxious")
        val local = VoiceEmotionStyle.expressionFor(profile, NEUTRAL_LINE)
        val expression = assertNotNull(VoiceEmotionStyle.expressionFor(
            profile, NEUTRAL_LINE,
            VoiceLineHint(styles = listOf("strict", "serious"), confidence = 0.9)
        ))
        assertEquals(local, expression)
        assertNull(expression.styleOverride)
        assertEquals("anxious", VoiceEmotionStyle.resolveStyle(profile, expression.styleOverride))
        assertFalse(expression.voiceHintApplied)
    }

    @Test
    fun `hint for another voice is ignored including its prosody`() {
        val profile = profile(XIAOKE_FLASH, "sad")
        val hint = VoiceLineHint(
            styles = listOf("angry"), rate = 2, pitch = 2, pause = 2, confidence = 0.9,
            targetVoiceNames = setOf(XIAOXIAO2_FLASH)
        )
        assertEquals(
            VoiceEmotionStyle.expressionFor(profile, NEUTRAL_LINE),
            VoiceEmotionStyle.expressionFor(profile, NEUTRAL_LINE, hint)
        )
    }

    @Test
    fun `low confidence hint keeps local expression unchanged`() {
        val profile = profile(XIAOXIAO2_FLASH, "anxious")
        assertEquals(
            VoiceEmotionStyle.expressionFor(profile, NEUTRAL_LINE),
            VoiceEmotionStyle.expressionFor(profile, NEUTRAL_LINE, VoiceLineHint(
                styles = listOf("angry"), rate = 2, pitch = 2, pause = 2, confidence = 0.54
            ))
        )
    }

    @Test
    fun `ON without a usable API hint still keeps local keyword expression`() {
        val profile = profile(XIAOXIAO2_FLASH, "anxious")
        val local = assertNotNull(VoiceEmotionStyle.expressionFor(profile, "对不起……"))
        assertEquals("sad", local.styleOverride)
        assertFalse(local.voiceHintApplied)
        assertEquals(local, VoiceEmotionStyle.expressionFor(
            profile, "对不起……", VoiceLineHint(styles = listOf("strict"), confidence = 0.9)
        ))
    }

    @Test
    fun `OFF ignores Chinese and English sentence emotion and excited punctuation`() {
        val profile = profile(XIAOXIAO2_FLASH, "anxious")
        val preset = assertNotNull(VoiceEmotionStyle.expressionFor(
            profile, NEUTRAL_LINE, emotionEnhancementEnabled = false
        ))
        for (line in listOf("对不起……", "太好了！谢谢你！！", "滚开！", "I'm sorry...", "Thank you!!", "Damn it!")) {
            assertEquals(preset, VoiceEmotionStyle.expressionFor(
                profile, line, emotionEnhancementEnabled = false
            ), line)
        }
        assertNull(preset.styleOverride)
        assertEquals("anxious", VoiceEmotionStyle.resolveStyle(profile, preset.styleOverride))
        assertFalse(preset.voiceHintApplied)
    }

    @Test
    fun `OFF ignores an earlier API style and all delivery deltas`() {
        val profile = profile(XIAOXIAO2_FLASH, "anxious")
        val preset = VoiceEmotionStyle.expressionFor(profile, NEUTRAL_LINE, emotionEnhancementEnabled = false)
        val hinted = VoiceEmotionStyle.expressionFor(
            profile, "太好了！！", VoiceLineHint(
                styles = listOf("cheerful"), intensity = 0.9,
                rate = 2, pitch = 2, pause = 2, confidence = 0.9
            ), emotionEnhancementEnabled = false
        )
        assertEquals(preset, hinted)
        assertFalse(assertNotNull(hinted).voiceHintApplied)
    }

    @Test
    fun `OFF preserves existing static voice tuning and base style intensity`() {
        val profile = profile(XIAOXIAO2_FLASH, "anxious")
        val localNeutral = assertNotNull(VoiceEmotionStyle.expressionFor(profile, NEUTRAL_LINE))
        val preset = assertNotNull(VoiceEmotionStyle.expressionFor(
            profile, "太好了！！", emotionEnhancementEnabled = false
        ))
        assertEquals(localNeutral.copy(ssmlModeVersion = preset.ssmlModeVersion), preset)
    }

    @Test
    fun `OFF still applies the selected user speed`() {
        val profile = profile(XIAOXIAO2_FLASH, "anxious")
        val normal = assertNotNull(VoiceEmotionStyle.expressionFor(
            profile, NEUTRAL_LINE, emotionEnhancementEnabled = false
        ))
        val faster = assertNotNull(VoiceEmotionStyle.expressionFor(
            profile, NEUTRAL_LINE, baseSpeedMultiplier = 1.5, emotionEnhancementEnabled = false
        ))
        assertTrue(assertNotNull(faster.rateOverride).toDouble() >
            (normal.rateOverride?.toDouble() ?: profile.rate.toDouble()))
        assertEquals(normal.styleOverride, faster.styleOverride)
        assertEquals(normal.pitchOverride, faster.pitchOverride)
        assertEquals(normal.pauseScale, faster.pauseScale)
    }

    @Test
    fun `OFF retains saved role and supported profile style fallback`() {
        val cases = listOf(
            profile("zh-CN-Yunyi:DragonHDFlashLatestNeural", "assassin") to "assassin",
            profile("zh-CN-Xiaoxiao:DragonHDFlashLatestNeural", "gentle") to "comforting"
        )
        for ((profile, expectedStyle) in cases) {
            val preset = assertNotNull(VoiceEmotionStyle.expressionFor(
                profile, "太好了！！", emotionEnhancementEnabled = false
            ))
            assertNull(preset.styleOverride)
            assertEquals(expectedStyle, VoiceEmotionStyle.resolveStyle(profile, preset.styleOverride))
        }
    }

    @Test
    fun `OFF does not invent a style for a styleless preset`() {
        val profile = profile(XIAOXIAO2_FLASH, "")
        val preset = assertNotNull(VoiceEmotionStyle.expressionFor(
            profile, "太好了！！", emotionEnhancementEnabled = false
        ))
        assertNull(preset.styleOverride)
        assertNull(preset.styleDegree)
        assertEquals("", VoiceEmotionStyle.resolveStyle(profile, preset.styleOverride))
    }

    @Test
    fun `OFF preserves the existing non Chinese voice behaviour`() {
        val profile = profile("en-US-JennyNeural", "cheerful").copy(locale = "en-US")
        assertNull(VoiceEmotionStyle.expressionFor(
            profile, "Damn it!!", emotionEnhancementEnabled = false
        ))
        assertEquals("cheerful", VoiceEmotionStyle.resolveStyle(profile, null))
    }

    @Test
    fun `OFF audio cache cannot reuse ON audio even with identical delivery values`() {
        val profile = profile(XIAOXIAO2_FLASH, "anxious")
        val localNeutral = assertNotNull(VoiceEmotionStyle.expressionFor(profile, NEUTRAL_LINE))
        val preset = assertNotNull(VoiceEmotionStyle.expressionFor(
            profile, NEUTRAL_LINE, emotionEnhancementEnabled = false
        ))
        val onRequest = VoiceSynthesisRequest(
            speakerName = "Olga", spokenText = NEUTRAL_LINE, profile = profile,
            styleOverride = localNeutral.styleOverride, rateOverride = localNeutral.rateOverride,
            pitchOverride = localNeutral.pitchOverride, styleDegree = localNeutral.styleDegree,
            pauseScale = localNeutral.pauseScale, ssmlModeVersion = localNeutral.ssmlModeVersion
        )
        val offRequest = onRequest.copy(ssmlModeVersion = preset.ssmlModeVersion)
        assertFalse(onRequest.cacheMaterial() == offRequest.cacheMaterial())
        assertEquals("natural_dialogue_v14", localNeutral.ssmlModeVersion)
        assertEquals("natural_dialogue_v14:presets_only_v1", preset.ssmlModeVersion)
    }

    @Test
    fun `allowed styles reflect voice support and existing dialogue tuning`() {
        assertEquals(
            setOf("angry", "cutesy", "fearful", "sad", "sorry", "whispering"),
            VoiceEmotionStyle.usableHintStylesFor(XIAOKE_FLASH)
        )
        val flashStyles = VoiceEmotionStyle.usableHintStylesFor(XIAOXIAO2_FLASH)
        assertTrue(flashStyles.containsAll(listOf("anxious", "affectionate", "empathetic")))
        assertTrue(flashStyles.intersect(setOf("strict", "serious", "poetry-reading", "story-telling")).isEmpty())
        val ordinaryStyles = VoiceEmotionStyle.usableHintStylesFor("zh-CN-XiaoxiaoNeural")
        assertTrue("sad" in ordinaryStyles)
        assertFalse("serious" in ordinaryStyles)
        assertFalse("whispering" in ordinaryStyles)
    }

    @Test
    fun `API cannot switch character roles but existing profile role is retained`() {
        val voice = "zh-CN-Yunyi:DragonHDFlashLatestNeural"
        assertTrue(VoiceEmotionStyle.usableHintStylesFor(voice).isEmpty())
        val profile = profile(voice, "assassin")
        val expression = assertNotNull(VoiceEmotionStyle.expressionFor(
            profile, NEUTRAL_LINE, VoiceLineHint(styles = listOf("poet"), confidence = 0.9)
        ))
        assertNull(expression.styleOverride)
        assertEquals("assassin", VoiceEmotionStyle.resolveStyle(profile, expression.styleOverride))
    }

    @Test
    fun `styleless and unknown voices do not advertise unrelated styles`() {
        for (voice in listOf("zh-CN-Yunxiao:DragonHDFlashLatestNeural",
            "zh-CN-Yunye:DragonHDFlashLatestNeural", "zh-CN-UnknownNeural")) {
            assertTrue(VoiceEmotionStyle.usableHintStylesFor(voice).isEmpty(), voice)
        }
    }

    @Test
    fun `combined voices advertise only common usable styles`() {
        val context = VoiceEmotionStyle.hintContextFor(listOf(XIAOKE_FLASH, XIAOXIAO2_FLASH, XIAOKE_FLASH))
        assertEquals(listOf(XIAOKE_FLASH, XIAOXIAO2_FLASH), context.voiceNames)
        assertEquals(listOf("angry", "fearful", "sad", "sorry", "whispering"), context.allowedStyles)
        assertTrue(VoiceEmotionStyle.hintContextFor(listOf(XIAOKE_FLASH, "unknown")).allowedStyles.isEmpty())
        assertTrue(VoiceEmotionStyle.hintContextFor(emptyList()).allowedStyles.isEmpty())
    }

    @Test
    fun `every advertised voice style can be applied without substitution`() {
        for (voice in AzureVoiceModelTuning.allowedZhCnVoiceNames()) {
            for (style in VoiceEmotionStyle.usableHintStylesFor(voice)) {
                assertEquals(style, VoiceEmotionStyle.resolveStyle(profile(voice, style), null), "$voice: $style")
                val expression = assertNotNull(VoiceEmotionStyle.expressionFor(
                    profile(voice, ""), NEUTRAL_LINE, VoiceLineHint(styles = listOf(style), confidence = 0.9)
                ))
                assertEquals(style, expression.styleOverride, "$voice: $style")
            }
        }
    }

    @Test
    fun `prepared target is bound to the same server and speaker set`() {
        val profiles = mapOf("A" to profile(XIAOKE_FLASH, "sad"), "B" to profile(XIAOXIAO2_FLASH, "anxious"))
        val target = PreparedVoiceTarget("jp", profiles)
        assertTrue(target.matches("jp", listOf("B", "A")))
        assertFalse(target.matches("tw", listOf("A", "B")))
        assertFalse(target.matches("jp", listOf("A")))
        assertEquals(VoiceEmotionStyle.hintContextFor(listOf(XIAOKE_FLASH, XIAOXIAO2_FLASH)), target.hintContext)
    }

    private companion object {
        const val XIAOKE_FLASH = "zh-CN-Xiaoke:DragonHDFlashLatestNeural"
        const val XIAOXIAO2_FLASH = "zh-CN-Xiaoxiao2:DragonHDFlashLatestNeural"
        const val NEUTRAL_LINE = "太阳升起来了。"
    }

    private fun profile(voice: String, style: String): VoiceProfile = VoiceProfile(
        profileId = "test",
        provider = "azure",
        locale = "zh-CN",
        voiceName = voice,
        style = style,
        pitch = "0%",
        rate = "1.00",
        volume = "100",
        description = "young_female"
    )
}
