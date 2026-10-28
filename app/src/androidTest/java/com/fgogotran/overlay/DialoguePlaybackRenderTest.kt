package com.fgogotran.overlay

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.view.MotionEvent
import androidx.test.platform.app.InstrumentationRegistry
import com.fgogotran.ocr.OcrEngineId
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Real Android font/ruby/bitmap checks. No OCR, network, service or paid translation calls. */
class DialoguePlaybackRenderTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun region(kind: TextRegion, box: Rect) = ClassifiedRegion(kind, emptyList(), box, OcrEngineId.ML_KIT,
        sourcePlateBounds = if (kind == TextRegion.NAME_LABEL) Rect(box) else null,
        sourceNameTextBounds = if (kind == TextRegion.NAME_LABEL) Rect(90, 755, 380, 815) else null)
    private fun instructions(locale: String, bilingual: Boolean, long: Boolean = false): List<RenderInstruction> {
        val text = when (locale) {
            "en" -> "The Grand Command Spell〈Sirius Light〉.\nWatch me—our story begins here."
            "zh-Hant" -> "我們回到觀星臺〈迦勒底〉，\n故事就從這裡開始。"
            else -> "我们回到观星台〈迦勒底〉，\n故事就从这里开始。"
        }
        return listOf(
            RenderInstruction(region(TextRegion.DIALOGUE_BOX, Rect(35, 830, 1805, 1055)),
                if (long) text.repeat(4) else text, "星見台〈カルデア〉に戻ろう。\nここから物語が始まる。",
                targetLocale = locale, showOriginalText = bilingual),
            RenderInstruction(region(TextRegion.NAME_LABEL, Rect(35, 740, 600, 828)), "Kadoc", targetLocale = locale),
            RenderInstruction(region(TextRegion.CHOICE_BUTTON, Rect(300, 420, 1600, 555)), "Continue", targetLocale = locale)
        )
    }

    @Test fun finalPixelsMatchLegacyAndOtherRegionsNeverAnimate() {
        val renderer = OverlayRenderer(context)
        val source = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
        try {
            for (locale in listOf("zh-Hans", "zh-Hant", "en")) for (bilingual in listOf(false, true)) for (long in listOf(false, true)) {
                val input = instructions(locale, bilingual, long)
                val expected = renderer.render(source, input, 1920, 1080).copy(Bitmap.Config.ARGB_8888, false)
                assertTrue("fixture includes a visible name plate", (expected.getPixel(100, 760) ushr 24) > 0)
                val frame = renderer.prepareDialoguePlayback(source, input, DialoguePlaybackSettings(DialogueTextSpeed.HALF, 3))
                val actual = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
                try {
                    assertTrue("dialogue plan exists: $locale/$bilingual/$long", frame.panels.isNotEmpty())
                    assertTrue("legacy final pixels", frame.bitmap.sameAs(expected))
                    val canvas = Canvas(actual)
                    canvas.drawBitmap(frame.bitmap, 0f, 0f, null)
                    frame.drawReveal(canvas, 1)
                    for (box in listOf(Rect(35, 740, 600, 828), Rect(300, 420, 1600, 555))) {
                        assertRegionEquals(expected, actual, box)
                    }
                    actual.eraseColor(android.graphics.Color.TRANSPARENT)
                    canvas.drawBitmap(frame.bitmap, 0f, 0f, null)
                    frame.drawReveal(canvas, frame.timeline.text.length)
                    assertTrue("completion preserves exact bitmap", actual.sameAs(expected))
                    if (locale == "zh-Hant" && bilingual && !long) {
                        val folder = File(context.cacheDir, "dialogue-playback").apply { mkdirs() }
                        File(folder, "complete.png").outputStream().use { actual.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        actual.eraseColor(android.graphics.Color.TRANSPARENT)
                        canvas.drawBitmap(frame.bitmap, 0f, 0f, null)
                        frame.drawReveal(canvas, 4)
                        File(folder, "partial.png").outputStream().use { actual.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    }
                } finally { actual.recycle(); expected.recycle(); frame.close() }
            }
        } finally { source.recycle(); renderer.releaseBuffers() }
    }

    @Test fun rubyAndJapaneseFollowDialogueReveal() {
        val source = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
        val renderer = OverlayRenderer(context)
        val frame = renderer.prepareDialoguePlayback(source, instructions("zh-Hant", true), DialoguePlaybackSettings(DialogueTextSpeed.HALF))
        val blank = frame.bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val partial = frame.bitmap.copy(Bitmap.Config.ARGB_8888, true)
        try {
            frame.drawReveal(Canvas(blank), 0)
            frame.drawReveal(Canvas(partial), 2)
            var cyan = 0
            var blankCyan = 0
            var partialCyan = 0
            for (y in 830 until 1055) for (x in 35 until 1805) {
                val p = frame.bitmap.getPixel(x, y)
                if ((p ushr 24) == 255 && (p shr 16 and 255) < 120 && (p shr 8 and 255) > 210 && (p and 255) > 210) {
                    cyan++
                }
                if (isCyan(blank.getPixel(x, y))) blankCyan++
                if (isCyan(partial.getPixel(x, y))) partialCyan++
            }
            assertTrue("original text present", cyan > 10)
            assertEquals("Japanese starts hidden", 0, blankCyan)
            assertTrue("Japanese is partially revealed with translation", partialCyan in 1 until cyan)
            val panel = frame.panels.single()
            assertEquals(2, panel.originalRows.size)
            val pairComplete = frame.bitmap.copy(Bitmap.Config.ARGB_8888, true)
            try {
                frame.drawReveal(Canvas(pairComplete), panel.rows.first().text.length)
                assertSourceLayerVisible(pairComplete, panel.originalRows[0].layer)
                assertEquals("second Japanese row waits for its translation", 0,
                    cyanInLayer(pairComplete, panel.originalRows[1].layer))
            } finally { pairComplete.recycle() }
            val row = frame.panels.flatMap { it.rows }.first { it.readings.isNotEmpty() }
            val reading = row.readings.first()
            var hiddenPixels = 0
            for (y in 0 until reading.layer.bitmap.height) for (x in 0 until reading.layer.bitmap.width) {
                if ((reading.layer.bitmap.getPixel(x, y) ushr 24) > 128) {
                    hiddenPixels++
                    assertEquals("ruby is hidden before base", blank.getPixel(x + reading.layer.left, y + reading.layer.top),
                        partial.getPixel(x + reading.layer.left, y + reading.layer.top))
                }
            }
            assertTrue(hiddenPixels > 0)
            frame.drawReveal(Canvas(partial), row.start + reading.end)
            assertFalse("ruby appears with complete base", partial.sameAs(blank))
        } finally { blank.recycle(); partial.recycle(); frame.close(); source.recycle(); renderer.releaseBuffers() }
    }

    @Test fun previousJapaneseRowCannotDarkenSecondRowRuby() {
        val source = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
        val renderer = OverlayRenderer(context)
        val input = instructions("zh-Hant", true).first().copy(
            translatedText = "準備好了。\n觀星臺〈迦勒底〉就在前方。",
            sourceText = "ここで待っていてください。\n星見台〈カルデア〉は前方です。")
        val frame = renderer.prepareDialoguePlayback(source, listOf(input), DialoguePlaybackSettings(DialogueTextSpeed.HALF))
        val partial = frame.bitmap.copy(Bitmap.Config.ARGB_8888, true)
        try {
            val row = frame.panels.single().rows.last()
            val reading = row.readings.single()
            frame.drawReveal(Canvas(partial), row.start + reading.end)
            var solid = 0
            for (y in 0 until reading.layer.bitmap.height) for (x in 0 until reading.layer.bitmap.width) {
                val pixel = reading.layer.bitmap.getPixel(x, y)
                if ((pixel ushr 24) == 255 && (pixel shr 16 and 255) > 235 && (pixel shr 8 and 255) > 235) {
                    solid++
                    assertEquals("ruby remains above the previous row's shadow", pixel,
                        partial.getPixel(reading.layer.left + x, reading.layer.top + y))
                }
            }
            assertTrue(solid > 0)
        } finally { partial.recycle(); frame.close(); source.recycle(); renderer.releaseBuffers() }
    }

    @Test fun unequalBilingualWrappingStillRevealsEveryJapaneseRow() {
        val source = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
        val renderer = OverlayRenderer(context)
        val cases = listOf(
            "We can head back now." to "星見台〈カルデア〉に戻ろう。\nここから物語が始まる。",
            "Ready.\nLet’s go." to "先輩、準備はいいですか？"
        )
        try {
            for ((translated, japanese) in cases) {
                val input = instructions("en", true).first().copy(translatedText = translated, sourceText = japanese)
                val frame = renderer.prepareDialoguePlayback(source, listOf(input), DialoguePlaybackSettings(DialogueTextSpeed.HALF, 3))
                val partial = frame.bitmap.copy(Bitmap.Config.ARGB_8888, true)
                try {
                    val panel = frame.panels.single()
                    assertEquals(translated.lines().size, panel.rows.size)
                    assertEquals(japanese.lines().size, panel.originalRows.size)
                    assertEquals(panel.rows.joinToString("\n") { it.text }, frame.timeline.text)
                    if (panel.rows.size == 2) {
                        frame.drawReveal(Canvas(partial), panel.rows.first().text.length)
                        val original = panel.originalRows.single().layer
                        assertTrue("Japanese spans both translated rows", cyanInLayer(partial, original) in
                            1 until cyanInLayer(frame.bitmap, original))
                    } else {
                        frame.drawReveal(Canvas(partial), frame.timeline.text.length - 1)
                        assertTrue("second Japanese row is not omitted", cyanInLayer(partial, panel.originalRows.last().layer) > 0)
                    }
                } finally { partial.recycle(); frame.close() }
            }
        } finally { source.recycle(); renderer.releaseBuffers() }
    }

    @Test fun actualUnicodeBoundariesAndDialogueOnlyTapCompletion() {
        assertArrayEquals(intArrayOf(1, 6, 8, 9), DialoguePlaybackCharacters.ends("A👩‍🚀e\u0301Z"))
        val inline = "星見台〈カルデア〉へ👩‍🚀。"
        val markerStart = inline.indexOf('〈')
        val markerEnd = inline.indexOf('〉') + 1
        val ends = DialoguePlaybackCharacters.ends(inline, inlineRuby = true)
        assertFalse("inline ruby never reveals half a bracket", ends.any { it in (markerStart + 1) until markerEnd })
        assertTrue(ends.contains(markerEnd))
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val renderer = OverlayRenderer(context)
            val source = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
            val frame = renderer.prepareDialoguePlayback(source, instructions("en", false), DialoguePlaybackSettings(DialogueTextSpeed.HALF))
            val view = DialoguePlaybackView(context)
            try {
                view.layout(0, 0, 1920, 1080)
                view.startPlayback(frame)
                fun tap(x: Float, y: Float): Boolean {
                    val down = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, x, y, 0)
                    val up = MotionEvent.obtain(0, 1, MotionEvent.ACTION_UP, x, y, 0)
                    return try { view.handlePlaybackTouch(down); view.handlePlaybackTouch(up) } finally { down.recycle(); up.recycle() }
                }
                assertFalse("name tap unchanged", tap(200f, 780f))
                assertFalse("choice tap unchanged", tap(500f, 480f))
                assertFalse("LOG/AUTO tap unchanged", tap(1850f, 850f))
                assertTrue("first dialogue tap completes", tap(600f, 900f))
                assertFalse("second tap forwards", tap(600f, 900f))
            } finally { view.clearPlayback(); source.recycle(); renderer.releaseBuffers() }
        }
    }

    @Test fun imageViewDensityAndCaptureRestoreRetainStaticRegions() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val renderer = OverlayRenderer(context)
            val source = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
            val frame = renderer.prepareDialoguePlayback(source, instructions("en", true), DialoguePlaybackSettings(DialogueTextSpeed.HALF))
            // Exercise a density mismatch instead of relying on the phone's current default.
            frame.bitmap.density = 160
            val plain = android.widget.ImageView(context).apply {
                scaleType = android.widget.ImageView.ScaleType.FIT_XY
                setImageBitmap(frame.bitmap)
                layout(0, 0, 1920, 1080)
            }
            val view = DialoguePlaybackView(context).apply {
                scaleType = android.widget.ImageView.ScaleType.FIT_XY
                layout(0, 0, 1920, 1080)
                startPlayback(frame)
                pausePlayback()
            }
            val expected = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
            val actual = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
            try {
                plain.draw(Canvas(expected))
                view.draw(Canvas(actual))
                assertRegionEquals(expected, actual, Rect(35, 740, 600, 828))
                assertRegionEquals(expected, actual, Rect(300, 420, 1600, 555))
                view.resumePlayback()
                val down = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 600f, 900f, 0)
                val up = MotionEvent.obtain(0, 1, MotionEvent.ACTION_UP, 600f, 900f, 0)
                try { view.handlePlaybackTouch(down); assertTrue(view.handlePlaybackTouch(up)) }
                finally { down.recycle(); up.recycle() }
                actual.eraseColor(android.graphics.Color.TRANSPARENT)
                view.draw(Canvas(actual))
                assertTrue("finished native view equals original ImageView even across density", actual.sameAs(expected))
            } finally {
                plain.setImageBitmap(null)
                view.clearPlayback(); expected.recycle(); actual.recycle(); source.recycle(); renderer.releaseBuffers()
            }
        }
    }

    private fun assertRegionEquals(a: Bitmap, b: Bitmap, box: Rect) {
        val left = IntArray(box.width() * box.height())
        val right = IntArray(left.size)
        a.getPixels(left, 0, box.width(), box.left, box.top, box.width(), box.height())
        b.getPixels(right, 0, box.width(), box.left, box.top, box.width(), box.height())
        assertArrayEquals(left, right)
    }

    private fun isCyan(pixel: Int): Boolean = (pixel ushr 24) == 255 &&
        (pixel shr 16 and 255) < 120 && (pixel shr 8 and 255) > 210 && (pixel and 255) > 210

    private fun cyanInLayer(bitmap: Bitmap, layer: DialoguePlaybackFrame.Layer): Int {
        var count = 0
        for (y in layer.top until layer.top + layer.bitmap.height) {
            for (x in layer.left until layer.left + layer.bitmap.width) {
                if (isCyan(bitmap.getPixel(x, y))) count++
            }
        }
        return count
    }

    private fun assertSourceLayerVisible(actual: Bitmap, layer: DialoguePlaybackFrame.Layer) {
        var solidPixels = 0
        for (y in 0 until layer.bitmap.height) {
            for (x in 0 until layer.bitmap.width) {
                val expected = layer.bitmap.getPixel(x, y)
                // Check the actual cached glyph cores, without comparing independently rasterized
                // antialiased edges or the neighbouring translated row's shadow.
                if (isCyan(expected)) {
                    solidPixels++
                    assertEquals("completed Japanese glyph at $x,$y", expected,
                        actual.getPixel(layer.left + x, layer.top + y))
                }
            }
        }
        assertTrue(solidPixels > 10)
    }
}
