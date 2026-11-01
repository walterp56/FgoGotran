package com.fgogotran.overlay

import kotlin.test.*
import com.fgogotran.ui.component.DialoguePlaybackExample

class DialoguePlaybackTest {
    private fun timeline(text: String, speed: DialogueTextSpeed = DialogueTextSpeed.NORMAL, pause: Int = 0,
                         ends: IntArray = (1..text.length).toList().toIntArray()) =
        DialoguePlaybackTimeline(text, ends, DialoguePlaybackSettings(speed, pause))

    @Test fun `MAX with zero pause still shows all text immediately`() {
        val t = timeline("你好，世界！", DialogueTextSpeed.INSTANT, 0)
        assertEquals(0L, t.durationNanos)
        assertEquals(t.text.length, t.visibleEndAt(0))
    }

    @Test fun `MAX applies every pause level independently of character speed`() {
        for (level in 1..3) {
            val t = timeline("甲，乙。丙", DialogueTextSpeed.INSTANT, level)
            val pause = level * 100_000_000L
            assertEquals(2, t.visibleEndAt(0))
            assertEquals(2, t.visibleEndAt(pause - 1))
            assertEquals(4, t.visibleEndAt(pause))
            assertEquals(4, t.visibleEndAt(2 * pause - 1))
            assertEquals(5, t.visibleEndAt(2 * pause))
            assertEquals(2 * pause, t.durationNanos)
        }
    }

    @Test fun `MAX with punctuation uses playback while MAX zero retains static routing`() {
        assertFalse(DialoguePlaybackSettings(DialogueTextSpeed.INSTANT, 0).requiresPlayback)
        for (level in 1..3) assertTrue(DialoguePlaybackSettings(DialogueTextSpeed.INSTANT, level).requiresPlayback)
        for (speed in DialogueTextSpeed.entries.filterNot { it == DialogueTextSpeed.INSTANT }) {
            assertTrue(DialoguePlaybackSettings(speed, 0).requiresPlayback)
        }
    }

    @Test fun `MAX punctuation pause can be completed by the first tap without waiting`() {
        val c = DialoguePlaybackController()
        c.start(timeline("甲，乙", DialogueTextSpeed.INSTANT, 3), 0)
        assertEquals(2, c.visibleEnd(0))
        assertTrue(c.isRevealing(0))
        c.complete()
        assertEquals(3, c.visibleEnd(0))
        assertFalse(c.isRevealing(0))
    }

    @Test fun `slow sample is five characters per second and each multiplier increases speed`() {
        val t = timeline("砂上船のご利用", DialogueTextSpeed.HALF)
        assertEquals(1, t.visibleEndAt(0))
        assertEquals(1, t.visibleEndAt(199_999_999))
        assertEquals(2, t.visibleEndAt(200_000_000))
        assertEquals(3, t.visibleEndAt(400_000_000))
        val durations = DialogueTextSpeed.entries.map { timeline("abcdefghij", it).durationNanos }
        assertTrue(durations.zipWithNext().all { (a, b) -> a > b })
    }

    @Test fun `pause levels increase time and a punctuation cluster pauses once`() {
        assertEquals(400_000_000L, timeline("甲，乙", pause = 2).durationNanos)
        assertEquals(600_000_000L, timeline("甲……乙", pause = 3).durationNanos)
        assertEquals(200_000_000L, timeline("甲，乙").durationNanos)
        assertEquals(100_000_000L, timeline("甲。", pause = 3).durationNanos)
    }

    @Test fun `decimal and initialism dots are not pauses`() {
        for (s in listOf("1.5", "Dr. Smith", "U.S. Army", "Mr. Holmes")) {
            assertEquals(timeline(s).durationNanos, timeline(s, pause = 3).durationNanos, s)
            assertEquals(0L, timeline(s, DialogueTextSpeed.INSTANT, 3).durationNanos, s)
        }
        assertTrue(timeline("Done. Next", pause = 3).durationNanos > timeline("Done. Next").durationNanos)
    }

    @Test fun `line breaks and layout spacing do not add a page delay`() {
        assertEquals(timeline("甲乙").durationNanos, timeline("甲\n乙").durationNanos)
        assertEquals(timeline("甲乙").durationNanos, timeline("甲　乙").durationNanos)
    }

    @Test fun `provided Unicode boundaries never reveal half an emoji or combining character`() {
        val text = "A👩‍🚀e\u0301Z"
        val t = timeline(text, ends = intArrayOf(1, 6, 8, 9))
        assertEquals(1, t.visibleEndAt(0))
        assertEquals(6, t.visibleEndAt(100_000_000))
        assertEquals(8, t.visibleEndAt(200_000_000))
        assertEquals(9, t.visibleEndAt(Long.MAX_VALUE))
    }

    @Test fun `capture pauses the clock and resumes without restarting`() {
        val c = DialoguePlaybackController()
        c.start(timeline("abcde"), 0)
        c.pause(150_000_000)
        assertEquals(2, c.visibleEnd(9_000_000_000))
        c.resume(9_000_000_000)
        assertEquals(3, c.visibleEnd(9_050_000_000))
        c.complete()
        assertEquals(5, c.visibleEnd(9_050_000_000))
        assertFalse(c.isRevealing(9_050_000_000))
    }

    @Test fun `replacement and cancellation invalidate previous owners even for identical text`() {
        val c = DialoguePlaybackController()
        c.start(timeline("same"), 0)
        val first = c.generation
        c.start(timeline("same"), 500_000_000)
        assertNotEquals(first, c.generation)
        assertEquals(1, c.visibleEnd(500_000_000))
        val second = c.generation
        c.cancel()
        assertNotEquals(second, c.generation)
        assertFalse(c.isRevealing(Long.MAX_VALUE))
    }

    @Test fun `latched first tap remains consumed if typing completes before release`() {
        val gate = DialoguePlaybackTapGate()
        gate.down(1, 100f, 100f, 10f, true)
        assertTrue(gate.up(1, 100f, 100f))
        assertFalse(gate.up(1, 100f, 100f))
        gate.down(1, 100f, 100f, 10f, false)
        assertFalse(gate.up(1, 100f, 100f))
    }

    @Test fun `drag cancel and a new render cannot complete dialogue`() {
        val gate = DialoguePlaybackTapGate()
        gate.down(1, 1f, 1f, 5f, true)
        gate.move(20f, 20f)
        assertFalse(gate.up(1, 1f, 1f))
        gate.down(1, 1f, 1f, 5f, true)
        assertFalse(gate.up(2, 1f, 1f))
        gate.down(2, 1f, 1f, 5f, true)
        gate.cancel()
        assertFalse(gate.up(2, 1f, 1f))
    }

    @Test fun `unknown stored speed retains instant default and pauses are bounded`() {
        assertEquals(DialogueTextSpeed.INSTANT, DialogueTextSpeed.fromStored("bad"))
        assertEquals(DialogueTextSpeed.INSTANT, DialogueTextSpeed.fromStored(null))
        DialogueTextSpeed.entries.forEach { assertEquals(it, DialogueTextSpeed.fromStored(it.storedValue)) }
        assertEquals(0, DialoguePlaybackSettings(punctuationPause = -1).pauseLevel)
        assertEquals(3, DialoguePlaybackSettings(punctuationPause = 9).pauseLevel)
    }

    @Test fun `preview uses paired Mash rows and excludes ruby from playback timing`() {
        for (locale in listOf("zh-Hans", "zh-Hant", "en")) {
            val sample = DialoguePlaybackExample.forTarget(locale)
            assertEquals(2, sample.lines.size)
            assertEquals(sample.lines.size, sample.originalLines.size)
            assertEquals(sample.lines[0].length + 1, sample.starts[1])
            assertEquals(sample.lines[1], sample.text.substring(sample.starts[1]))
            assertTrue(RubyRenderText.hasRuby(sample.renderText(true)))
            assertFalse(RubyRenderText.hasRuby(sample.renderText(false)))
            assertEquals(sample.text, RubyRenderText.body(sample.renderText(true)))
            assertTrue(sample.originalText.contains("昼食〈ランチ〉"))
            val range = sample.emphasisRanges.single()
            assertEquals(if (locale == "en") "Dessert" else if (locale == "zh-Hant") "甜點" else "甜点",
                sample.annotatedText.substring(range.start, range.endExclusive))
            val t = timeline(sample.text, DialogueTextSpeed.INSTANT, 3)
            assertTrue(t.durationNanos > 0, locale)
            assertTrue(t.visibleEndAt(0) < sample.starts[1], locale)
            assertEquals(sample.text.length, t.visibleEndAt(t.durationNanos), locale)
        }
    }
}
