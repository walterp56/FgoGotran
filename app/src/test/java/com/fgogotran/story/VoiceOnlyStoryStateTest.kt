package com.fgogotran.story

import com.fgogotran.overlay.FgoReferenceRect
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VoiceOnlyStoryStateTest {
    private val state = VoiceOnlyStoryState<Int>()
    private val bounds = FgoReferenceRect(20, 30, 90, 60)
    private fun choices(offset: Int = 0) = StoryTapHandoff.ChoiceFrame(
        120, 100, listOf(StoryTapHandoff.ChoiceMask(bounds, 12, longArrayOf(1L shl offset)))
    )

    @Test
    fun `AUTO choices need no dialogue marker or successful overlay render`() {
        assertFalse(state.dialogueComplete(false, false, null, Int::equals))
        assertFalse(state.shouldSkipChoices(choices(), 0L, false))
        state.acceptChoices(choices())
        assertTrue(state.shouldSkipChoices(choices(), 400L, false))
    }

    @Test
    fun `incomplete uncommitted choices remain retryable`() {
        repeat(3) { assertFalse(state.shouldSkipChoices(choices(), it * 400L, false)) }
    }

    @Test
    fun `changed choice text is recognized immediately`() {
        state.acceptChoices(choices())
        assertTrue(state.shouldSkipChoices(choices(), 0L, false))
        assertFalse(state.shouldSkipChoices(choices(1), 400L, false))
        assertFalse(state.shouldSkipChoices(choices(1), 800L, false))
        state.acceptChoices(choices(1))
        assertTrue(state.shouldSkipChoices(choices(1), 1_200L, false))
    }

    @Test
    fun `periodic verification has same bounded skip as JP`() {
        state.acceptChoices(choices())
        assertTrue(state.shouldSkipChoices(choices(), 100L, false))
        assertTrue(state.shouldSkipChoices(choices(), 1_299L, false))
        assertFalse(state.shouldSkipChoices(choices(), 1_300L, false))
        assertFalse(state.shouldSkipChoices(choices(), 1_700L, false))
        state.acceptChoices(choices())
        assertTrue(state.shouldSkipChoices(choices(), 2_000L, false))
    }

    @Test
    fun `mixed dialogue choices do not hide changed dialogue`() {
        state.acceptChoices(choices())
        assertFalse(state.shouldSkipChoices(choices(), 100L, true))
    }

    @Test
    fun `one absent frame is animation and two permit repeated choice recognition`() {
        state.acceptChoices(choices())
        state.onChoicesAbsent()
        assertFalse(state.hasChoiceDeparture())
        assertTrue(state.shouldSkipChoices(choices(), 100L, false))
        state.onChoicesAbsent()
        state.onChoicesAbsent()
        assertTrue(state.hasChoiceDeparture())
        assertFalse(state.shouldSkipChoices(choices(), 500L, false))
        state.acceptChoices(choices())
        assertFalse(state.hasChoiceDeparture())
    }

    @Test
    fun `strict diamond accepts immediately and breaks old fallback evidence`() {
        assertFalse(state.dialogueComplete(false, true, 1, Int::equals))
        assertTrue(state.dialogueComplete(true, false, null, Int::equals))
        assertFalse(state.dialogueComplete(false, true, 1, Int::equals))
        assertFalse(state.dialogueComplete(false, true, 1, Int::equals))
        assertTrue(state.dialogueComplete(false, true, 1, Int::equals))
    }

    @Test
    fun `fallback needs three stable masks all with marker evidence`() {
        assertFalse(state.dialogueComplete(false, true, 1, Int::equals))
        assertFalse(state.dialogueComplete(false, true, 2, Int::equals))
        assertFalse(state.dialogueComplete(false, true, 2, Int::equals))
        assertTrue(state.dialogueComplete(false, true, 2, Int::equals))
    }

    @Test
    fun `missing evidence or mask cannot become dialogue completion`() {
        repeat(2) { assertFalse(state.dialogueComplete(false, true, 1, Int::equals)) }
        assertFalse(state.dialogueComplete(false, false, 1, Int::equals))
        assertFalse(state.dialogueComplete(false, true, 1, Int::equals))
        assertFalse(state.dialogueComplete(false, true, null, Int::equals))
        repeat(2) { assertFalse(state.dialogueComplete(false, true, 1, Int::equals)) }
        assertTrue(state.dialogueComplete(false, true, 1, Int::equals))
    }

    @Test
    fun `source baselines are mode specific and support equivalent OCR stability keys`() {
        state.acceptSource("AUTO_BACKGROUND", "source1", "dialogue1")
        assertTrue(state.isAccepted("AUTO_BACKGROUND", "source1", ""))
        assertTrue(state.isAccepted("AUTO_BACKGROUND", "changed boxes", "dialogue1"))
        assertFalse(state.isAccepted("SEMI_AUTO_CHOICE_TAP", "source1", "dialogue1"))
        assertFalse(state.isAccepted("AUTO_BACKGROUND", "source2", ""))
    }

    @Test
    fun `retry grows within bounds and accepted recognition clears it`() {
        state.rememberFailure("AUTO", "empty", 0L, 400L, 1_200L)
        assertTrue(state.isCoolingDown("AUTO", 399L))
        assertFalse(state.isCoolingDown("AUTO", 400L))
        state.rememberFailure("AUTO", "empty", 400L, 400L, 1_200L)
        assertTrue(state.isCoolingDown("AUTO", 1_199L))
        assertFalse(state.isCoolingDown("AUTO", 1_200L))
        repeat(4) { state.rememberFailure("AUTO", "empty", 1_200L, 400L, 1_200L) }
        assertFalse(state.isCoolingDown("AUTO", 2_400L))
        state.acceptSource("AUTO", "source", "text")
        assertFalse(state.isCoolingDown("AUTO", 1_200L))
    }

    @Test
    fun `advance hint wakes capture without clearing accepted text or triggering choice speech`() {
        state.acceptSource("SEMI", "dialogue", "text")
        state.delayFor("SEMI", 0L, 900L)
        state.dialogueComplete(false, true, 1, Int::equals)
        val generation = state.generation
        state.onAdvanceObserved()
        assertFalse(state.isCoolingDown("SEMI", 100L))
        assertTrue(state.isAccepted("SEMI", "dialogue", "text"))
        assertFalse(state.dialogueComplete(false, true, 1, Int::equals))
        assertEquals(generation, state.generation)
    }

    @Test
    fun `reset invalidates pending voice context and all non JP baselines`() {
        state.acceptSource("AUTO", "source", "text")
        state.acceptChoices(choices())
        state.emptyChoiceCooldown.recordEmpty(choices(), 0L)
        state.delayFor("AUTO", 0L, 1_200L)
        state.dialogueComplete(false, true, 1, Int::equals)
        val generation = state.generation
        state.reset()
        assertEquals(generation + 1, state.generation)
        assertFalse(state.isAccepted("AUTO", "source", "text"))
        assertFalse(state.shouldSkipChoices(choices(), 100L, false))
        assertFalse(state.emptyChoiceCooldown.isCoolingDown(choices(), 100L))
        assertFalse(state.isCoolingDown("AUTO", 100L))
        assertFalse(state.dialogueComplete(false, true, 1, Int::equals))
    }

    @Test
    fun `non JP choice acceptance cannot update JP render or cooldown owners`() {
        val jp = StoryTapHandoff(2_500L)
        val jpCooldown = ChoiceRecognitionPolicy.EmptyOcrCooldown()
        state.acceptChoices(choices())
        state.emptyChoiceCooldown.recordEmpty(choices(), 0L)
        assertEquals(0L, jp.autoRenderGeneration)
        assertFalse(jp.shouldSkipRenderedChoices(choices(), 100L, false))
        assertFalse(jpCooldown.isCoolingDown(choices(), 100L))
        state.reset()
        jp.onSceneRendered("jp", 120, 100, StoryTapHandoff.Kind.CHOICES, listOf(bounds), choices(), true)
        assertFalse(state.shouldSkipChoices(choices(), 100L, false))
        assertTrue(jp.shouldSkipRenderedChoices(choices(), 100L, false))
    }

    @Test
    fun `dialogue visual baseline is recognition committed not overlay committed`() {
        fun observe(now: Long) = state.visualGate.observe(
            StoryOcrVisualScope.AUTO, 120, 100,
            StoryOcrVisualBounds(0, 0, 30, 30), StoryOcrVisualBounds(0, 40, 120, 100),
            pixel = { x, y -> if (x in 10..25 && y in 55..72) -1 else 0xff142943.toInt() }, now = now
        )
        val initial = observe(0L)
        state.visualGate.completeRecognition(initial.recognitionToken, false)
        val retry = observe(400L)
        assertEquals(StoryOcrVisualAction.RECOGNIZE, retry.action)
        state.visualGate.completeRecognition(retry.recognitionToken, true)
        assertEquals(StoryOcrVisualAction.SKIP_UNCHANGED, observe(800L).action)
        val jpGate = StoryOcrVisualGate()
        val jpInitial = jpGate.observe(
            StoryOcrVisualScope.AUTO, 120, 100,
            StoryOcrVisualBounds(0, 0, 30, 30), StoryOcrVisualBounds(0, 40, 120, 100),
            pixel = { _, _ -> -1 }, now = 800L
        )
        assertEquals(StoryOcrVisualAction.RECOGNIZE, jpInitial.action)
    }

    @Test
    fun `non JP coordinator cannot enter translation or JP overlay state`() {
        val file = listOf(
            File("src/main/java/com/fgogotran/accessibility/FgoAccessibilityService.kt"),
            File("app/src/main/java/com/fgogotran/accessibility/FgoAccessibilityService.kt")
        ).first(File::isFile)
        val text = file.readText()
        val functions = Regex("(?m)^    private (?:suspend )?fun ").split(text).drop(1)
        val voiceFunctions = functions.filter {
            it.startsWith("processVoiceOnly") || it.startsWith("scanVoiceOnly") ||
                it.startsWith("voiceOnlyDialogueCompletion") || it.startsWith("requestVoiceOnlyScene(")
        }
        assertEquals(7, voiceFunctions.size)
        for (body in voiceFunctions) {
            for (forbidden in listOf("translateSceneSource(", "translateAndRenderScene(", "translator.translate",
                    "overlayRenderer.", "storyTapHandoff.", "rememberRenderedSourceText(", "isAlreadyRenderedSource(")) {
                assertFalse(body.contains(forbidden), "${body.substringBefore('(')} calls $forbidden")
            }
        }
        assertTrue(text.contains("emptyCooldown: ChoiceRecognitionPolicy.EmptyOcrCooldown = emptyChoiceOcrCooldown"))
        assertTrue(text.contains("useVoiceOnlyNameCache: Boolean = false"))
    }
}
