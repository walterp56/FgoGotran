package com.fgogotran.ocr

import com.fgogotran.story.StoryOcrVisualAction
import com.fgogotran.story.StoryOcrVisualBounds
import com.fgogotran.story.StoryOcrVisualGate
import com.fgogotran.story.StoryOcrVisualScope
import kotlin.test.Test
import kotlin.test.assertEquals

class DialogueOcrVisualGatePaletteTest {
    @Test
    fun `all approved dialogue colours trigger OCR with an unchanged white speaker`() {
        FgoStoryTextPalette.samples.forEach { sample ->
            val gate = StoryOcrVisualGate()
            fun observe(dialogueLeft: Int, now: Long) = gate.observe(
                StoryOcrVisualScope.SEMI_AUTO, 120, 100,
                StoryOcrVisualBounds(0, 0, 30, 30), StoryOcrVisualBounds(0, 40, 120, 100),
                pixel = { x, y ->
                    when {
                        x in 5..16 && y in 5..16 -> -1
                        x in dialogueLeft until dialogueLeft + 12 && y in 55..72 -> sample.renderColor
                        else -> 0xff142943.toInt()
                    }
                }, now = now
            )
            val first = observe(20, 0)
            assertEquals(StoryOcrVisualAction.RECOGNIZE, first.action, sample.family.name)
            gate.completeRecognition(first.recognitionToken, accepted = true)
            assertEquals(StoryOcrVisualAction.SKIP_UNCHANGED, observe(20, 100).action, sample.family.name)
            assertEquals(StoryOcrVisualAction.RECOGNIZE, observe(60, 200).action, sample.family.name)
        }
    }

    @Test
    fun `failed recognition does not suppress the next attempt`() {
        val gate = StoryOcrVisualGate()
        fun observe(now: Long) = gate.observe(
            StoryOcrVisualScope.AUTO, 100, 100,
            StoryOcrVisualBounds(0, 0, 0, 0), StoryOcrVisualBounds(0, 40, 100, 100),
            pixel = { x, y -> if (x in 20..40 && y in 55..72) 0xff8753d1.toInt() else 0xff142943.toInt() },
            now = now
        )
        val first = observe(0)
        gate.completeRecognition(first.recognitionToken, accepted = false)
        assertEquals(StoryOcrVisualAction.RECOGNIZE, observe(100).action)
    }
}
