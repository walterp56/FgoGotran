package com.fgogotran.accessibility

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Rect
import com.fgogotran.ocr.OcrEngineId
import com.fgogotran.ocr.OcrTextLine
import com.fgogotran.overlay.ClassifiedRegion
import com.fgogotran.overlay.TextRegion
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.*

/** Exercises the service's actual sampler without starting its lifecycle, OCR or network work. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StoryTextColorSamplingTest {
    private val service by lazy { Robolectric.buildService(FgoAccessibilityService::class.java).get() }
    private val blue = 0xff6079f0.toInt()
    private val purple = 0xff8753d1.toInt()

    private fun sample(bitmap: Bitmap, kind: TextRegion, engine: OcrEngineId = OcrEngineId.PADDLE_OCR,
                       trustedNameBounds: Rect? = null,
                       lineBounds: Rect = Rect(0, 0, bitmap.width, bitmap.height)): Int? {
        val bounds = Rect(0, 0, bitmap.width, bitmap.height)
        val region = ClassifiedRegion(kind, listOf(OcrTextLine("黒い影", lineBounds, 1f)), bounds,
            engine, sourceNameTextBounds = trustedNameBounds)
        val method = FgoAccessibilityService::class.java.getDeclaredMethod("renderTextColorForRegion",
            Bitmap::class.java, ClassifiedRegion::class.java).apply { isAccessible = true }
        return method.invoke(service, bitmap, region) as Int?
    }

    private fun fixture(name: String): Bitmap = javaClass.getResourceAsStream("/ocr/jp-blue-$name.png")!!.use {
        BitmapFactory.decodeStream(it)!!.apply { density = Bitmap.DENSITY_NONE }
    }

    @Test fun `original blue name and dialogue crops select blue for either OCR engine`() {
        for ((name, kind) in listOf("name" to TextRegion.NAME_LABEL, "dialogue" to TextRegion.DIALOGUE_BOX)) {
            val bitmap = fixture(name)
            try {
                for (engine in listOf(OcrEngineId.PADDLE_OCR, OcrEngineId.ML_KIT)) {
                    assertEquals(blue, sample(bitmap, kind, engine), "$name/$engine")
                }
            } finally { bitmap.recycle() }
        }
    }

    @Test fun `real empty blue plates do not produce a foreground colour`() {
        for (name in listOf("nameplate-background", "dialogue-background")) {
            val bitmap = fixture(name)
            try {
                for (kind in listOf(TextRegion.NAME_LABEL, TextRegion.DIALOGUE_BOX)) {
                    assertNull(sample(bitmap, kind), "$name/$kind")
                }
            } finally { bitmap.recycle() }
        }
    }

    @Test fun `eight votes are still required and choice scope cannot adopt blue`() {
        val bitmap = Bitmap.createBitmap(64, 32, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(0xff1b304e.toInt())
            repeat(7) { bitmap.setPixel(2 + it * 2, 2, blue) }
            assertNull(sample(bitmap, TextRegion.NAME_LABEL))
            assertNull(sample(bitmap, TextRegion.DIALOGUE_BOX))
            bitmap.setPixel(16, 2, blue)
            assertEquals(blue, sample(bitmap, TextRegion.NAME_LABEL))
            assertEquals(blue, sample(bitmap, TextRegion.DIALOGUE_BOX))
            assertNull(sample(bitmap, TextRegion.CHOICE_BUTTON))
        } finally { bitmap.recycle() }
    }

    @Test fun `name winner ratio remains one point five for blue versus purple`() {
        val bitmap = Bitmap.createBitmap(64, 32, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(0xff1b304e.toInt())
            repeat(11) { bitmap.setPixel(2 + it * 2, 2, blue) }
            repeat(8) { bitmap.setPixel(2 + it * 2, 4, purple) }
            assertEquals(Color.rgb(245, 245, 240), sample(bitmap, TextRegion.NAME_LABEL))
            bitmap.setPixel(24, 2, blue)
            assertEquals(blue, sample(bitmap, TextRegion.NAME_LABEL))
        } finally { bitmap.recycle() }
    }

    /**
     * The fast Story path reports the glyph box of a choice panel. The colour vote runs inside
     * that box, so a panel-wide box lets artwork showing through the translucent bar flip a
     * white choice to red: the two assertions below are the same frame, only the box differs.
     */
    @Test fun `a choice colour follows the glyph box not the whole panel`() {
        val bitmap = Bitmap.createBitmap(400, 140, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(0xff101010.toInt())
            for (y in 50 until 90) {
                for (x in 120 until 260) bitmap.setPixel(x, y, Color.rgb(245, 245, 240))
            }
            // Warm scene art under the bar, well outside the glyphs.
            for (y in 10 until 30) {
                for (x in 300 until 330) bitmap.setPixel(x, y, Color.rgb(220, 20, 20))
            }
            assertEquals(
                Color.rgb(245, 245, 240),
                sample(bitmap, TextRegion.CHOICE_BUTTON, lineBounds = Rect(118, 48, 262, 92))
            )
            assertEquals(
                Color.rgb(220, 0, 0),
                sample(bitmap, TextRegion.CHOICE_BUTTON, lineBounds = Rect(0, 0, 400, 140))
            )
        } finally { bitmap.recycle() }
    }

    @Test fun `trusted name glyph bounds exclude unrelated coloured artwork`() {
        val bitmap = Bitmap.createBitmap(128, 64, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(0xff1b304e.toInt())
            for (y in 0 until 64) for (x in 64 until 128) bitmap.setPixel(x, y, purple)
            repeat(8) { bitmap.setPixel(2 + it * 2, 2, blue) }
            assertEquals(blue, sample(bitmap, TextRegion.NAME_LABEL, trustedNameBounds = Rect(0, 0, 32, 32)))
            assertEquals(purple, sample(bitmap, TextRegion.NAME_LABEL))
        } finally { bitmap.recycle() }
    }

    @Test fun `blue screenshot name glyphs can be measured without covering the whole plate`() {
        val bitmap = fixture("name")
        try {
            val method = FgoAccessibilityService::class.java.getDeclaredMethod("measureNameGlyphBounds",
                Bitmap::class.java, Rect::class.java, String::class.java).apply { isAccessible = true }
            val measured = assertNotNull(method.invoke(service, bitmap, Rect(0, 0, bitmap.width, bitmap.height), "黒い影"))
            val bounds = measured.javaClass.getDeclaredField("bounds").apply { isAccessible = true }.get(measured) as Rect
            assertTrue(bounds.width() > 0 && bounds.height() > 0)
            assertTrue(bounds.left > 0 && bounds.right < bitmap.width)
        } finally { bitmap.recycle() }
    }
}
