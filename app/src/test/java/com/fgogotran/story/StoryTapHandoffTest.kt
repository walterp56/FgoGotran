package com.fgogotran.story

import com.fgogotran.overlay.FgoReferenceRect
import com.fgogotran.overlay.FgoViewportGeometry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StoryTapHandoffTest {
    private val handoff = StoryTapHandoff(2_500L)

    @Test
    fun `successful dialogue replay starts a bounded one-shot hint`() {
        render()
        val scene = tap()
        assertEquals("previous dialogue", scene.fingerprint)
        assertFalse(handoff.isPending(100L)) // Touch alone is not successful replay.
        assertTrue(handoff.beginAfterReplay(scene, 100L))
        assertTrue(handoff.keepDialogueBaseline(2_599L))
        assertFalse(handoff.keepDialogueBaseline(2_600L))
        assertTrue(handoff.isPending(3_200L)) // Game transition may outlast the visual hint.
        assertTrue(handoff.isPending(30_099L))
        assertFalse(handoff.isPending(30_100L))
        assertFalse(handoff.beginAfterReplay(scene, 30_101L))
    }

    @Test
    fun `failed replay leaves the displayed scene available without a hint`() {
        render()
        tap() // A failed dispatch never calls beginAfterReplay.
        assertFalse(handoff.isPending(100L))
        assertNotNull(tap())
    }

    @Test
    fun `only a rendered eligible dialogue may supply a ticket`() {
        assertNull(tapOrNull())
        handoff.onSceneRendered("manual or auto", 1920, 1080, kind = null)
        assertNull(tapOrNull())
        handoff.onSceneRendered("", 1920, 1080, kind = StoryTapHandoff.Kind.DIALOGUE)
        assertNull(tapOrNull())
        handoff.onSceneRendered("dialogue", 0, 1080, kind = StoryTapHandoff.Kind.DIALOGUE)
        assertNull(tapOrNull())
    }

    @Test
    fun `duplicate replay cannot renew the pending deadline`() {
        render()
        val scene = tap()
        assertTrue(handoff.beginAfterReplay(scene, 100L))
        assertFalse(handoff.beginAfterReplay(scene, 500L))
        assertNull(tapOrNull())
        assertFalse(handoff.isPending(30_100L))
    }

    @Test
    fun `new render invalidates a suspended replay ticket`() {
        render()
        val old = tap()
        render("new dialogue")
        assertFalse(handoff.beginAfterReplay(old, 100L))
        assertFalse(handoff.isPending(100L))
        assertEquals("new dialogue", tap().fingerprint)
    }

    @Test
    fun `accepted repeated dialogue also clears the hint`() {
        render()
        assertTrue(handoff.beginAfterReplay(tap(), 100L))
        render() // No different-text requirement.
        assertFalse(handoff.isPending(101L))
        assertNotNull(tap())
    }

    @Test
    fun `mode cancellation or foreground loss clears tickets touches and pending hint`() {
        render()
        val old = tap()
        handoff.clear()
        assertFalse(handoff.beginAfterReplay(old, 100L))
        assertNull(tapOrNull())
        render()
        assertTrue(handoff.beginAfterReplay(tap(), 200L))
        handoff.clear()
        assertFalse(handoff.isPending(201L))
    }

    @Test
    fun `unchanged accepted recognition can finish the hint without another render`() {
        render()
        assertTrue(handoff.beginAfterReplay(tap(), 100L))
        handoff.finishPending()
        assertFalse(handoff.isPending(101L))
    }

    @Test
    fun `drag remains ineligible even when it returns to its starting point`() {
        render()
        handoff.touchDown(500f, 400f, 12f)
        handoff.touchMove(530f, 400f)
        handoff.touchMove(500f, 400f)
        assertNull(handoff.takeTap(500f, 400f))
        assertNotNull(tap())
    }

    @Test
    fun `finger jitter is allowed but release beyond slop is not`() {
        render()
        handoff.touchDown(500f, 400f, 12f)
        assertNotNull(handoff.takeTap(506f, 406f))
        handoff.touchDown(500f, 400f, 12f)
        assertNull(handoff.takeTap(520f, 400f))
    }

    @Test
    fun `consumed floating-menu touch cancelled touch and multitouch cannot advance`() {
        render()
        handoff.touchDown(500f, 400f, 12f)
        handoff.cancelTouch() // Service calls this for consumed, cancelled or multi-pointer touches.
        assertNull(handoff.takeTap(500f, 400f))
        assertNull(handoff.takeTap(500f, 400f)) // ACTION_UP without a fresh DOWN.
    }

    @Test
    fun `SKIP LOG and AUTO are excluded but the advance diamond is allowed`() {
        render()
        assertNull(tapOrNull(1750f, 70f))
        assertNull(tapOrNull(1840f, 825f))
        assertNull(tapOrNull(1840f, 915f))
        assertNotNull(tapOrNull(1840f, 1000f))
        assertNotNull(tapOrNull(1840f, 500f))
        assertNotNull(tapOrNull(1770f, 915f))
    }

    @Test
    fun `control exclusions follow centered viewport at different screen sizes`() {
        listOf(2340 to 1080, 1280 to 720, 1080 to 2340).forEach { (width, height) ->
            render(width = width, height = height)
            fun point(x: Int, y: Int): Pair<Float, Float> {
                val bounds = FgoViewportGeometry.map(FgoReferenceRect(x, y, x + 2, y + 2), width, height)
                return bounds.left.toFloat() to bounds.top.toFloat()
            }
            listOf(1750 to 70, 1840 to 825, 1840 to 915).forEach { (x, y) ->
                val (screenX, screenY) = point(x, y)
                assertNull(tapOrNull(screenX, screenY), "$width x $height at $x,$y")
            }
            val (screenX, screenY) = point(1840, 1000)
            assertNotNull(tapOrNull(screenX, screenY), "$width x $height advance diamond")
        }
    }

    @Test
    fun `invalid coordinates cannot create an advance ticket`() {
        render()
        listOf(-1f to 400f, 1920f to 400f, 500f to -1f, 500f to 1080f,
            Float.NaN to 400f, Float.POSITIVE_INFINITY to 400f).forEach { (x, y) ->
            assertNull(tapOrNull(x, y))
        }
    }

    private fun render(fingerprint: String = "previous dialogue", width: Int = 1920, height: Int = 1080) {
        handoff.onSceneRendered(fingerprint, width, height, kind = StoryTapHandoff.Kind.DIALOGUE)
    }

    private fun tapOrNull(x: Float = 500f, y: Float = 400f): StoryTapHandoff.RenderedScene? {
        handoff.touchDown(x, y, 12f)
        return handoff.takeTap(x, y)
    }

    private fun tap(): StoryTapHandoff.RenderedScene = assertNotNull(tapOrNull())
}
