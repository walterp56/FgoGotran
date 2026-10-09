package com.fgogotran.ui.overlay

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FloatingButtonDockingTest {
    private val viewport = FloatingControlBounds(24, 12, 1000, 600)

    private fun released(
        x: Int,
        cancelled: Boolean = false,
        startedDocked: Boolean = false
    ) = FloatingButtonDocking.sideAfterRelease(cancelled, startedDocked, x, 54, viewport, 12)

    @Test fun `both edges dock without a mode restriction`() {
        assertEquals(FloatingDockSide.LEFT, released(24))
        assertEquals(FloatingDockSide.RIGHT, released(946))
    }

    @Test fun `edge allowance includes exact boundary on both sides`() {
        assertEquals(FloatingDockSide.LEFT, released(36))
        assertNull(released(37))
        assertEquals(FloatingDockSide.RIGHT, released(934))
        assertNull(released(933))
    }

    @Test fun `middle of screen does not dock`() {
        assertNull(released(400))
    }

    @Test fun `cancelled drag never docks`() {
        assertNull(released(24, cancelled = true))
        assertNull(released(946, cancelled = true))
    }

    @Test fun `reveal release keeps button visible even at the edge`() {
        assertNull(released(24, startedDocked = true))
        assertNull(released(946, startedDocked = true))
    }

    @Test fun `left handle accepts inward motion only`() {
        assertTrue(FloatingButtonDocking.canStartDrag(FloatingDockSide.LEFT, 19f, 0f, 18f))
        assertFalse(FloatingButtonDocking.canStartDrag(FloatingDockSide.LEFT, 18f, 0f, 18f))
        assertFalse(FloatingButtonDocking.canStartDrag(FloatingDockSide.LEFT, -30f, 0f, 18f))
        assertFalse(FloatingButtonDocking.canStartDrag(FloatingDockSide.LEFT, 0f, 100f, 18f))
    }

    @Test fun `right handle accepts inward motion only`() {
        assertTrue(FloatingButtonDocking.canStartDrag(FloatingDockSide.RIGHT, -19f, 0f, 18f))
        assertFalse(FloatingButtonDocking.canStartDrag(FloatingDockSide.RIGHT, 30f, 0f, 18f))
        assertFalse(FloatingButtonDocking.canStartDrag(FloatingDockSide.RIGHT, 0f, 100f, 18f))
    }

    @Test fun `normal drag keeps the existing radial slop`() {
        assertTrue(FloatingButtonDocking.canStartDrag(null, 13f, 13f, 18f))
        assertFalse(FloatingButtonDocking.canStartDrag(null, 10f, 10f, 18f))
    }

    @Test fun `handle uses usable left and right edges and button vertical centre`() {
        assertEquals(
            FloatingControlBounds(24, 203, 40, 251),
            FloatingButtonDocking.handleBounds(FloatingDockSide.LEFT, 200, 54, 16, 48, viewport)
        )
        assertEquals(
            FloatingControlBounds(984, 203, 1000, 251),
            FloatingButtonDocking.handleBounds(FloatingDockSide.RIGHT, 200, 54, 16, 48, viewport)
        )
    }

    @Test fun `handle is clamped away from top and bottom insets`() {
        assertEquals(12, FloatingButtonDocking.handleBounds(FloatingDockSide.LEFT, -30, 54, 16, 48, viewport).top)
        assertEquals(600, FloatingButtonDocking.handleBounds(FloatingDockSide.RIGHT, 600, 54, 16, 48, viewport).bottom)
    }

    @Test fun `reveal centres the full button on the pointer`() {
        assertEquals(
            FloatingControlBounds(373, 273, 427, 327),
            FloatingButtonDocking.revealedButtonBounds(400f, 300f, 54, viewport)
        )
    }

    @Test fun `reveal clamps the full button not the narrow handle`() {
        assertEquals(
            FloatingControlBounds(24, 12, 78, 66),
            FloatingButtonDocking.revealedButtonBounds(25f, 12f, 54, viewport)
        )
        assertEquals(
            FloatingControlBounds(946, 546, 1000, 600),
            FloatingButtonDocking.revealedButtonBounds(999f, 599f, 54, viewport)
        )
    }

    @Test fun `handle dimensions also work in a smaller viewport`() {
        assertEquals(
            FloatingControlBounds(0, 0, 10, 20),
            FloatingButtonDocking.handleBounds(FloatingDockSide.RIGHT, 0, 54, 16, 48, FloatingControlBounds(0, 0, 10, 20))
        )
    }

    @Test fun `button size and pixel scaled allowance are respected`() {
        assertEquals(
            FloatingDockSide.RIGHT,
            FloatingButtonDocking.sideAfterRelease(
                false, false, 820, 144, viewport, 36
            )
        )
    }

    @Test fun `full button clamps to usable bounds on both edges`() {
        assertEquals(
            FloatingControlBounds(24, 12, 78, 66),
            FloatingButtonDocking.buttonBounds(-100, -100, 54, viewport)
        )
        assertEquals(
            FloatingControlBounds(946, 546, 1000, 600),
            FloatingButtonDocking.buttonBounds(2000, 2000, 54, viewport)
        )
    }

    @Test fun `a revealed button can dock again on a later separate drag`() {
        val revealed = FloatingButtonDocking.revealedButtonBounds(25f, 220f, 54, viewport)
        assertNull(released(revealed.left, startedDocked = true))
        assertEquals(FloatingDockSide.LEFT, released(revealed.left))
    }
}
