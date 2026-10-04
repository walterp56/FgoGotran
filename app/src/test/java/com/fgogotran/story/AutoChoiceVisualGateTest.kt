package com.fgogotran.story

import com.fgogotran.overlay.FgoReferenceRect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AutoChoiceVisualGateTest {
    private val handoff = StoryTapHandoff(2_500L)
    private val buttons = listOf(FgoReferenceRect(220, 250, 1690, 385), FgoReferenceRect(220, 437, 1690, 572))

    @Test
    fun `initial choices recognize immediately without mandatory settling`() {
        assertFalse(skip(frame(), 100L))
        assertFalse(skip(frame(), 600L)) // No successful display, so a failed result remains retryable.
    }

    @Test
    fun `successful AUTO display skips repeated masks before OCR and API`() {
        var ocrCalls = 0
        var apiCalls = 0
        for (now in listOf(100L, 400L, 900L, 1_400L)) {
            if (!skip(frame(), now)) {
                ocrCalls++
                apiCalls++
                render()
            }
        }
        assertEquals(1, ocrCalls)
        assertEquals(1, apiCalls)
    }

    @Test
    fun `periodic verification bounds unchanged skips without an additional timer`() {
        render()
        assertTrue(skip(frame(), 100L))
        assertTrue(skip(frame(), 1_299L))
        assertFalse(skip(frame(), 1_300L))
        // Verification failed: keep retrying rather than suppressing the failure for another interval.
        assertFalse(skip(frame(), 1_800L))
        handoff.acceptAutoChoiceObservation(frame()) // Complete OCR matches the already displayed source.
        assertTrue(skip(frame(), 2_000L))
        assertFalse(skip(frame(), 3_200L))
    }

    @Test
    fun `changed masks remain retryable until a render succeeds`() {
        render()
        repeat(4) { assertFalse(skip(frame(offset = 30), 100L + it * 500L)) }
        render(offset = 30)
        assertTrue(skip(frame(offset = 30), 2_500L))
    }

    @Test
    fun `equivalent OCR source can refresh masks without a second API call or render`() {
        render()
        val changed = frame(offset = 1)
        assertFalse(skip(changed, 100L))
        handoff.acceptAutoChoiceObservation(changed)
        assertTrue(skip(changed, 500L))
        assertEquals(1L, handoff.autoRenderGeneration)
    }

    @Test
    fun `one absent frame is animation but two absent frames permit repeated options`() {
        render()
        handoff.onChoicesAbsent()
        assertFalse(handoff.hasDepartedRenderedAutoChoices())
        assertTrue(skip(frame(), 100L))
        handoff.onChoicesAbsent()
        handoff.onChoicesAbsent()
        assertTrue(handoff.hasDepartedRenderedAutoChoices())
        assertFalse(skip(frame(), 600L))
        handoff.acceptAutoChoiceObservation(frame()) // Cannot acknowledge away a real transition.
        assertTrue(handoff.hasDepartedRenderedAutoChoices())
        render()
        assertEquals(2L, handoff.autoRenderGeneration)
        assertFalse(handoff.hasDepartedRenderedAutoChoices())
        assertTrue(skip(frame(), 1_000L))
    }

    @Test
    fun `mixed dialogue and choice scene must not skip because choice masks match`() {
        render()
        repeat(3) {
            assertFalse(handoff.shouldSkipRenderedChoices(frame(), 100L + it * 500L, dialogueMayBePresent = true))
        }
    }

    @Test
    fun `semi-auto choice display never enables the AUTO pre-OCR skip`() {
        render(auto = false)
        assertFalse(skip(frame(), 100L))
        handoff.acceptAutoChoiceObservation(frame())
        assertFalse(skip(frame(), 600L))
        assertEquals(0L, handoff.autoRenderGeneration)
    }

    @Test
    fun `tap handoff continues using old-list and new-list settling rules`() {
        render()
        handoff.touchDown(500f, 300f, 12f)
        val scene = assertNotNull(handoff.takeTap(500f, 300f))
        assertTrue(handoff.beginAfterReplay(scene, 100L))
        assertFalse(skip(frame(), 400L)) // The general gate does not replace pending handoff policy.
        assertEquals(StoryTapHandoff.ChoiceAction.WAIT_OLD, handoff.observeChoices(frame(), 400L))
        assertEquals(StoryTapHandoff.ChoiceAction.WAIT_SETTLE, handoff.observeChoices(frame(30), 900L))
        assertEquals(StoryTapHandoff.ChoiceAction.RECOGNIZE, handoff.observeChoices(frame(30), 1_400L))
    }

    @Test
    fun `blank masks never skip OCR or become an accepted baseline`() {
        render()
        val blank = StoryTapHandoff.sampleChoices(1920, 1080, buttons) { _, _ -> BLACK }
        assertFalse(skip(blank, 100L))
        handoff.acceptAutoChoiceObservation(blank)
        assertFalse(skip(blank, 600L))
        assertTrue(skip(frame(), 1_000L))
    }

    @Test
    fun `new geometry cannot replace the displayed geometry through a duplicate source`() {
        render()
        val moved = frame(rows = buttons.map { it.copy(top = it.top + 10, bottom = it.bottom + 10) })
        assertFalse(skip(moved, 100L))
        handoff.acceptAutoChoiceObservation(moved)
        assertFalse(skip(moved, 600L))
        val resized = StoryTapHandoff.sampleChoices(2340, 1080, buttons) { x, y -> ink(x, y, 0) }
        assertFalse(skip(resized, 1_000L))
    }

    @Test
    fun `clear or dialogue display removes the choice baseline`() {
        render()
        handoff.clear()
        assertFalse(skip(frame(), 100L))
        render()
        handoff.onSceneRendered("dialogue", 1920, 1080, StoryTapHandoff.Kind.DIALOGUE, autoRenderCommitted = true)
        assertFalse(skip(frame(), 600L))
    }

    @Test
    fun `one through six choices preserve ruby and leading internal trailing punctuation changes`() {
        for (count in 1..6) {
            handoff.clear()
            val rows = (0 until count).map { FgoReferenceRect(220, 14 + it * 144, 1690, 149 + it * 144) }
            val original = frame(rows = rows)
            handoff.onSceneRendered("choices", 1920, 1080, StoryTapHandoff.Kind.CHOICES,
                rows, original, autoRenderCommitted = true)
            assertTrue(skip(original, 100L))
            for ((x, y) in listOf(500 to rows[0].top + 61, 650 to rows[0].top + 61,
                    800 to rows[0].top + 61, 600 to rows[0].top + 18)) {
                val changed = StoryTapHandoff.sampleChoices(1920, 1080, rows) { px, py ->
                    if (px in x..x + 8 && py == y) WHITE else ink(px, py, 0, rows)
                }
                assertFalse(skip(changed, 500L), "count=$count, mark=($x,$y)")
            }
        }
    }

    @Test
    fun `a change between sampled columns receives bounded recognition recovery`() {
        render()
        // At this resolution the first sampled x is 271, with step 3: 500 lies between samples.
        val missedDot = StoryTapHandoff.sampleChoices(1920, 1080, buttons) { x, y ->
            if (x == 500 && y == 311) WHITE else ink(x, y, 0)
        }
        assertTrue(frame().sameText(missedDot))
        assertTrue(skip(missedDot, 100L))
        assertFalse(skip(missedDot, 1_300L))
    }

    private fun render(offset: Int = 0, auto: Boolean = true) = handoff.onSceneRendered(
        "choices", 1920, 1080, StoryTapHandoff.Kind.CHOICES, buttons, frame(offset), autoRenderCommitted = auto
    )

    private fun skip(observation: StoryTapHandoff.ChoiceFrame, now: Long) =
        handoff.shouldSkipRenderedChoices(observation, now, dialogueMayBePresent = false)

    private fun frame(offset: Int = 0, rows: List<FgoReferenceRect> = buttons) =
        StoryTapHandoff.sampleChoices(1920, 1080, rows) { x, y -> ink(x, y, offset, rows) }

    private fun ink(x: Int, y: Int, offset: Int, rows: List<FgoReferenceRect> = buttons): Int =
        if (x in 580 + offset..740 + offset && x !in 644 + offset..656 + offset &&
            rows.any { y in it.top + 50..it.top + 85 }) WHITE else BLACK

    companion object {
        private val BLACK = 0xff000000.toInt()
        private const val WHITE = -1
    }
}
