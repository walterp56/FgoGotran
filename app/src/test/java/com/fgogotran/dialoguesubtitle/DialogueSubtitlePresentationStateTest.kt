package com.fgogotran.dialoguesubtitle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DialogueSubtitlePresentationStateTest {
    private fun window(vararg ids: Long) = ids.mapIndexed { slot, id -> DialogueSubtitleVisibleRow(id, slot) }
    private fun event(vararg ids: Long) = DialogueSubtitleEvent(ids.first(), "JP", 0,
        ids.map { DialogueSubtitleSourceRow(it, "JP$it") })
    private fun DialogueSubtitlePresentationState<String>.ready(vararg ids: Long) =
        complete(event(*ids), ids.map { "T$it" }, "owner${ids.first()}")

    @Test fun `fast result for a tentative row waits for confirmation without being discarded`() {
        val state = DialogueSubtitlePresentationState<String>()
        state.observe(window(1), 0); state.ready(1)
        state.observe(window(1), 100, window(2, 3)); state.ready(2)
        assertEquals(listOf("T1"), state.candidate(100).map { it.text })
        state.observe(window(2, 3), 200, window(2, 3))
        assertEquals(listOf("T2"), state.candidate(200).map { it.text })
        state.ready(1)
        assertEquals(listOf("T2"), state.candidate(201).map { it.text })
    }

    @Test fun `initial cache completion before first confirmed window is retained without early display`() {
        val state = DialogueSubtitlePresentationState<String>()
        state.observe(null, 0, window(1)); state.ready(1)
        assertTrue(state.candidate(0).isEmpty())
        state.observe(window(1), 800)
        assertEquals("T1", state.candidate(800).single().text)
    }

    @Test fun `recording source timeline stays aligned even when translation completes immediately`() {
        val assembler = DialogueSubtitleAssembler()
        val state = DialogueSubtitlePresentationState<String>()
        fun frame(at: Long, top: String, bottom: String = "", panel: Boolean = true): List<String> {
            val observed = listOfNotNull(
                top.takeIf(String::isNotEmpty)?.let { DialogueSubtitleRow(it, 50f, 92f, 0.97f) },
                bottom.takeIf(String::isNotEmpty)?.let { DialogueSubtitleRow(it, 130f, 172f, 0.97f) })
            val events = assembler.observe(observed, panel, at)
            state.observe(assembler.visibleSnapshot, at, assembler.observedSnapshot)
            for (event in events) state.complete(event, event.rows.map { "T:${it.text}" }, "owner${event.id}")
            val displayed = state.candidate(at)
            state.markDrawn(displayed, at)
            return displayed.map { it.text.removePrefix("T:") }
        }
        val a = "はじめまして、汎人類史のお友達！"
        val b = "私はククルカン。"
        val c = "樹海の水と風と大地の守護者にして、えーーと……"
        val d = "なんだっけ？"
        val e = "ま、いいですよね、細かいことは。"
        val f = "これからたくさん、貴方たちの文化を学ばせて"
        frame(1_000, a); assertEquals(listOf(a), frame(1_800, a))
        frame(5_200, b); frame(5_800, b, c)
        assertEquals(listOf(b, c), frame(6_600, b, c))
        frame(14_200, c, d); assertEquals(listOf(c, d), frame(15_000, c, d))
        frame(16_500, d, e); assertEquals(listOf(d, e), frame(17_500, d, e))
        frame(20_800, e, f); frame(21_400, f, "いただき")
        frame(22_000, f, "いただきます♡")
        assertEquals(listOf(f, "いただきます♡"), frame(22_800, f, "いただきます♡"))
        frame(27_300, "", panel = false)
        assertEquals(listOf(f, "いただきます♡"), frame(28_100, "", panel = false))
        assertEquals(2, state.candidate(30_099).size)
        assertTrue(state.candidate(30_100).isEmpty())
    }

    @Test fun `AB to BC carries B upward without another request and waits for C`() {
        val state = DialogueSubtitlePresentationState<String>()
        state.observe(window(1, 2), 0); state.ready(1); state.ready(2)
        assertEquals(listOf("T1", "T2"), state.candidate(10).map { it.text })
        state.observe(window(2, 3), 100)
        assertEquals(listOf(2L to 0), state.candidate(100).map { it.id to it.slot })
        state.ready(3)
        assertEquals(listOf("T2", "T3"), state.candidate(101).map { it.text })
    }

    @Test fun `complete replacement clears both old rows and late answers cannot jump back`() {
        val state = DialogueSubtitlePresentationState<String>()
        state.observe(window(1, 2), 0); state.ready(1)
        state.markDrawn(state.candidate(0), 0)
        state.observe(window(3, 4), 100)
        assertTrue(state.candidate(100).isEmpty())
        state.ready(2); state.ready(1)
        assertTrue(state.candidate(101).isEmpty())
        state.ready(3, 4)
        assertEquals(listOf(3L, 4L), state.candidate(102).map { it.id })
    }

    @Test fun `ready lower row is displayed in its slot without waiting for upper API`() {
        val state = DialogueSubtitlePresentationState<String>()
        state.observe(window(1, 2), 0); state.ready(2)
        assertEquals(listOf(2L to 1), state.candidate(0).map { it.id to it.slot })
        state.ready(1)
        assertEquals(listOf(1L, 2L), state.candidate(1).map { it.id })
    }

    @Test fun `caption stays while source is visible even after two seconds or an unknown observation`() {
        val state = DialogueSubtitlePresentationState<String>()
        state.observe(window(1), 0); state.ready(1)
        state.markDrawn(state.candidate(0), 0)
        assertEquals("T1", state.candidate(60_000).single().text)
        state.observe(null, 60_000)
        assertEquals("T1", state.candidate(120_000).single().text)
    }

    @Test fun `tail starts at confirmed disappearance and repeated refresh does not extend it`() {
        val state = DialogueSubtitlePresentationState<String>()
        state.observe(window(1), 0); state.ready(1)
        state.markDrawn(state.candidate(0), 0)
        state.observe(emptyList(), 10_000)
        assertEquals("T1", state.candidate(11_999).single().text)
        state.observe(emptyList(), 11_999)
        state.markDrawn(state.candidate(11_999), 11_999)
        assertTrue(state.candidate(12_000).isEmpty())
    }

    @Test fun `new source ends the tail before two seconds without flashing the old caption`() {
        val state = DialogueSubtitlePresentationState<String>()
        state.observe(window(1), 0); state.ready(1); state.markDrawn(state.candidate(0), 0)
        state.observe(emptyList(), 100)
        state.observe(window(2), 200)
        assertTrue(state.candidate(200).isEmpty())
        state.ready(2)
        assertEquals("T2", state.candidate(201).single().text)
    }

    @Test fun `undrawn or late output does not appear in a blank tail`() {
        val state = DialogueSubtitlePresentationState<String>()
        state.observe(window(1, 2), 0); state.ready(1)
        state.observe(emptyList(), 100)
        state.ready(2)
        assertTrue(state.candidate(101).isEmpty())
    }

    @Test fun `identical text on a new occurrence does not inherit the old result`() {
        val state = DialogueSubtitlePresentationState<String>()
        state.observe(window(1), 0)
        state.complete(event(1), listOf("はい"), "one")
        state.observe(window(2), 100)
        assertTrue(state.candidate(100).isEmpty())
        state.complete(event(2), listOf("はい"), "two")
        assertEquals(2L, state.candidate(101).single().id)
    }

    @Test fun `one wrapped utterance maps only its currently visible source fragments`() {
        val state = DialogueSubtitlePresentationState<String>()
        state.observe(window(2, 3), 0)
        state.complete(event(1, 2), listOf("first", "continuation"), "sentence")
        assertEquals(listOf("continuation"), state.candidate(0).map { it.text })
    }

    @Test fun `pause requires fresh confirmation and rejects callbacks for the previous window`() {
        val state = DialogueSubtitlePresentationState<String>()
        state.observe(window(1), 0); state.ready(1)
        val old = state.candidate(0)
        state.pause()
        assertTrue(state.candidate(50_000).isEmpty())
        assertFalse(state.markDrawn(old, 50_000))
        state.observe(window(2), 50_001); state.ready(2)
        assertFalse(state.markDrawn(old, 50_002))
        assertEquals("T2", state.candidate(50_002).single().text)
        state.clear(); state.ready(2)
        assertTrue(state.candidate(50_003).isEmpty())
    }

    @Test fun `completion during pause is reused only if fresh source confirms the same row`() {
        val state = DialogueSubtitlePresentationState<String>()
        state.observe(window(1), 0); state.pause(); state.ready(1)
        assertTrue(state.candidate(1).isEmpty())
        state.observe(window(1), 10)
        assertEquals("T1", state.candidate(10).single().text)
    }
}
