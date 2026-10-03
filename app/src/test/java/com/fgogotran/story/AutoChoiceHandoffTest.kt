package com.fgogotran.story

import com.fgogotran.overlay.FgoReferenceRect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AutoChoiceHandoffTest {
    private val handoff = StoryTapHandoff(2_500L)
    private val buttons = listOf(
        FgoReferenceRect(220, 250, 1690, 385),
        FgoReferenceRect(220, 437, 1690, 572)
    )

    @Test
    fun `AUTO commits count renders rather than distinct source text`() {
        render(StoryTapHandoff.Kind.CHOICES)
        val old = tap()
        assertEquals(1L, handoff.autoRenderGeneration)
        assertTrue(handoff.beginAfterReplay(old, 100L))
        render(StoryTapHandoff.Kind.CHOICES) // Identical translated options in a later list.
        assertEquals(2L, handoff.autoRenderGeneration)
        assertFalse(handoff.ownsScene(old))
        assertFalse(handoff.isPending(200L))
        assertEquals(old.fingerprint, tap().fingerprint)
    }

    @Test
    fun `all four AUTO scene transitions reuse the same owner`() {
        for (oldKind in StoryTapHandoff.Kind.entries) {
            for (nextKind in StoryTapHandoff.Kind.entries) {
                handoff.clear()
                render(oldKind)
                val old = tap()
                val generation = handoff.autoRenderGeneration
                assertTrue(handoff.beginAfterReplay(old, 100L))
                if (nextKind == StoryTapHandoff.Kind.CHOICES) {
                    assertEquals(StoryTapHandoff.ChoiceAction.WAIT_SETTLE,
                        handoff.observeChoices(frame(bits = 2L), 400L))
                    assertEquals(StoryTapHandoff.ChoiceAction.RECOGNIZE,
                        handoff.observeChoices(frame(bits = 2L), 900L))
                } else {
                    handoff.onChoicesAbsent()
                    assertEquals(oldKind == StoryTapHandoff.Kind.DIALOGUE,
                        handoff.keepDialogueBaseline(400L))
                }
                // Recognition alone must not terminate polling; only the overlay commit does.
                assertEquals(generation, handoff.autoRenderGeneration)
                assertTrue(handoff.ownsScene(old))
                render(nextKind, bits = 2L)
                assertEquals(generation + 1L, handoff.autoRenderGeneration)
                assertFalse(handoff.ownsScene(old))
                assertFalse(handoff.isPending(1_000L))
                assertEquals(nextKind, tap().kind)
            }
        }
    }

    @Test
    fun `new options in the same panels settle without an empty capture`() {
        render(StoryTapHandoff.Kind.CHOICES)
        assertTrue(handoff.beginAfterReplay(tap(), 100L))
        assertEquals(StoryTapHandoff.ChoiceAction.WAIT_OLD, handoff.observeChoices(frame(), 400L))
        assertEquals(StoryTapHandoff.ChoiceAction.WAIT_SETTLE,
            handoff.observeChoices(frame(bits = 2L), 900L))
        assertEquals(StoryTapHandoff.ChoiceAction.WAIT_SETTLE,
            handoff.observeChoices(frame(bits = 4L), 1_400L))
        assertEquals(StoryTapHandoff.ChoiceAction.RECOGNIZE,
            handoff.observeChoices(frame(bits = 4L), 1_900L))
    }

    @Test
    fun `disappearing old subsets never trigger OCR or a render commit`() {
        render(StoryTapHandoff.Kind.CHOICES)
        assertTrue(handoff.beginAfterReplay(tap(), 100L))
        buttons.forEachIndexed { index, button ->
            assertEquals(StoryTapHandoff.ChoiceAction.WAIT_OLD,
                handoff.observeChoices(frame(rows = listOf(button)), 400L + index * 500L))
        }
        assertEquals(1L, handoff.autoRenderGeneration)
        assertTrue(handoff.isPending(1_000L))
    }

    @Test
    fun `repeated options require confirmed departure and a stable return`() {
        render(StoryTapHandoff.Kind.CHOICES)
        assertTrue(handoff.beginAfterReplay(tap(), 100L))
        handoff.onChoicesAbsent()
        assertEquals(StoryTapHandoff.ChoiceAction.WAIT_OLD, handoff.observeChoices(frame(), 900L))
        handoff.onChoicesAbsent()
        handoff.onChoicesAbsent()
        assertEquals(StoryTapHandoff.ChoiceAction.WAIT_SETTLE, handoff.observeChoices(frame(), 1_900L))
        assertEquals(StoryTapHandoff.ChoiceAction.RECOGNIZE, handoff.observeChoices(frame(), 2_400L))
        render(StoryTapHandoff.Kind.CHOICES)
        assertEquals(2L, handoff.autoRenderGeneration)
    }

    @Test
    fun `selection hit area is the full detected panel not the translated glyph box`() {
        render(StoryTapHandoff.Kind.CHOICES)
        // Well outside the centred glyphs, but inside the original selectable button.
        assertNotNull(tapOrNull(230f, 300f))
        assertNotNull(tapOrNull(1680f, 300f))
        assertNull(tapOrNull(219f, 300f))
        assertNull(tapOrNull(1690f, 300f))
        assertNull(tapOrNull(500f, 410f))
        assertFalse(handoff.isPending(100L))
    }

    @Test
    fun `failed replay leaves AUTO ticket retryable and does not fake departure`() {
        render(StoryTapHandoff.Kind.CHOICES)
        val failed = tap() // Service never calls beginAfterReplay for a failed dispatch.
        assertTrue(handoff.ownsScene(failed))
        assertFalse(handoff.isPending(100L))
        assertEquals(1L, handoff.autoRenderGeneration)
        assertTrue(handoff.beginAfterReplay(tap(), 400L))
        assertEquals(StoryTapHandoff.ChoiceAction.WAIT_OLD, handoff.observeChoices(frame(), 900L))
    }

    @Test
    fun `failed OCR or render remains retryable and is not a completed AUTO poll`() {
        render(StoryTapHandoff.Kind.CHOICES)
        val old = tap()
        assertTrue(handoff.beginAfterReplay(old, 100L))
        assertEquals(StoryTapHandoff.ChoiceAction.WAIT_SETTLE,
            handoff.observeChoices(frame(bits = 2L), 400L))
        repeat(3) { index ->
            assertEquals(StoryTapHandoff.ChoiceAction.RECOGNIZE,
                handoff.observeChoices(frame(bits = 2L), 900L + index * 500L))
            assertEquals(1L, handoff.autoRenderGeneration)
            assertTrue(handoff.ownsScene(old))
        }
    }

    @Test
    fun `new render invalidates suspended replay even when its source is identical`() {
        render(StoryTapHandoff.Kind.CHOICES)
        val suspended = tap()
        assertTrue(handoff.ownsScene(suspended))
        render(StoryTapHandoff.Kind.CHOICES)
        assertFalse(handoff.ownsScene(suspended))
        assertFalse(handoff.beginAfterReplay(suspended, 100L))
        assertFalse(handoff.isPending(100L))
    }

    @Test
    fun `mode reset foreground loss and cancellation invalidate both replay stages`() {
        for (replayed in listOf(false, true)) {
            render(StoryTapHandoff.Kind.CHOICES)
            val suspended = tap()
            if (replayed) assertTrue(handoff.beginAfterReplay(suspended, 100L))
            val generation = handoff.autoRenderGeneration
            handoff.clear() // Shared service cleanup for mode/foreground/cancellation.
            assertFalse(handoff.ownsScene(suspended))
            assertFalse(handoff.beginAfterReplay(suspended, 400L))
            assertFalse(handoff.isPending(400L))
            assertNull(tapOrNull())
            assertEquals(generation, handoff.autoRenderGeneration)
        }
    }

    @Test
    fun `semi-auto or manual display does not count as an AUTO commit`() {
        render(StoryTapHandoff.Kind.CHOICES)
        val old = tap()
        handoff.onSceneRendered("semi", 1920, 1080, StoryTapHandoff.Kind.DIALOGUE)
        assertEquals(1L, handoff.autoRenderGeneration)
        assertFalse(handoff.ownsScene(old))
        handoff.onSceneRendered("manual", 1920, 1080, kind = null)
        assertEquals(1L, handoff.autoRenderGeneration)
        assertNull(tapOrNull())
    }

    @Test
    fun `expiry ends ownership without pretending to render another scene`() {
        render(StoryTapHandoff.Kind.CHOICES)
        val old = tap()
        assertTrue(handoff.beginAfterReplay(old, 100L))
        assertFalse(handoff.isPending(30_100L))
        assertFalse(handoff.ownsScene(old))
        assertEquals(1L, handoff.autoRenderGeneration)
    }

    private fun render(kind: StoryTapHandoff.Kind, bits: Long = 1L) = handoff.onSceneRendered(
        fingerprint = "same source", width = 1920, height = 1080, kind = kind,
        choiceHitBounds = if (kind == StoryTapHandoff.Kind.CHOICES) buttons else emptyList(),
        choices = if (kind == StoryTapHandoff.Kind.CHOICES) frame(bits) else null,
        autoRenderCommitted = true
    )

    private fun frame(bits: Long = 1L, rows: List<FgoReferenceRect> = buttons) =
        StoryTapHandoff.ChoiceFrame(1920, 1080,
            rows.map { StoryTapHandoff.ChoiceMask(it, 1, longArrayOf(bits)) })

    private fun tapOrNull(x: Float = 500f, y: Float = 300f): StoryTapHandoff.RenderedScene? {
        handoff.touchDown(x, y, 12f)
        return handoff.takeTap(x, y)
    }

    private fun tap(): StoryTapHandoff.RenderedScene = assertNotNull(tapOrNull())
}
