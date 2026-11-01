package com.fgogotran.battle

import com.fgogotran.subtitle.ScreenOcrWorkflow
import com.fgogotran.subtitle.ScreenOcrWorkflowState

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BattleModeStateTest {
    @Test fun `state starts inactive and follows user selection`() {
        val state = ScreenOcrWorkflowState()

        assertFalse(state.isBattle)
        state.select(ScreenOcrWorkflow.BATTLE_SUBTITLE)
        assertTrue(state.isBattle)
        state.select(ScreenOcrWorkflow.STORY)
        assertFalse(state.isBattle)
    }
}
