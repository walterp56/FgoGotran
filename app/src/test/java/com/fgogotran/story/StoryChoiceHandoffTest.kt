package com.fgogotran.story

import com.fgogotran.overlay.FgoReferenceRect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StoryChoiceHandoffTest {
    private val handoff = StoryTapHandoff(2_500L)
    private val buttons = listOf(FgoReferenceRect(220, 250, 1690, 385), FgoReferenceRect(220, 437, 1690, 572))

    @Test
    fun `dialogue to dialogue retains the completion gate and accepts a new render`() {
        handoff.onSceneRendered("old", 1920, 1080, StoryTapHandoff.Kind.DIALOGUE)
        begin()
        handoff.onChoicesAbsent()
        assertTrue(handoff.keepDialogueBaseline(400L))
        handoff.onSceneRendered("next", 1920, 1080, StoryTapHandoff.Kind.DIALOGUE)
        assertFalse(handoff.isPending(600L))
        assertEquals("next", tap().fingerprint)
    }

    @Test
    fun `dialogue to choices waits for stable text without a dialogue marker`() {
        handoff.onSceneRendered("dialogue", 1920, 1080, StoryTapHandoff.Kind.DIALOGUE)
        begin()
        val choices = frame()
        assertEquals(StoryTapHandoff.ChoiceAction.WAIT_SETTLE, handoff.observeChoices(choices, 400L))
        assertEquals(StoryTapHandoff.ChoiceAction.RECOGNIZE, handoff.observeChoices(frame(), 900L))
        renderChoices()
        assertFalse(handoff.isPending(1_000L))
        assertEquals(StoryTapHandoff.Kind.CHOICES, tap().kind)
    }

    @Test
    fun `choice to dialogue keeps scanning after the old list leaves`() {
        renderChoices()
        begin()
        handoff.onChoicesAbsent()
        handoff.onChoicesAbsent()
        assertFalse(handoff.keepDialogueBaseline(400L))
        assertTrue(handoff.isPending(3_500L))
        handoff.onSceneRendered("next dialogue", 1920, 1080, StoryTapHandoff.Kind.DIALOGUE)
        assertFalse(handoff.isPending(3_501L))
        assertEquals("next dialogue", tap().fingerprint)
    }

    @Test
    fun `choice to new choices with identical geometry does not require a panel-free gap`() {
        renderChoices()
        begin()
        assertEquals(StoryTapHandoff.ChoiceAction.WAIT_OLD, handoff.observeChoices(frame(), 400L))
        val changed = frame(offset = 30)
        assertEquals(StoryTapHandoff.ChoiceAction.WAIT_SETTLE, handoff.observeChoices(changed, 900L))
        assertEquals(StoryTapHandoff.ChoiceAction.RECOGNIZE, handoff.observeChoices(changed, 1_400L))
        renderChoices(offset = 30)
        assertFalse(handoff.isPending(1_500L))
        assertTrue(handoff.beginAfterReplay(tap(), 1_600L))
    }

    @Test
    fun `old choices remain skipped after the short visual hint expires`() {
        renderChoices()
        begin()
        assertFalse(handoff.keepDialogueBaseline(3_100L))
        assertTrue(handoff.isPending(3_100L))
        assertEquals(StoryTapHandoff.ChoiceAction.WAIT_OLD, handoff.observeChoices(frame(), 3_100L))
        assertEquals(StoryTapHandoff.ChoiceAction.WAIT_OLD, handoff.observeChoices(frame(), 6_000L))
    }

    @Test
    fun `old buttons disappearing one at a time are not a new smaller list`() {
        renderChoices()
        begin()
        assertEquals(StoryTapHandoff.ChoiceAction.WAIT_OLD,
            handoff.observeChoices(frame(rows = buttons.take(1)), 400L))
        assertEquals(StoryTapHandoff.ChoiceAction.WAIT_OLD,
            handoff.observeChoices(frame(rows = buttons.takeLast(1)), 900L))
        val newRow = frame(offset = 30, rows = buttons.take(1))
        assertEquals(StoryTapHandoff.ChoiceAction.WAIT_SETTLE, handoff.observeChoices(newRow, 1_400L))
        assertEquals(StoryTapHandoff.ChoiceAction.RECOGNIZE, handoff.observeChoices(newRow, 1_900L))
    }

    @Test
    fun `a small punctuation change is not hidden by a global difference tolerance`() {
        val changed = StoryTapHandoff.sampleChoices(1920, 1080, buttons) { x, y ->
            if (ink(x, y, 0, buttons) || (x in 790..798 && y == buttons.first().top + 61)) WHITE else BLACK
        }
        assertFalse(frame().sameText(changed))
    }

    @Test
    fun `a single absent animation frame cannot cause the old list to be reread`() {
        renderChoices()
        begin()
        handoff.onChoicesAbsent()
        assertEquals(StoryTapHandoff.ChoiceAction.WAIT_OLD, handoff.observeChoices(frame(), 900L))
    }

    @Test
    fun `identical options may be read again after confirmed departure`() {
        renderChoices()
        begin()
        handoff.onChoicesAbsent()
        handoff.onChoicesAbsent()
        assertEquals(StoryTapHandoff.ChoiceAction.WAIT_SETTLE, handoff.observeChoices(frame(), 1_400L))
        assertEquals(StoryTapHandoff.ChoiceAction.RECOGNIZE, handoff.observeChoices(frame(), 1_900L))
    }

    @Test
    fun `changing or incomplete choice frames never count as stable`() {
        handoff.onSceneRendered("dialogue", 1920, 1080, StoryTapHandoff.Kind.DIALOGUE)
        begin()
        assertEquals(StoryTapHandoff.ChoiceAction.WAIT_SETTLE, handoff.observeChoices(frame(), 400L))
        assertEquals(StoryTapHandoff.ChoiceAction.WAIT_SETTLE, handoff.observeChoices(frame(30), 900L))
        val blank = StoryTapHandoff.sampleChoices(1920, 1080, buttons) { _, _ -> BLACK }
        repeat(2) {
            assertEquals(StoryTapHandoff.ChoiceAction.WAIT_SETTLE, handoff.observeChoices(blank, 1_400L))
        }
        assertEquals(StoryTapHandoff.ChoiceAction.WAIT_SETTLE, handoff.observeChoices(frame(30), 1_900L))
        assertEquals(StoryTapHandoff.ChoiceAction.RECOGNIZE, handoff.observeChoices(frame(30), 2_400L))
        // Failed OCR/render does not commit a baseline or end the pending transition.
        assertEquals(StoryTapHandoff.ChoiceAction.RECOGNIZE, handoff.observeChoices(frame(30), 2_900L))
    }

    @Test
    fun `outside-button taps do not start a choice handoff and failure can retry`() {
        renderChoices()
        handoff.touchDown(500f, 410f, 12f)
        assertNull(handoff.takeTap(500f, 410f))
        assertFalse(handoff.isPending(200L))
        val failedTicket = tap() // No beginAfterReplay on failure.
        assertNotNull(tap())
        handoff.clear()
        assertFalse(handoff.beginAfterReplay(failedTicket, 400L))
        assertFalse(handoff.isPending(400L))
    }

    @Test
    fun `missing choice metadata cannot supply a ticket`() {
        handoff.onSceneRendered("choices", 1920, 1080, StoryTapHandoff.Kind.CHOICES)
        handoff.touchDown(500f, 300f, 12f)
        assertNull(handoff.takeTap(500f, 300f))
    }

    @Test
    fun `one through six buttons are distinct logical rows including the sixth below dialogue top`() {
        for (count in 1..6) {
            val rows = (0 until count).map { FgoReferenceRect(220, 14 + it * 144, 1690, 149 + it * 144) }
            val sample = frame(rows = rows)
            assertTrue(sample.hasText, "count=$count")
            handoff.onSceneRendered("$count", 1920, 1080, StoryTapHandoff.Kind.CHOICES, rows, sample)
            val last = rows.last()
            handoff.touchDown(500f, last.top + 60f, 12f)
            assertNotNull(handoff.takeTap(500f, last.top + 60f), "count=$count")
            assertFalse(sample.sameText(frame(offset = 30, rows = rows)), "count=$count")
        }
    }

    @Test
    fun `signature covers new text outside the previous OCR glyph bounds`() {
        val extended = StoryTapHandoff.sampleChoices(1920, 1080, buttons) { x, y ->
            if (ink(x, y, 0, buttons) || (x in 1_200..1_280 && buttons.any { y in it.top + 50..it.top + 85 })) WHITE else BLACK
        }
        assertFalse(frame().sameText(extended))
    }

    @Test
    fun `border glow is excluded while upper ruby remains part of the signature`() {
        val glow = StoryTapHandoff.sampleChoices(1920, 1080, buttons) { x, y ->
            if (ink(x, y, 0, buttons)) WHITE
            else if (buttons.any { y in it.top..it.top + 8 || x in it.left..it.left + 10 }) WHITE
            else BLACK
        }
        assertTrue(frame().sameText(glow))
        val ruby = StoryTapHandoff.sampleChoices(1920, 1080, buttons) { x, y ->
            if (ink(x, y, 0, buttons) || (x in 600..640 && buttons.any { y in it.top + 18..it.top + 25 })) WHITE else BLACK
        }
        assertFalse(frame().sameText(ruby))
    }

    @Test
    fun `punctuation-only thin dashes and red choices remain visible to the text gate`() {
        val dash = StoryTapHandoff.sampleChoices(1920, 1080, buttons) { x, y ->
            if (x in 600..760 && buttons.any { y == it.top + 61 }) WHITE else BLACK
        }
        assertTrue(dash.hasText)
        val red = StoryTapHandoff.sampleChoices(1920, 1080, buttons) { x, y ->
            if (ink(x, y, 0, buttons)) 0xffdc0000.toInt() else BLACK
        }
        assertTrue(frame().sameText(red)) // Colour is not a new source by itself.
    }

    @Test
    fun `cancel and expiry stop choice work without disabling subsequent dialogue tickets`() {
        renderChoices()
        begin()
        assertFalse(handoff.isPending(30_100L))
        assertEquals(StoryTapHandoff.ChoiceAction.WAIT_SETTLE, handoff.observeChoices(frame(30), 30_101L))
        handoff.onSceneRendered("dialogue", 1920, 1080, StoryTapHandoff.Kind.DIALOGUE)
        assertNotNull(tap())
    }

    private fun renderChoices(offset: Int = 0) = handoff.onSceneRendered("choices $offset", 1920, 1080,
        StoryTapHandoff.Kind.CHOICES, buttons, frame(offset))

    private fun begin() = assertTrue(handoff.beginAfterReplay(tap(), 100L))

    private fun tap(): StoryTapHandoff.RenderedScene {
        handoff.touchDown(500f, 300f, 12f)
        return assertNotNull(handoff.takeTap(500f, 300f))
    }

    private fun frame(offset: Int = 0, rows: List<FgoReferenceRect> = buttons) =
        StoryTapHandoff.sampleChoices(1920, 1080, rows) { x, y -> if (ink(x, y, offset, rows)) WHITE else BLACK }

    private fun ink(x: Int, y: Int, offset: Int, rows: List<FgoReferenceRect>) =
        x in 580 + offset..740 + offset && rows.any { y in it.top + 50..it.top + 85 }

    companion object {
        private val BLACK = 0xff000000.toInt()
        private const val WHITE = -1
    }
}
