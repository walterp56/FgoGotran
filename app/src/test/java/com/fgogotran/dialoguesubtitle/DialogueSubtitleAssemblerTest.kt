package com.fgogotran.dialoguesubtitle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DialogueSubtitleAssemblerTest {
    @Test fun `confirmed visible snapshots carry IDs upward and fully replace unrelated rows`() {
        val assembler = DialogueSubtitleAssembler()
        assembler.observe(listOf(row("一。"), row("二。", true)), true, 0)
        assertNull(assembler.visibleSnapshot)
        val events = assembler.observe(listOf(row("一。"), row("二。", true)), true, 800)
        val first = assembler.visibleSnapshot!!
        assertEquals(events.map { it.rows.single().id }, first.map { it.id })
        assembler.observe(listOf(row("二。"), row("三。", true)), true, 1_000)
        assertEquals(first, assembler.visibleSnapshot)
        assembler.observe(listOf(row("二。"), row("三。", true)), true, 1_800)
        val carried = assembler.visibleSnapshot!!
        assertEquals(first.last().id, carried.first().id)
        assertEquals(0, carried.first().slot)
        assembler.observe(listOf(row("四。"), row("五。", true)), true, 2_000)
        assembler.observe(listOf(row("四。"), row("五。", true)), true, 2_800)
        assertTrue(assembler.visibleSnapshot!!.none { it.id in carried.map { r -> r.id } })
    }

    @Test fun `wrapped sentence retains original row boundaries and occurrence IDs`() {
        val assembler = DialogueSubtitleAssembler()
        val rows = listOf(row("貴方たちの文化を学ばせて"), row("いただきます♡", true))
        assembler.observe(rows, true, 0)
        val event = assembler.observe(rows, true, 800).single()
        assertEquals(rows.map { it.text }, event.rows.map { it.text })
        assertEquals(assembler.visibleSnapshot!!.map { it.id }, event.rows.map { it.id })
        assertEquals(rows.joinToString("") { it.text }, event.source)
    }

    @Test fun `typing membership confirms independently and pause requires two fresh observations`() {
        val assembler = DialogueSubtitleAssembler()
        assembler.observe(listOf(row("私")), true, 0)
        assembler.observe(listOf(row("私は")), true, 400)
        val window = assembler.visibleSnapshot!!
        assembler.requireFreshSnapshot()
        assertNull(assembler.visibleSnapshot)
        assembler.observe(listOf(row("私はククルカン。")), true, 800)
        assertNull(assembler.visibleSnapshot)
        assembler.observe(listOf(row("私はククルカン。")), true, 1_600)
        assertEquals(window, assembler.visibleSnapshot)
    }

    @Test fun `only confirmed absence empties source snapshot and bad OCR remains unknown`() {
        val assembler = DialogueSubtitleAssembler()
        assembler.observe(listOf(row("はい。")), true, 0)
        assembler.observe(listOf(row("はい。")), true, 800)
        val window = assembler.visibleSnapshot
        assembler.observe(emptyList(), false, 1_000)
        assembler.observationUnavailable()
        assembler.observe(emptyList(), false, 2_000)
        assertEquals(window, assembler.visibleSnapshot)
        assembler.observe(emptyList(), false, 2_600)
        assertEquals(emptyList(), assembler.visibleSnapshot)
    }

    @Test fun `confirmed blank does not force continuous OCR confirmations`() {
        val assembler = DialogueSubtitleAssembler()
        assembler.observe(listOf(row("はい。")), true, 0)
        assembler.observe(listOf(row("はい。")), true, 800)
        assembler.observe(emptyList(), true, 1_000)
        assertTrue(assembler.needsConfirmation)
        assembler.observe(emptyList(), true, 1_600)
        assertEquals(emptyList(), assembler.visibleSnapshot)
        assertTrue(!assembler.needsConfirmation)
    }

    @Test fun `uncertain trailing row cannot erase a confirmed leading fragment at panel exit`() {
        val assembler = DialogueSubtitleAssembler()
        assembler.observe(listOf(row("続きを待って")), true, 0)
        assembler.observe(listOf(row("続きを待って")), true, 400)
        assembler.observe(listOf(row("続きを待って"), row("不明", true).copy(confidence = 0.6f)), true, 500)
        assembler.observe(emptyList(), false, 600)
        assertEquals(listOf("続きを待って"), assembler.observe(emptyList(), false, 1_300).map { it.source })
    }

    @Test fun `confirmed panel exit retains a strong single observation of a complete wrapped sentence`() {
        val assembler = DialogueSubtitleAssembler()
        val first = "これからたくさん、貴方たちの文化を学ばせて"
        assembler.observe(listOf(row(first), row("いただきます♡", true)), true, 0)
        assembler.observe(emptyList(), false, 400)
        assertEquals(listOf(first + "いただきます♡"),
            assembler.observe(emptyList(), false, 1_200).map { it.source })
    }

    @Test fun `single character typing and one bad OCR frame do not create extra source text`() {
        val assembler = DialogueSubtitleAssembler()
        assembler.observe(listOf(row("私")), true, 0)
        assembler.observe(listOf(row("私はククルカン。")), true, 400)
        assembler.observe(listOf(row("読めないノイズ")), true, 800)
        assertEquals(listOf("私はククルカン。"), assembler.observe(listOf(row("私はククルカン。")), true, 1_200).map { it.source })
        assertTrue(assembler.observe(listOf(row("私はククルカン。")), true, 3_000).isEmpty())
    }

    @Test fun `recording timeline emits six logical utterances in order and drains on return to menu`() {
        val assembler = DialogueSubtitleAssembler()
        val output = mutableListOf<String>()
        fun frame(at: Long, top: String, bottom: String = "") {
            output += assembler.observe(listOfNotNull(top.takeIf { it.isNotEmpty() }?.let { row(it) },
                bottom.takeIf { it.isNotEmpty() }?.let { row(it, true) }), true, at).map { it.source }
        }
        val a = "はじめまして、汎人類史のお友達！"
        val b = "私はククルカン。"
        val c = "樹海の水と風と大地の守護者にして、えーーと……"
        val d = "なんだっけ？"
        val e = "ま、いいですよね、細かいことは。"
        val f = "これからたくさん、貴方たちの文化を学ばせて"
        frame(1_000, a); frame(1_800, a)
        frame(5_200, b); frame(5_800, b, c); frame(6_600, b, c)
        frame(14_200, c, d); frame(15_000, c, d)
        frame(16_500, d, e); frame(17_500, d, e)
        frame(20_800, e, f); frame(21_400, f, "いただき")
        frame(22_000, f, "いただきます♡"); frame(22_800, f, "いただきます♡")
        output += assembler.observe(emptyList(), false, 27_300).map { it.source }
        output += assembler.observe(emptyList(), false, 28_100).map { it.source }
        assertEquals(listOf(a, b, c, d, e, f + "いただきます♡"), output)
        assertTrue(assembler.playbackEnded)
    }

    private fun row(text: String, lower: Boolean = false) =
        DialogueSubtitleRow(text, if (lower) 130f else 50f, if (lower) 172f else 92f, 0.97f)

    @Test fun `typing prefix is not submitted as a separate utterance`() {
        val assembler = DialogueSubtitleAssembler()
        assertTrue(assembler.observe(listOf(row("私はク")), true, 0).isEmpty())
        assertTrue(assembler.observe(listOf(row("私はククルカン。")), true, 400).isEmpty())
        assertEquals(listOf("私はククルカン。"), assembler.observe(listOf(row("私はククルカン。")), true, 1_000).map { it.source })
        assertTrue(assembler.observe(listOf(row("私はククルカン。")), true, 1_800).isEmpty())
    }

    @Test fun `lower row carried upward is not repeated and wrapped sentence is joined`() {
        val assembler = DialogueSubtitleAssembler()
        val first = row("ま、いいですよね、細かいことは。")
        assembler.observe(listOf(first), true, 0)
        assertEquals(1, assembler.observe(listOf(first), true, 800).size)
        val half = "これからたくさん、貴方たちの文化を学ばせて"
        assembler.observe(listOf(first, row(half, true)), true, 1_000)
        assertTrue(assembler.observe(listOf(row(half)), true, 1_400).isEmpty())
        assembler.observe(listOf(row(half), row("いただきます♡", true)), true, 1_600)
        assertEquals(listOf(half + "いただきます♡"), assembler.observe(
            listOf(row(half), row("いただきます♡", true)), true, 2_400
        ).map { it.source })
    }

    @Test fun `same phrase can occur twice including simultaneously on successive rows`() {
        val assembler = DialogueSubtitleAssembler()
        assembler.observe(listOf(row("なんだっけ？")), true, 0)
        val first = assembler.observe(listOf(row("なんだっけ？")), true, 800).single()
        assembler.observe(listOf(row("なんだっけ？"), row("なんだっけ？", true)), true, 1_000)
        val second = assembler.observe(listOf(row("なんだっけ？"), row("なんだっけ？", true)), true, 1_800).single()
        assertEquals(first.source, second.source)
        assertTrue(second.id > first.id)
    }

    @Test fun `failed observation is not playback end and panel exit flushes confirmed fragments`() {
        val assembler = DialogueSubtitleAssembler()
        assembler.observe(listOf(row("えーーと")), true, 0)
        assembler.observe(listOf(row("えーーと")), true, 400)
        assembler.observe(emptyList(), false, 500)
        assembler.observationUnavailable()
        assertTrue(assembler.observe(emptyList(), false, 1_200).isEmpty())
        assertEquals(listOf("えーーと"), assembler.observe(emptyList(), false, 2_000).map { it.source })
        assertTrue(assembler.playbackEnded)
        assertTrue(assembler.observe(emptyList(), false, 2_800).isEmpty())
    }

    @Test fun `blank between repeated occurrences does not permanently suppress text`() {
        val assembler = DialogueSubtitleAssembler()
        assembler.observe(listOf(row("はい。")), true, 0)
        assembler.observe(listOf(row("はい。")), true, 800)
        assembler.observe(emptyList(), true, 1_000)
        assembler.observe(emptyList(), true, 1_800)
        assembler.observe(listOf(row("はい。")), true, 2_000)
        assertEquals(listOf("はい。"), assembler.observe(listOf(row("はい。")), true, 2_800).map { it.source })
    }

    @Test fun `panel exit emits ending identities once including a not yet confirmed final row`() {
        val assembler = DialogueSubtitleAssembler()
        assembler.observe(listOf(row("最初。")), true, 0)
        assembler.observe(listOf(row("最初。")), true, 800)
        assembler.observe(listOf(row("最後。", true)), true, 1_000)
        val final = assembler.observedSnapshot
        assertTrue(final != assembler.visibleSnapshot)
        assertEquals(1, final.single().slot)
        assembler.observe(emptyList(), false, 1_200)
        assertNull(assembler.endingSnapshot)
        val events = assembler.observe(emptyList(), false, 1_800)
        assertEquals(final, assembler.endingSnapshot)
        assertEquals(final.map { it.id }, events.flatMap { it.rows }.map { it.id })
        assertEquals(emptyList(), assembler.visibleSnapshot)
        assertTrue(assembler.observedSnapshot.isEmpty())
        assembler.observe(emptyList(), false, 2_400)
        assertNull(assembler.endingSnapshot)
        assembler.observe(emptyList(), false, 3_000)
        assertNull(assembler.endingSnapshot)
    }

    @Test fun `confirmed blank captures ending once and later panel exit does not restart it`() {
        val assembler = DialogueSubtitleAssembler()
        assembler.observe(listOf(row("はい。")), true, 0)
        assembler.observe(listOf(row("はい。")), true, 800)
        val final = assembler.visibleSnapshot
        assembler.observe(emptyList(), true, 1_000)
        assertNull(assembler.endingSnapshot)
        assembler.observe(emptyList(), true, 1_600)
        assertEquals(final, assembler.endingSnapshot)
        assembler.observe(emptyList(), true, 1_800)
        assertNull(assembler.endingSnapshot)
        assembler.observe(emptyList(), false, 2_000)
        assembler.observe(emptyList(), false, 2_600)
        assertNull(assembler.endingSnapshot)
    }

    @Test fun `ending metadata does not promote weak OCR or add translation requests`() {
        val assembler = DialogueSubtitleAssembler()
        assembler.observe(listOf(row("不確か。").copy(confidence = 0.6f)), true, 0)
        val final = assembler.observedSnapshot
        assembler.observe(emptyList(), false, 400)
        assertTrue(assembler.observe(emptyList(), false, 1_000).isEmpty())
        assertEquals(final, assembler.endingSnapshot)
        assembler.observe(emptyList(), false, 1_600)
        assertTrue(assembler.observe(emptyList(), false, 2_200).isEmpty())
        assertNull(assembler.endingSnapshot)
    }

    @Test fun `unknown observations never declare an ending and clear removes old ending identities`() {
        val assembler = DialogueSubtitleAssembler()
        assembler.observe(listOf(row("はい。")), true, 0)
        assembler.observe(listOf(row("はい。")), true, 800)
        val first = assembler.observedSnapshot.single().id
        assembler.observationUnavailable()
        assertNull(assembler.endingSnapshot)
        assembler.observe(emptyList(), false, 1_000)
        assembler.observe(emptyList(), false, 1_600)
        assertEquals(first, assembler.endingSnapshot!!.single().id)
        assembler.requireFreshSnapshot()
        assertNull(assembler.endingSnapshot)
        assembler.clear()
        assertNull(assembler.endingSnapshot)
        assembler.observe(listOf(row("はい。")), true, 2_000)
        assertTrue(assembler.observedSnapshot.single().id > first)
    }
}
