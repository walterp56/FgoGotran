package com.fgogotran.story

import com.fgogotran.overlay.DialogueRenderTextPolicy
import com.fgogotran.overlay.FgoReferenceRect
import com.fgogotran.overlay.FgoViewportGeometry
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AutoDialogueHandoffPolicyTest {
    private val crop = FgoReferenceRect(316, 833, 2015, 1052)
    private val main = FgoReferenceRect(345, 872, 1320, 932)
    private val row = DialogueRenderTextPolicy.LineBounds(main.top, main.bottom)

    @Test
    fun `logged single dialogue row is eligible without the speaker concentration score`() {
        assertTrue(accept(text = "今日の日付を思い出していただければ、"))
    }

    @Test
    fun `narration needs no speaker name`() {
        assertTrue(accept(text = "少し未来のお話。"))
    }

    @Test
    fun `ruby boxes do not create another main row`() {
        val ruby = FgoReferenceRect(620, 850, 690, 868)
        assertTrue(accept(
            text = "これは私〈わたくし〉と重なって、",
            ocrBounds = listOf(ruby, main)
        ))
    }

    @Test
    fun `emphasis above an already cleaned main row does not increase its row count`() {
        val emphasis = FgoReferenceRect(350, 850, 500, 860)
        assertTrue(accept(text = "うるさい", ocrBounds = listOf(emphasis, main)))
    }

    @Test
    fun `same-row OCR fragments count as one visual row even with source newlines`() {
        val fragments = listOf(
            FgoReferenceRect(345, 870, 400, 934),
            FgoReferenceRect(415, 871, 900, 929),
            FgoReferenceRect(915, 873, 1320, 930)
        )
        assertTrue(accept(
            text = "！！\n『霧の都にようこそ』\nいいから早く！",
            ocrBounds = fragments,
            mainRows = fragments.map { DialogueRenderTextPolicy.LineBounds(it.top, it.bottom) }
        ))
    }

    @Test
    fun `accepted punctuation-only dialogue does not require Japanese words`() {
        listOf("……", "───", "……。", "！！").forEach { text ->
            assertTrue(accept(text = text), text)
        }
    }

    @Test
    fun `a thin dash-only row remains a valid row`() {
        val dash = FgoReferenceRect(350, 898, 510, 904)
        assertTrue(accept(
            text = "───",
            ocrBounds = listOf(dash),
            mainRows = listOf(DialogueRenderTextPolicy.LineBounds(dash.top, dash.bottom))
        ))
    }

    @Test
    fun `missing strict marker including fallback-only completion cannot use the exception`() {
        assertFalse(accept(strictComplete = false))
    }

    @Test
    fun `choice or mixed scenes cannot use the dialogue exception`() {
        assertFalse(accept(hasChoices = true))
    }

    @Test
    fun `name-only and empty sources are rejected`() {
        listOf(null, "", " \n\t").forEach { assertFalse(accept(text = it)) }
        assertFalse(accept(ocrBounds = emptyList(), mainRows = emptyList()))
    }

    @Test
    fun `a source newline does not disguise two physical dialogue rows`() {
        val second = FgoReferenceRect(345, 958, 1320, 1018)
        assertFalse(accept(
            text = "第一行と第二行",
            ocrBounds = listOf(main, second),
            mainRows = listOf(row, DialogueRenderTextPolicy.LineBounds(second.top, second.bottom))
        ))
    }

    @Test
    fun `missing main-row evidence does not fall back to text newline counting`() {
        assertFalse(accept(mainRows = emptyList()))
    }

    @Test
    fun `invalid main-row bounds cannot be silently dropped to produce one row`() {
        assertFalse(accept(mainRows = listOf(row, DialogueRenderTextPolicy.LineBounds(900, 900))))
    }

    @Test
    fun `out-of-crop main-row evidence is rejected`() {
        assertFalse(accept(mainRows = listOf(DialogueRenderTextPolicy.LineBounds(832, 900))))
        assertFalse(accept(mainRows = listOf(DialogueRenderTextPolicy.LineBounds(1000, 1053))))
    }

    @Test
    fun `empty OCR evidence and invalid OCR rectangles are rejected`() {
        assertFalse(accept(ocrBounds = emptyList()))
        assertFalse(accept(ocrBounds = listOf(main.copy(right = main.left))))
        assertFalse(accept(ocrBounds = listOf(main.copy(bottom = main.top))))
    }

    @Test
    fun `OCR boxes outside any crop edge cannot use the exception`() {
        listOf(
            main.copy(left = crop.left - 1),
            main.copy(top = crop.top - 1),
            main.copy(right = crop.right + 1),
            main.copy(bottom = crop.bottom + 1)
        ).forEach { assertFalse(accept(ocrBounds = listOf(it)), it.toString()) }
    }

    @Test
    fun `render bounds cannot stand in for the narrower OCR crop`() {
        assertFalse(accept(ocrBounds = listOf(main.copy(left = 245))))
    }

    @Test
    fun `OCR punctuation touching the crop edge is not discarded`() {
        assertTrue(accept(ocrBounds = listOf(main.copy(left = crop.left, right = crop.right))))
    }

    @Test
    fun `zero or reversed crop dimensions are rejected`() {
        assertFalse(accept(dialogueCrop = crop.copy(right = crop.left)))
        assertFalse(accept(dialogueCrop = crop.copy(bottom = crop.top)))
        assertFalse(accept(dialogueCrop = crop.copy(right = crop.left - 1)))
    }

    @Test
    fun `the same single-row rule works at scaled and letterboxed viewport coordinates`() {
        for ((width, height) in listOf(2340 to 1080, 1920 to 1080, 1280 to 720, 1600 to 1200)) {
            val referenceCrop = FgoReferenceRect(106, 833, 1805, 1052)
            val referenceMain = FgoReferenceRect(135, 872, 1110, 932)
            val scaledCrop = FgoViewportGeometry.map(referenceCrop, width, height)
            val scaledMain = FgoViewportGeometry.map(referenceMain, width, height)
            assertTrue(accept(
                dialogueCrop = scaledCrop,
                ocrBounds = listOf(scaledMain),
                mainRows = listOf(DialogueRenderTextPolicy.LineBounds(scaledMain.top, scaledMain.bottom))
            ), "$width x $height")
        }
    }

    private fun accept(
        strictComplete: Boolean = true,
        hasChoices: Boolean = false,
        text: String? = "一行の台詞です。",
        dialogueCrop: FgoReferenceRect = crop,
        ocrBounds: List<FgoReferenceRect> = listOf(main),
        mainRows: List<DialogueRenderTextPolicy.LineBounds> = listOf(row)
    ): Boolean = AutoDialogueHandoffPolicy.canAcceptSingleRow(
        strictDialogueComplete = strictComplete,
        hasChoices = hasChoices,
        dialogueText = text,
        dialogueCrop = dialogueCrop,
        dialogueOcrBounds = ocrBounds,
        mainLineBounds = mainRows
    )
}
