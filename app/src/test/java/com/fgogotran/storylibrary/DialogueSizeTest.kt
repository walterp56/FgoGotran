package com.fgogotran.storylibrary

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The pack's size marker and the render scale it selects.
 *
 * `LARGE` comes from measuring the game: the same `[line 3]` rule is 155 px on a
 * normal line and 256 px on a large one (1.652x). `SMALL` is a first estimate
 * until an `[f small]` capture confirms it.
 */
class DialogueSizeTest {

    @Test
    fun `a leading marker selects the render size`() {
        assertEquals(DialogueSize.LARGE, DialogueSize.of("⟬L⟭―――『銃神』！！"))
        assertEquals(DialogueSize.SMALL, DialogueSize.of("⟬S⟭こそこそ"))
        assertEquals(DialogueSize.NORMAL, DialogueSize.of("―――『銃神』！！"))
        assertEquals(DialogueSize.NORMAL, DialogueSize.of(""))
    }

    @Test
    fun `strip removes only the leading marker`() {
        assertEquals("―――『銃神』！！", DialogueSize.strip("⟬L⟭―――『銃神』！！"))
        assertEquals("", DialogueSize.strip("⟬S⟭"))
        assertEquals("本文⟬L⟭", DialogueSize.strip("本文⟬L⟭"))
    }

    @Test
    fun `scales match the measured game sizes`() {
        assertEquals(1f, DialogueSize.NORMAL.scale, 0f)
        assertEquals(1.652f, DialogueSize.LARGE.scale, 0f)
        assertEquals(0.70f, DialogueSize.SMALL.scale, 0f)
        // The renderer's CN/TW dialogue base is 53 px, so the large line lands on the
        // game's ~85 px glyph advance and the small line stays legible at 37 px.
        assertEquals(87.6f, 53f * DialogueSize.LARGE.scale, 0.1f)
        assertEquals(37.1f, 53f * DialogueSize.SMALL.scale, 0.05f)
    }
}
