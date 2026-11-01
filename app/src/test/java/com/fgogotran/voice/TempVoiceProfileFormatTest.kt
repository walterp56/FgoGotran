package com.fgogotran.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class TempVoiceProfileFormatTest {
    @Test fun `observed extra empty style column is rejected instead of shifting reason to 100`() {
        assertNull(TempVoiceProfileFormat.columns(
            "elder_male\tzh-CN-YunyeNeural\t\t\t-2%\t0.95\t100\t年老男性"
        ))
        assertNull(TempVoiceProfileFormat.columns("elder_male\tzh-CN-YunyeNeural"))
        assertNull(TempVoiceProfileFormat.columns(VALID_ROW + "\textra"))
    }

    @Test fun `seven fields including the none sentinel preserve numeric columns and reason`() {
        val fields = assertNotNull(TempVoiceProfileFormat.columns(VALID_ROW))
        assertEquals("none", fields[2])
        assertEquals("-2%", fields[3])
        assertEquals("0.95", fields[4])
        assertEquals("100", fields[5])
        assertEquals("老人回憶往事", fields[6])
        assertNotNull(TempVoiceProfileFormat.columns(VALID_ROW.replace("\tnone\t", "\t\t")))
        assertNull(TempVoiceProfileFormat.columns(VALID_ROW + "\n" + VALID_ROW))
    }

    @Test fun `observed zero and negative multipliers use neutral speed instead of the slowest speed`() {
        for (raw in listOf("0", "-5", "", "NaN", "Infinity", "-Infinity", "-100%", "bad")) {
            assertEquals("1.00", TempVoiceProfileFormat.rate(raw), raw)
        }
    }

    @Test fun `valid rates and legacy percentages remain bounded and locale independent`() {
        assertEquals("0.95", TempVoiceProfileFormat.rate("0.95"))
        assertEquals("0.95", TempVoiceProfileFormat.rate("-5%"))
        assertEquals("1.00", TempVoiceProfileFormat.rate("0%"))
        assertEquals("1.10", TempVoiceProfileFormat.rate("1.5"))
        assertEquals("0.86", TempVoiceProfileFormat.rate("0.5"))
    }

    @Test fun `pitch requires its unit and invalid units remain neutral`() {
        for (raw in listOf("-2Hz", "-2", "NaN%", "Infinity%", "", "-2.5%")) {
            assertEquals("+0%", TempVoiceProfileFormat.pitch(raw), raw)
        }
        assertEquals("-2%", TempVoiceProfileFormat.pitch(" -2% "))
        assertEquals("+0%", TempVoiceProfileFormat.pitch("0%"))
        assertEquals("+8%", TempVoiceProfileFormat.pitch("+10%"))
        assertEquals("-8%", TempVoiceProfileFormat.pitch("-10%"))
    }

    @Test fun `volume requires an unsigned integer in range`() {
        assertEquals("100", TempVoiceProfileFormat.volume("100"))
        assertEquals("50", TempVoiceProfileFormat.volume("50"))
        for (raw in listOf("0%", "+0%", "+50", "-2", "101", "NaN", "0.95", "")) {
            assertEquals("100", TempVoiceProfileFormat.volume(raw), raw)
        }
    }

    @Test fun `unsupported temporary style cannot turn a neutral old man into cheerful`() {
        val profile = profile("zh-CN-YunyeNeural")
        // The general curated-profile fallback remains available elsewhere; not in temp rows.
        assertEquals("cheerful", VoiceEmotionStyle.resolveStyle(profile.copy(style = "gentle"), null))
        assertEquals("", TempVoiceProfileFormat.style(profile, "gentle"))
        for (raw in listOf("none", "NONE", "normal", "general", "default", "")) {
            assertEquals("", TempVoiceProfileFormat.style(profile, raw))
        }
        assertEquals("sad", TempVoiceProfileFormat.style(profile("zh-CN-XiaoxiaoNeural"), "sad"))
    }

    private fun profile(voice: String) = VoiceProfile("test", "azure", "zh-CN", voice, "", "0%", "1.00", "100", "elder_male")
    private companion object {
        const val VALID_ROW = "elder_male\tzh-CN-YunyeNeural\tnone\t-2%\t0.95\t100\t老人回憶往事"
    }
}
