package com.fgogotran.storylibrary

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The pack's size marker and the render scale it selects.
 *
 * Every scale is measured on a 2340x1080 capture of the game, using a full-width
 * glyph cell (a `[line N]` rule is N cells wide, and a run of kana repeats one
 * cell pitch):
 * - normal: `[line 3]` = 155 px -> 51.7 px per cell,
 * - `[f large]`: the same rule = 256 px -> 1.652x,
 * - `[f x-large]`: kana pitch = 120 px -> 2.32x.
 *
 * `SMALL` has no capture yet; 0.70x is the first estimate.
 */
class DialogueSizeTest {

    @Test
    fun `a leading marker selects the render size`() {
        assertEquals(DialogueSize.EXTRA_LARGE, DialogueSize.of("⟬XL⟭させるかああああっ！！！"))
        assertEquals(DialogueSize.LARGE, DialogueSize.of("⟬L⟭―――『銃神』！！"))
        assertEquals(DialogueSize.SMALL, DialogueSize.of("⟬S⟭こそこそ"))
        assertEquals(DialogueSize.NORMAL, DialogueSize.of("―――『銃神』！！"))
        assertEquals(DialogueSize.NORMAL, DialogueSize.of(""))
    }

    @Test
    fun `strip removes only the leading marker`() {
        assertEquals("させるかああああっ！！！", DialogueSize.strip("⟬XL⟭させるかああああっ！！！"))
        assertEquals("―――『銃神』！！", DialogueSize.strip("⟬L⟭―――『銃神』！！"))
        assertEquals("", DialogueSize.strip("⟬S⟭"))
        assertEquals("本文⟬L⟭", DialogueSize.strip("本文⟬L⟭"))
        // `⟬XL⟭` starts with `⟬` and an `L` only after the `X`; the strip must not
        // leave a stray `X⟭` behind, and a shorter marker must not match it.
        assertEquals("X", DialogueSize.strip("⟬L⟭X"))
    }

    @Test
    fun `scales match the measured game sizes`() {
        assertEquals(1f, DialogueSize.NORMAL.scale, 0f)
        assertEquals(1.652f, DialogueSize.LARGE.scale, 0f)
        assertEquals(2.32f, DialogueSize.EXTRA_LARGE.scale, 0f)
        assertEquals(0.70f, DialogueSize.SMALL.scale, 0f)
        // The renderer's CN/TW dialogue base is 53 px, so each class lands on the
        // game's measured cell: ~85 px for large, ~120 px for x-large, 37 px for small.
        assertEquals(87.6f, 53f * DialogueSize.LARGE.scale, 0.1f)
        assertEquals(123.0f, 53f * DialogueSize.EXTRA_LARGE.scale, 0.2f)
        assertEquals(37.1f, 53f * DialogueSize.SMALL.scale, 0.05f)
    }
}
