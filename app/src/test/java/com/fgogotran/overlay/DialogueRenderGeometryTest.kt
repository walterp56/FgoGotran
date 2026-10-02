package com.fgogotran.overlay

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DialogueRenderGeometryTest {
    private val panel = DialogueRect(245f, 830f, 2015f, 1052f)
    private val textArea = DialogueRenderGeometry.textArea(panel, 1f)
    private val source = DialogueRect(338f, 872f, 1320f, 932f)

    @Test
    fun `logged trailing dash case uses content width instead of full row`() {
        val tail = "あ、いたいた。おーい、マス───"
        val clear = clearBox(tail, translatedWidth = 1007f)
        assertEquals(1376f, clear.right) // 345 text left + 1007 text width + 24 cover inset.
        assertTrue(clear.right < textArea.right)
        val oldFullRowClear = DialogueRenderGeometry.clearBox(
            panel, textArea, source, 1007f, 53f, hasCountdownTail = true, scale = 1f, englishTarget = false
        )
        assertEquals(1986f, oldFullRowClear.right)
        assertEquals(oldFullRowClear.left, clear.left)
        assertEquals(oldFullRowClear.top, clear.top)
        assertEquals(oldFullRowClear.bottom, clear.bottom)
    }

    @Test
    fun `original Japanese including its dash still determines cover when longer`() {
        val originalWithDash = source.copy(right = 1600f)
        val clear = clearBox("本文───", translatedWidth = 400f, original = originalWithDash)
        assertEquals(1634f, clear.right)
        assertTrue(clear.right > originalWithDash.right)
    }

    @Test
    fun `translation longer than Japanese still has normal right cover padding`() {
        val clear = clearBox("本文───", translatedWidth = 1200f)
        assertEquals(1569f, clear.right)
    }

    @Test
    fun `dash-only row uses original stroke bounds without full-row stretch`() {
        val dash = DialogueRect(350f, 892f, 510f, 898f)
        val clear = clearBox("───", translatedWidth = 159f, original = dash)
        assertEquals(544f, clear.right)
        assertTrue(clear.right > dash.right)
    }

    @Test
    fun `dash forms and normal punctuation do not request full width`() {
        listOf(
            "本文───", "本文——", "本文――", "本文ーー", "本文一一", "本文--",
            "本文－－", "本文━━", "本文───   ", "───本文", "前───後", "……", "本文……。", "本文！", ""
        ).forEach { assertFalse(DialogueRenderGeometry.isCountdownTail(it), it) }
    }

    @Test
    fun `existing spaced countdown safeguard remains unchanged`() {
        listOf("3 2 1", "3 2 1───", "３　２　１ーー", "開始、5\t4\t3\t2\t1 ――  ")
            .forEach { assertTrue(DialogueRenderGeometry.isCountdownTail(it), it) }
        val clear = clearBox("3 2 1───", translatedWidth = 250f)
        assertEquals(textArea.right, clear.right)
    }

    @Test
    fun `ordinary numbers do not trigger countdown cover`() {
        listOf("3 2", "321", "3,2,1", "2026年", "3 2 1です", "第1───")
            .forEach { assertFalse(DialogueRenderGeometry.isCountdownTail(it), it) }
    }

    @Test
    fun `ordinary dialogue geometry and fixed text area are unchanged`() {
        assertEquals(DialogueRect(345f, 878f, 1986f, 1040f), textArea)
        assertEquals(DialogueRect(308f, 842f, 1376f, 950f), clearBox("普通の本文", 1007f))
        assertEquals(clearBox("普通の本文", 1007f), clearBox("普通の本文───", 1007f))
    }

    @Test
    fun `English keeps its established anchor and ruby cover`() {
        val clear = clearBox("本文───", 1007f, english = true)
        assertEquals(DialogueRect(331f, 852f, 1376f, 950f), clear)
        assertEquals(clearBox("普通の本文", 1007f, english = true), clear)
    }

    @Test
    fun `no source bounds still covers all translated glyphs`() {
        assertEquals(1376f, clearBox("本文───", 1007f, original = null).right)
    }

    @Test
    fun `genuinely long original text may still reach the panel boundary`() {
        val clear = clearBox("本文───", 1007f, original = source.copy(right = 2015f))
        assertEquals(panel.right, clear.right)
    }

    @Test
    fun `scaling preserves the same content-based cover`() {
        val scale = 1.5f
        fun DialogueRect.scaled() = DialogueRect(left * scale, top * scale, right * scale, bottom * scale)
        val scaledPanel = panel.scaled()
        val clear = DialogueRenderGeometry.clearBox(
            scaledPanel, DialogueRenderGeometry.textArea(scaledPanel, scale), source.scaled(),
            1007f * scale, 53f * scale, hasCountdownTail = false, scale = scale, englishTarget = false
        )
        assertEquals(clearBox("本文───", 1007f).scaled(), clear)
    }

    private fun clearBox(
        sourceTail: String,
        translatedWidth: Float,
        original: DialogueRect? = source,
        english: Boolean = false
    ): DialogueRect = DialogueRenderGeometry.clearBox(
        panel, textArea, original, translatedWidth, 53f,
        hasCountdownTail = DialogueRenderGeometry.isCountdownTail(sourceTail), scale = 1f, englishTarget = english
    )
}
