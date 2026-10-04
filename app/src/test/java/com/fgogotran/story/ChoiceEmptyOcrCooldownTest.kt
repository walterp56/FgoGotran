package com.fgogotran.story

import com.fgogotran.overlay.FgoReferenceRect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChoiceEmptyOcrCooldownTest {
    private val cooldown = ChoiceRecognitionPolicy.EmptyOcrCooldown()
    private val button = FgoReferenceRect(220, 250, 1690, 385)

    @Test
    fun `same empty content waits only until its bounded retry time`() {
        assertFalse(cooldown.isCoolingDown(frame(), 100L))
        assertEquals(600L, cooldown.recordEmpty(frame(), 100L))
        assertTrue(cooldown.isCoolingDown(frame(), 699L))
        assertFalse(cooldown.isCoolingDown(frame(), 700L))
        assertEquals(1_200L, cooldown.recordEmpty(frame(), 700L))
        assertFalse(cooldown.isCoolingDown(frame(), 1_900L))
        repeat(5) { assertEquals(1_200L, cooldown.recordEmpty(frame(), 1_900L + it * 1_200)) }
    }

    @Test
    fun `new text in identical button positions never inherits a failed-content delay`() {
        cooldown.recordEmpty(frame(), 100L)
        assertFalse(cooldown.isCoolingDown(frame(bits = 2L), 200L))
        assertEquals(600L, cooldown.recordEmpty(frame(bits = 2L), 200L))
    }

    @Test
    fun `a blank panel gaining punctuation or ruby is new content`() {
        cooldown.recordEmpty(frame(bits = 0L), 100L)
        assertFalse(cooldown.isCoolingDown(frame(bits = 1L), 200L))
        cooldown.recordEmpty(frame(bits = 1L), 200L)
        assertFalse(cooldown.isCoolingDown(frame(bits = 3L), 300L))
    }

    @Test
    fun `new geometry and button count bypass cooldown`() {
        cooldown.recordEmpty(frame(), 100L)
        assertFalse(cooldown.isCoolingDown(frame(rows = listOf(button.copy(top = 260))), 200L))
        cooldown.recordEmpty(frame(), 300L)
        assertFalse(cooldown.isCoolingDown(frame(rows = listOf(button, button.copy(top = 450))), 400L))
    }

    @Test
    fun `success departure or mode cleanup releases the failed-content baseline`() {
        cooldown.recordEmpty(frame(), 100L)
        cooldown.reset()
        assertFalse(cooldown.isCoolingDown(frame(), 200L))
        assertEquals(600L, cooldown.recordEmpty(frame(), 200L))
    }

    private fun frame(bits: Long = 1L, rows: List<FgoReferenceRect> = listOf(button)) =
        StoryTapHandoff.ChoiceFrame(1920, 1080,
            rows.map { StoryTapHandoff.ChoiceMask(it, if (bits == 0L) 0 else 1, longArrayOf(bits)) })
}
