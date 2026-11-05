package com.fgogotran.ui.overlay

import kotlin.test.Test
import kotlin.test.assertEquals

class HistorySpeakerDialogueFormatterTest {
    @Test
    fun `adds one outer speech pair around adjacent inner quotes`() {
        assertEquals(
            "「『擁有良好的寶具』『何等愚蠢的行為』『但竟是無形』」",
            render("『擁有良好的寶具』『何等愚蠢的行為』『但竟是無形』")
        )
    }

    @Test
    fun `adds one outer speech pair around ordinary speaker dialogue`() {
        assertEquals("「內容」", render("內容"))
    }

    @Test
    fun `does not duplicate an existing complete speech pair`() {
        assertEquals("「內容」", render("「內容」"))
        assertEquals("「內容」。", render("「內容」。"))
    }

    @Test
    fun `adjacent speech fragments are enclosed as one complete dialogue`() {
        assertEquals("「「前半」「後半」」", render("「前半」「後半」"))
    }

    @Test
    fun `other quote styles remain inside the fixed speech pair`() {
        assertEquals("「『內心話』」", render("『內心話』"))
        assertEquals("「“內容”」", render("“內容”"))
    }

    private fun render(text: String): String {
        val prepared = HistorySpeakerDialogueFormatter.prepare(text)
        return prepared.openingQuote + prepared.body + prepared.closingQuote + prepared.trailingPunctuation
    }
}
