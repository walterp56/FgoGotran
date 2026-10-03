package com.fgogotran.story

import com.fgogotran.overlay.FgoReferenceRect
import com.fgogotran.translation.TranslationMode
import com.fgogotran.translation.TranslationTrigger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ChoiceRecognitionPolicyTest {
    private fun buttons(count: Int) = (0 until count).map {
        FgoReferenceRect(220, 20 + it * 140, 1690, 150 + it * 140)
    }

    @AfterTest
    fun resetTranslationTrigger() {
        TranslationTrigger.setTranslationMode(TranslationMode.MANUAL)
    }

    @Test
    fun `semi-auto dialogue to choices waits for a floating button request`() {
        assertSemiAutoAdvanceWaitsForChoiceRequest(StoryTapHandoff.Kind.DIALOGUE)
    }

    @Test
    fun `semi-auto choices to choices waits for another floating button request`() {
        assertSemiAutoAdvanceWaitsForChoiceRequest(StoryTapHandoff.Kind.CHOICES)
    }

    @Test
    fun `AUTO can recognize choices without a user request`() {
        TranslationTrigger.setTranslationMode(TranslationMode.AUTO)
        assertFalse(TranslationTrigger.consumeRequest())
        assertTrue(ChoiceRecognitionPolicy.canRecognize(
            userInitiated = false, fullAuto = TranslationTrigger.isFullAutoEnabled()))
    }

    @Test
    fun `manual choices still require and accept an explicit request`() {
        TranslationTrigger.setTranslationMode(TranslationMode.MANUAL)
        assertFalse(canRecognizeRequestedChoices())
        TranslationTrigger.requestTranslation()
        assertTrue(canRecognizeRequestedChoices())
        assertFalse(canRecognizeRequestedChoices())
    }

    @Test
    fun `complete one through six button lists are accepted`() {
        for (count in 1..6) {
            val expected = buttons(count)
            assertTrue(ChoiceRecognitionPolicy.isComplete(expected, expected.map { it to "選択肢" }))
        }
    }

    @Test
    fun `empty list and incomplete two or six button list are rejected`() {
        assertFalse(ChoiceRecognitionPolicy.isComplete(emptyList<String>(), emptyList()))
        for (count in listOf(2, 6)) {
            val expected = buttons(count)
            assertFalse(ChoiceRecognitionPolicy.isComplete(expected, expected.dropLast(1).map { it to "選択肢" }))
        }
    }

    @Test
    fun `equal count with duplicate or wrong button identity is rejected`() {
        val expected = buttons(2)
        assertFalse(ChoiceRecognitionPolicy.isComplete(expected, listOf(expected[0] to "一", expected[0] to "二")))
        assertFalse(ChoiceRecognitionPolicy.isComplete(expected, listOf(expected[0] to "一", buttons(3)[2] to "二")))
        assertFalse(ChoiceRecognitionPolicy.isComplete(listOf(expected[0], expected[0]),
            listOf(expected[0] to "一", expected[1] to "二")))
    }

    @Test
    fun `extra or blank row is rejected but reordered complete rows are accepted`() {
        val expected = buttons(2)
        assertFalse(ChoiceRecognitionPolicy.isComplete(expected, buttons(3).map { it to "選択肢" }))
        assertFalse(ChoiceRecognitionPolicy.isComplete(expected, listOf(expected[0] to "一", expected[1] to " \n\t")))
        assertTrue(ChoiceRecognitionPolicy.isComplete(expected, expected.reversed().map { it to "選択肢" }))
    }

    @Test
    fun `punctuation-only rows do not need Japanese words`() {
        val expected = buttons(2)
        assertTrue(ChoiceRecognitionPolicy.isComplete(expected, listOf(expected[0] to "……", expected[1] to "───")))
    }

    @Test
    fun `a required row lost during source formatting is rejected without poisoning the next result`() {
        val expected = buttons(2)
        val raw = expected.map { it to "選択肢" }
        assertTrue(ChoiceRecognitionPolicy.isComplete(expected, raw))
        val formatted = listOf(expected[0] to "選択肢", expected[1] to "")
        assertFalse(ChoiceRecognitionPolicy.isComplete(expected, formatted))
        assertFalse(ChoiceRecognitionPolicy.isComplete(expected, formatted.filter { it.second.isNotBlank() }))
        assertTrue(ChoiceRecognitionPolicy.isComplete(expected, raw))
    }

    private fun assertSemiAutoAdvanceWaitsForChoiceRequest(kind: StoryTapHandoff.Kind) {
        TranslationTrigger.setTranslationMode(TranslationMode.SEMI_AUTO)
        val handoff = StoryTapHandoff(2_500L)
        val bounds = buttons(2)
        val oldChoices = if (kind == StoryTapHandoff.Kind.CHOICES) {
            StoryTapHandoff.ChoiceFrame(1920, 1080, bounds.map {
                StoryTapHandoff.ChoiceMask(it, 1, longArrayOf(1L))
            })
        } else null
        handoff.onSceneRendered("old scene", 1920, 1080, kind,
            choiceHitBounds = bounds, choices = oldChoices)
        handoff.touchDown(500f, 100f, 12f)
        val ticket = assertNotNull(handoff.takeTap(500f, 100f))
        assertTrue(handoff.beginAfterReplay(ticket, 100L))
        // Story/choice taps only arm the next-scene hint. They never request choice OCR.
        for (now in listOf(200L, 1_000L, 2_000L)) {
            assertTrue(handoff.isPending(now))
            assertFalse(canRecognizeRequestedChoices())
            assertTrue(TranslationTrigger.isSemiAutoEnabled())
        }
        TranslationTrigger.requestTranslation()
        assertTrue(canRecognizeRequestedChoices())
        assertFalse(canRecognizeRequestedChoices()) // One request, not continuous AUTO.
        assertTrue(TranslationTrigger.isSemiAutoEnabled())
    }

    private fun canRecognizeRequestedChoices() = ChoiceRecognitionPolicy.canRecognize(
        userInitiated = TranslationTrigger.consumeRequest(),
        fullAuto = TranslationTrigger.isFullAutoEnabled()
    )
}
