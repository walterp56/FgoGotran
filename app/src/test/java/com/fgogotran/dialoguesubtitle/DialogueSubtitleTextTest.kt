package com.fgogotran.dialoguesubtitle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DialogueSubtitleTextTest {
    @Test fun `expressive symbols are displayable without an API call`() {
        assertTrue(DialogueSubtitleText.isExpressivePunctuationOnly("♡……♪！？"))
        kotlin.test.assertFalse(DialogueSubtitleText.isExpressivePunctuationOnly("いただきます♡"))
    }

    private fun line(text: String, y: Float, x: Float = 0f) =
        DialogueSubtitleTextLine(text, x, y, x + 400, y + 42, 0.95f)

    @Test fun `text boxes are grouped by geometry and keep hearts`() {
        val rows = DialogueSubtitleText.extract(listOf(line("いただきます♡", 130f), line("文化を学ばせて", 50f)))
        assertEquals(listOf("文化を学ばせて", "いただきます♡"), rows.map { it.text })
    }

    @Test fun `voice list with three or more rows is rejected`() {
        assertTrue(DialogueSubtitleText.extract(listOf(line("召喚", 0f), line("召喚２", 70f), line("レベルアップ", 140f))).isEmpty())
    }
}
