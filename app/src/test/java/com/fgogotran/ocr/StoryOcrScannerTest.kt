package com.fgogotran.ocr

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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

    /**
     * FGO centres the choice text, so the fixed horizontal insets of [choiceBand] cut a long
     * line. Measured on the event screenshot: glyphs 466–1869 in a 430–1900 panel, with the
     * pale blue plate border at 443–460 and 1880–1896.
     */
    @Test
    fun `a centred choice crop follows the glyphs instead of the fixed insets`() {
        val panel = Rect(430, 250, 1900, 385)
        val window = StoryOcrGeometry.choicePlateWindow(2340, 1080, panel)
        val pixels = IntArray(2340 * 1080).apply { fill(Color.BLACK) }
        for (y in 275 until 360) {
            for (x in 443 until 460) pixels[y * 2340 + x] = Color.rgb(137, 170, 189)
            for (x in 1880 until 1896) pixels[y * 2340 + x] = Color.rgb(137, 170, 189)
        }
        for (y in 295 until 340) {
            for (x in 466 until 1870) pixels[y * 2340 + x] = Color.WHITE
        }
        val bitmap = Bitmap.createBitmap(2340, 1080, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, 2340, 0, 0, 2340, 1080)

        val columns = StoryOcrGeometry.choiceTextColumns(bitmap, panel, window)
        assertEquals(IntRange(466, 1869), columns)
        // The fixed band would cut the head and the tail of a line this long.
        assertEquals(Rect(577, 274, 1812, 361), StoryOcrGeometry.choiceBand(panel))
        assertEquals(Rect(460, 274, 1876, 361), StoryOcrGeometry.choiceCrop(panel, window, columns))
        bitmap.recycle()
    }

    /**
     * The slot is wider than the plate, so its margins show the live scene. Measured on the
     * festival screenshot: lanterns at 430–452 and 1888–1900 pass the glyph test, stretched the
     * window to 430–1899, and the line box then covered the lanterns, which voted the white
     * choice red. The plate fill is 451–1889, so those columns are outside the window.
     */
    @Test
    fun `scene pixels beside the plate do not widen the glyph window`() {
        val panel = Rect(430, 250, 1900, 385)
        val window = StoryOcrGeometry.choicePlateWindow(2340, 1080, panel)
        assertEquals(IntRange(455, 1885), window)
        val pixels = IntArray(2340 * 1080).apply { fill(Color.BLACK) }
        for (y in 275 until 360) {
            for (x in 430 until 452) pixels[y * 2340 + x] = Color.rgb(247, 87, 59)
            for (x in 1888 until 1900) pixels[y * 2340 + x] = Color.rgb(247, 87, 59)
        }
        for (y in 295 until 340) {
            for (x in 900 until 1400) pixels[y * 2340 + x] = Color.WHITE
        }
        val bitmap = Bitmap.createBitmap(2340, 1080, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, 2340, 0, 0, 2340, 1080)

        val columns = StoryOcrGeometry.choiceTextColumns(bitmap, panel, window)
        assertEquals(IntRange(900, 1399), columns)
        assertEquals(Rect(894, 274, 1406, 361), StoryOcrGeometry.choiceCrop(panel, window, columns))
        bitmap.recycle()
    }

    @Test
    fun `the plate window follows the story scale`() {
        // A 1280x720 screen maps the same slot to 147-1127 with the plate fill at 161-1119.
        assertEquals(
            IntRange(165, 1115),
            StoryOcrGeometry.choicePlateWindow(1280, 720, Rect(147, 167, 1127, 257))
        )
    }

    @Test
    fun `a choice without glyphs keeps the whole plate fill`() {
        val panel = Rect(430, 250, 1900, 385)
        val window = StoryOcrGeometry.choicePlateWindow(2340, 1080, panel)
        val bitmap = Bitmap.createBitmap(2340, 1080, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.BLACK)
        assertNull(StoryOcrGeometry.choiceTextColumns(bitmap, panel, window))
        assertEquals(Rect(455, 274, 1886, 361), StoryOcrGeometry.choiceCrop(panel, window, null))
        bitmap.recycle()
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

    /**
     * One line may be vertically centred across both bands, so the frame replaces its
     * band box with the glyph box of the whole crop. Two lines keep their own bands.
     */
    @Test
    fun `a single line frame takes the whole crop box`() {
        val band = Rect(106, 881, 1805, 933)
        val line = StoryOcrLine("『ヤタガラス・クラーッシュ』！！！", band, 0.9f, row = 1)
        val glyph = Rect(150, 858, 1700, 977)
        val single = StoryOcrFrame(listOf(line), null)
        assertEquals(glyph, single.withSingleLineBounds(glyph).dialogue.single().bounds)
        assertEquals(band, single.withSingleLineBounds(null).dialogue.single().bounds)

        val secondBand = Rect(106, 967, 1805, 1019)
        val two = StoryOcrFrame(
            listOf(line, line.copy(text = "二行目", bounds = secondBand, row = 2)),
            null
        )
        assertEquals(
            listOf(band, secondBand),
            two.withSingleLineBounds(glyph).dialogue.map { it.bounds }
        )
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
        // The service samples the text colour inside the reported box, so a line carries the
        // glyph box, never the panel.
        val reported = listOf(Rect(367, 274, 1602, 330), Rect(420, 470, 1240, 520))
        val lines = StoryOcrAssembler.choices(
            listOf(StoryBandText("水着を買う", 0.97f), StoryBandText("焼きまんじゅうを食べる", 0.95f)),
            buttons,
            reported
        )
        assertEquals(2, lines?.size)
        assertEquals(reported[1], lines?.get(1)?.bounds)
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
    fun `a name reports the glyph columns, not the plate width`() {
        val bitmap = Bitmap.createBitmap(300, 60, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.BLACK)
        for (y in 20 until 40) {
            for (x in 40 until 140) bitmap.setPixel(x, y, Color.WHITE)
        }
        val rows = StoryOcrGeometry.inkBand(bitmap, Rect(0, 0, 300, 60))
        assertEquals(Rect(0, 20, 300, 40), rows)
        // The colour vote scans this box, so the name must not report the whole plate.
        assertEquals(Rect(38, 20, 142, 40), StoryOcrGeometry.inkBounds(bitmap, rows!!))
        bitmap.recycle()
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

    @Test
    fun `a short line reports a tight box inside its band`() {
        val bitmap = Bitmap.createBitmap(400, 60, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.argb(255, 20, 24, 34))
        // A full-height line that stops well before the band's right edge.
        for (y in 8 until 52) {
            for (x in 40 until 140) bitmap.setPixel(x, y, Color.WHITE)
        }
        val band = Rect(0, 0, 400, 60)
        val ink = StoryOcrGeometry.inkBounds(bitmap, band)
        // The overlay paints its white cover panel from this box, so it must not be the band.
        assertEquals(Rect(38, 6, 142, 54), ink)
        assertEquals(ink, StoryOcrGeometry.recognitionBand(band, ink))
        bitmap.recycle()
    }

    /**
     * A big one-line title is centred across both fixed bands: the screen capture has the
     * glyph at 860–975 while row one ends at 933, so the band box loses the bottom 42 px.
     * The overlay sizes its cover from the reported box, so the crop glyphs must win.
     */
    @Test
    fun `a single line keeps the glyphs of the whole crop`() {
        val bitmap = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(16, 22, 38))
        for (y in 860 until 976) {
            for (x in 200 until 1600) bitmap.setPixel(x, y, Color.WHITE)
        }
        val band = StoryOcrGeometry.dialogueBands(referenceDialogue).first()
        val bandInk = StoryOcrGeometry.inkBounds(bitmap, band)
        val cropInk = StoryOcrGeometry.inkBounds(bitmap, referenceDialogue)
        // The band clips the title at its own bottom edge; the crop keeps the whole glyph.
        assertEquals(933, bandInk?.bottom)
        assertEquals(978, cropInk?.bottom)
        assertTrue(cropInk!!.top < bandInk!!.top)
        bitmap.recycle()
    }

    @Test
    fun `a thin mark keeps the full band for recognition`() {
        val bitmap = Bitmap.createBitmap(400, 60, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.BLACK)
        for (y in 28 until 32) {
            for (x in 40 until 140) bitmap.setPixel(x, y, Color.WHITE)
        }
        val band = Rect(0, 0, 400, 60)
        val ink = StoryOcrGeometry.inkBounds(bitmap, band)
        assertEquals(Rect(38, 26, 142, 34), ink)
        // A mark this thin would be stretched by the recognition resize.
        assertSame(band, StoryOcrGeometry.recognitionBand(band, ink))
        bitmap.recycle()
    }

    @Test
    fun `red shout text still counts as ink`() {
        val bitmap = Bitmap.createBitmap(200, 60, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.argb(255, 18, 22, 30))
        for (y in 20 until 40) {
            for (x in 20 until 60) bitmap.setPixel(x, y, Color.rgb(237, 28, 36))
        }
        assertEquals(Rect(18, 18, 62, 42), StoryOcrGeometry.inkBounds(bitmap, Rect(0, 0, 200, 60)))
        bitmap.recycle()
    }

    @Test
    fun `an empty band has no ink box`() {
        val bitmap = Bitmap.createBitmap(200, 60, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.argb(255, 18, 22, 30))
        assertNull(StoryOcrGeometry.inkBounds(bitmap, Rect(0, 0, 200, 60)))
        bitmap.recycle()
    }

    private fun bands(): List<Rect> = StoryOcrGeometry.dialogueBands(referenceDialogue)
}
