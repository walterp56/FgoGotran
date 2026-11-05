package com.fgogotran.data

import kotlin.test.Test
import kotlin.test.assertEquals

class AiVoiceSpeedSettingTest {
    @Test
    fun `voice speed is clamped and snapped to five percent increments`() {
        val cases = mapOf(
            0 to 50,
            25 to 50,
            49 to 50,
            50 to 50,
            52 to 50,
            53 to 55,
            100 to 100,
            101 to 100,
            103 to 105,
            197 to 195,
            198 to 200,
            200 to 200,
            250 to 200
        )

        cases.forEach { (input, expected) ->
            assertEquals(expected, SettingsRepository.normalizeAiVoiceSpeedPercent(input))
        }
    }

    @Test
    fun `voice speed range contains thirty one selections`() {
        val selections = (
            SettingsRepository.MIN_AI_VOICE_SPEED_PERCENT..
                SettingsRepository.MAX_AI_VOICE_SPEED_PERCENT
            step SettingsRepository.AI_VOICE_SPEED_STEP_PERCENT
            ).toList()

        assertEquals(31, selections.size)
        assertEquals(50, selections.first())
        assertEquals(100, selections[10])
        assertEquals(105, selections[11])
        assertEquals(200, selections.last())
    }
}
