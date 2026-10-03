package com.fgogotran.story

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DialogueTapVisualGateTest {
    private val gate = StoryOcrVisualGate()
    private val handoff = StoryTapHandoff(2_500L)

    @Test
    fun `incomplete frames during handoff preserve the old rendered baseline`() {
        renderBaselineAndTap()
        observeIncompleteFrame(200L)
        observeIncompleteFrame(600L)
        assertEquals(StoryOcrVisualAction.SKIP_UNCHANGED, observe(20, 1_000L).action)
        assertEquals(StoryOcrVisualAction.RECOGNIZE, observe(60, 1_100L).action)
    }

    @Test
    fun `periodic unchanged verification is not disabled by the tap hint`() {
        renderBaselineAndTap()
        assertEquals(StoryOcrVisualAction.SKIP_UNCHANGED, observe(20, 200L).action)
        val verification = observe(20, 1_400L)
        assertEquals(StoryOcrVisualAction.RECOGNIZE, verification.action)
        assertEquals("periodic unchanged-scene verification", verification.reason)
    }

    @Test
    fun `expiry returns incomplete-frame cleanup to ordinary behaviour`() {
        renderBaselineAndTap()
        observeIncompleteFrame(2_600L)
        val next = observe(20, 2_700L)
        assertEquals(StoryOcrVisualAction.RECOGNIZE, next.action)
        assertEquals("no rendered visual baseline", next.reason)
    }

    @Test
    fun `failed recognition of the next frame continues retrying`() {
        renderBaselineAndTap()
        val next = observe(60, 200L)
        gate.completeRecognition(next.recognitionToken, accepted = false)
        assertEquals(StoryOcrVisualAction.RECOGNIZE, observe(60, 600L).action)
    }

    private fun renderBaselineAndTap() {
        val baseline = observe(20, 0L)
        gate.completeRecognition(baseline.recognitionToken, accepted = true)
        handoff.onSceneRendered("old dialogue", 1920, 1080, kind = StoryTapHandoff.Kind.DIALOGUE)
        handoff.touchDown(500f, 400f, 12f)
        val scene = assertNotNull(handoff.takeTap(500f, 400f))
        assertTrue(handoff.beginAfterReplay(scene, 100L))
    }

    private fun observeIncompleteFrame(now: Long) {
        // Same condition as the service's semi-auto incomplete-dialogue branch. No OCR occurs.
        if (!handoff.keepDialogueBaseline(now)) gate.reset()
    }

    private fun observe(left: Int, now: Long) = gate.observe(
        StoryOcrVisualScope.SEMI_AUTO, 120, 100,
        StoryOcrVisualBounds(0, 0, 30, 30), StoryOcrVisualBounds(0, 40, 120, 100),
        pixel = { x, y ->
            if ((x in 5..16 && y in 5..16) || (x in left until left + 12 && y in 55..72)) -1
            else 0xff142943.toInt()
        },
        now = now
    )
}
