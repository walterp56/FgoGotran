package com.fgogotran.ocr

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Fixed story geometry and the acceptance rules of the Story Library OCR.
 *
 * The geometry mirrors `DialogueRubyLayout`: the 219 px dialogue crop keeps its ruby in
 * 15–48 and 98–134, so the base glyphs of each line occupy 48–100 and 134–186.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class StoryOcrScannerTest {

    private val referenceDialogue = Rect(106, 833, 1805, 1052)

    @Test
    fun `dialogue bands follow the reference two row layout`() {
        val bands = StoryOcrGeometry.dialogueBands(referenceDialogue)
        assertEquals(2, bands.size)
        assertEquals(Rect(106, 881, 1805, 933), bands[0])
        assertEquals(Rect(106, 967, 1805, 1019), bands[1])
    }

    @Test
    fun `dialogue bands scale with the screen`() {
        val half = Rect(53, 416, 902, 525)
        val bands = StoryOcrGeometry.dialogueBands(half)
        assertEquals(2, bands.size)
        // Half the reference crop: the same 48–100 and 134–186 rows at half height.
        assertEquals(26, bands[0].height())
        assertEquals(26, bands[1].height())
        assertEquals(440, bands[0].top)
        assertEquals(509, bands[1].bottom)
        assertTrue(bands[0].bottom < bands[1].top)
    }

    @Test
    fun `a collapsed dialogue rect has no bands`() {
        assertTrue(StoryOcrGeometry.dialogueBands(Rect(10, 20, 10, 200)).isEmpty())
        assertTrue(StoryOcrGeometry.dialogueBands(Rect(10, 20, 300, 20)).isEmpty())
    }

    @Test
    fun `a choice band strips the panel border and marker`() {
        val band = StoryOcrGeometry.choiceBand(Rect(220, 250, 1690, 385))
        assertEquals(Rect(367, 274, 1602, 361), band)
    }

    @Test
    fun `the name band keeps only the ink rows`() {
        val bitmap = Bitmap.createBitmap(120, 60, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.BLACK)
        for (y in 20 until 40) {
            for (x in 10 until 110) bitmap.setPixel(x, y, Color.WHITE)
        }
        val band = StoryOcrGeometry.inkBand(bitmap, Rect(0, 0, 120, 60))
        assertEquals(Rect(0, 20, 120, 40), band)
        bitmap.recycle()
    }

    @Test
    fun `an empty name plate has no ink band`() {
        val bitmap = Bitmap.createBitmap(120, 60, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.BLACK)
        assertNull(StoryOcrGeometry.inkBand(bitmap, Rect(0, 0, 120, 60)))
        bitmap.recycle()
    }

    @Test
    fun `two read rows become two dialogue lines`() {
        val rows = StoryOcrAssembler.dialogue(
            listOf(StoryBandText("おはようございます。", 0.95f), StoryBandText("今日もいい天気ですね。", 0.93f)),
            bands()
        )
        assertEquals(listOf(1, 2), rows?.map { it.row })
        assertEquals("おはようございます。", rows?.first()?.text)
    }

    @Test
    fun `one read row is a single line on row one`() {
        val rows = StoryOcrAssembler.dialogue(
            listOf(StoryBandText("おはようございます。", 0.95f), null),
            bands()
        )
        assertEquals(1, rows?.size)
        assertEquals(1, rows?.first()?.row)
    }

    /**
     * A line that straddles both bands is read twice: the top fragment lands in row one and
     * the full line in row two. Keeping both would duplicate the text.
     */
    @Test
    fun `a straddling line collapses into the longer read`() {
        val rows = StoryOcrAssembler.dialogue(
            listOf(StoryBandText("こんに", 0.90f), StoryBandText("こんにちは、マスター。", 0.94f)),
            bands()
        )
        assertEquals(1, rows?.size)
        assertEquals("こんにちは、マスター。", rows?.first()?.text)
        assertEquals(2, rows?.first()?.row)
    }

    @Test
    fun `a weak read asks for the full pipeline`() {
        assertNull(
            StoryOcrAssembler.dialogue(
                listOf(StoryBandText("おはよう。", 0.94f), StoryBandText("###", 0.31f)),
                bands()
            )
        )
    }

    @Test
    fun `no read rows means no fast frame`() {
        assertNull(StoryOcrAssembler.dialogue(listOf(null, null), bands()))
        assertNull(StoryOcrAssembler.dialogue(listOf(StoryBandText("  ", 0.9f), null), bands()))
    }

    @Test
    fun `every choice panel must read`() {
        val buttons = listOf(Rect(220, 250, 1690, 385), Rect(220, 437, 1690, 572))
        val lines = StoryOcrAssembler.choices(
            listOf(StoryBandText("水着を買う", 0.97f), StoryBandText("焼きまんじゅうを食べる", 0.95f)),
            buttons
        )
        assertEquals(2, lines?.size)
        assertEquals(buttons[1], lines?.get(1)?.bounds)
        assertNull(
            StoryOcrAssembler.choices(
                listOf(StoryBandText("水着を買う", 0.97f), StoryBandText("", 0.95f)),
                buttons
            )
        )
        assertNull(
            StoryOcrAssembler.choices(
                listOf(StoryBandText("水着を買う", 0.97f), StoryBandText("焼きまんじゅう", 0.22f)),
                buttons
            )
        )
    }

    @Test
    fun `a name line carries its plate band`() {
        val band = Rect(0, 20, 120, 40)
        val name = StoryOcrAssembler.name(StoryBandText("雑賀孫一", 0.96f), band)
        assertEquals(band, name?.bounds)
        assertEquals("雑賀孫一", name?.text)
        assertNotNull(name)
        assertNull(StoryOcrAssembler.name(StoryBandText("", 0.96f), band))
        assertNull(StoryOcrAssembler.name(StoryBandText("雑賀孫一", 0.2f), band))
        assertNull(StoryOcrAssembler.name(null, band))
    }

    private fun bands(): List<Rect> = StoryOcrGeometry.dialogueBands(referenceDialogue)
}
