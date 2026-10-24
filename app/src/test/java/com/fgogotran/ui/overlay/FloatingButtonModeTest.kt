package com.fgogotran.ui.overlay

import com.fgogotran.subtitle.ScreenOcrWorkflow

import kotlin.test.Test
import kotlin.test.assertEquals

class FloatingButtonModeTest {
    @Test fun `dialogue has its own indicator while crop keeps temporary ownership`() {
        assertEquals(FloatingButtonMode.DIALOGUE,
            FloatingButtonMode.SEMI_AUTO.withSubtitleIndicator(ScreenOcrWorkflow.DIALOGUE_SUBTITLE))
        assertEquals(FloatingButtonMode.BATTLE,
            FloatingButtonMode.AUTO.withSubtitleIndicator(ScreenOcrWorkflow.BATTLE_SUBTITLE))
        for (mode in ScreenOcrWorkflow.entries) {
            assertEquals(FloatingButtonMode.CROP, FloatingButtonMode.CROP.withSubtitleIndicator(mode))
        }
    }

    @Test fun `confirmed battle overrides every translation label`() {
        listOf(
            FloatingButtonMode.MANUAL,
            FloatingButtonMode.SEMI_AUTO,
            FloatingButtonMode.AUTO
        ).forEach { mode ->
            assertEquals(FloatingButtonMode.BATTLE, mode.withBattleIndicator(true))
        }
    }

    @Test fun `crop selection stays visually authoritative during battle`() {
        assertEquals(
            FloatingButtonMode.CROP,
            FloatingButtonMode.CROP.withBattleIndicator(true)
        )
    }

    @Test fun `leaving battle restores the underlying translation label`() {
        FloatingButtonMode.entries.forEach { mode ->
            assertEquals(mode, mode.withBattleIndicator(false))
        }
    }
}
