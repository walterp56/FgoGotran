package com.fgogotran.ui.component

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.platform.app.InstrumentationRegistry
import com.fgogotran.overlay.DialoguePlaybackSettings
import com.fgogotran.overlay.DialogueTextSpeed
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import kotlin.math.abs

/** Actual font/bitmap checks of the settings preview; no OCR, service or API requests. */
class DialoguePlaybackPreviewTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun realRendererKeepsTwoBodyRowsAndRubyToggleDoesNotMoveThem() {
        for (locale in listOf("zh-Hans", "zh-Hant", "en")) for (bilingual in listOf(false, true)) {
            val example = DialoguePlaybackExample.forTarget(locale)
            DialoguePlaybackPreviewScene.create(context, example, locale, bilingual).use { scene ->
                val withRuby = scene.frame(true)
                val withoutRuby = scene.frame(false)
                val on = withRuby.panels.single().rows
                val off = withoutRuby.panels.single().rows
                assertEquals("two explicit rows: $locale/$bilingual", 2, on.size)
                assertEquals(2, off.size)
                assertEquals(if (locale == "en") 2 else 3, on.sumOf { it.readings.size })
                assertTrue(off.all { it.readings.isEmpty() })
                assertEquals(withRuby.timeline.text, withoutRuby.timeline.text)
                assertEquals(withRuby.timeline.durationNanos, withoutRuby.timeline.durationNanos)
                val slow = scene.timeline(DialoguePlaybackSettings(DialogueTextSpeed.HALF, 3))
                val instant = scene.timeline(DialoguePlaybackSettings())
                assertEquals(withRuby.timeline.text, slow.text)
                assertTrue(slow.durationNanos > instant.durationNanos)
                assertEquals(0L, instant.durationNanos)
                assertSame("changing playback timing reuses pixels", withRuby.bitmap, scene.frame(true).bitmap)
                assertFalse(withRuby.timeline.text.contains('〈'))
                assertEquals(if (bilingual) 2 else 0, withRuby.panels.single().originalRows.size)
                for ((a, b) in withRuby.panels.single().originalRows.zip(withoutRuby.panels.single().originalRows)) {
                    assertEquals(a.text, b.text)
                    assertEquals(a.layer.left, b.layer.left)
                    assertEquals(a.layer.top, b.layer.top)
                    assertTrue("Japanese layout is stable across ruby toggle", a.layer.bitmap.sameAs(b.layer.bitmap))
                }
                for ((a, b) in on.zip(off)) {
                    assertEquals(a.text, b.text)
                    assertEquals(a.x, b.x, 0f)
                    assertEquals(a.layer.left, b.layer.left)
                    assertEquals("row top: $locale/$bilingual", a.layer.top, b.layer.top)
                    assertArrayEquals(a.advances, b.advances, 0f)
                    assertTrue("identical body ink: $locale/$bilingual", a.layer.bitmap.sameAs(b.layer.bitmap))
                }
                for (frame in listOf(withRuby, withoutRuby)) {
                    assertTrue(scene.crop.contains(frame.panels.single().bounds))
                    for (row in frame.panels.single().rows) for (reading in row.readings) {
                        assertTrue(scene.crop.contains(reading.layer.left, reading.layer.top))
                        assertTrue(scene.crop.contains(reading.layer.left + reading.layer.bitmap.width - 1,
                            reading.layer.top + reading.layer.bitmap.height - 1))
                    }
                }
                val blankOn = render(scene, true, 0)
                val blankOff = render(scene, false, 0)
                val completeOn = render(scene, true, withRuby.timeline.text.length)
                val completeOff = render(scene, false, withoutRuby.timeline.text.length)
                val partialOn = render(scene, true, withRuby.panels.single().rows.first().ends[3])
                try {
                    val folder = File(context.cacheDir, "dialogue-preview").apply { mkdirs() }
                    for ((name, image) in listOf("ruby" to completeOn, "plain" to completeOff,
                        "blank-ruby" to blankOn, "blank-plain" to blankOff, "partial" to partialOn)) {
                        File(folder, "$locale-$bilingual-$name.png").outputStream().use {
                            image.compress(Bitmap.CompressFormat.PNG, 100, it)
                        }
                    }
                    assertBackgroundAndOriginalStable(blankOn, blankOff, "$locale/$bilingual")
                    assertFalse("ruby is actually visible", completeOn.sameAs(completeOff))
                } finally {
                    blankOn.recycle(); blankOff.recycle(); completeOn.recycle(); completeOff.recycle(); partialOn.recycle()
                }
            }
        }
    }

    private fun render(scene: DialoguePlaybackPreviewScene, ruby: Boolean, end: Int): Bitmap =
        Bitmap.createBitmap(scene.crop.width(), scene.crop.height(), Bitmap.Config.ARGB_8888).also {
            it.eraseColor(Color.rgb(27, 48, 78))
            scene.draw(Canvas(it), ruby, end)
        }

    private fun assertBackgroundAndOriginalStable(a: Bitmap, b: Bitmap, label: String) {
        fun panelBlue(pixel: Int): Boolean = Color.alpha(pixel) == 255 &&
            abs(Color.red(pixel) - 27) <= 1 && abs(Color.green(pixel) - 48) <= 1 &&
            abs(Color.blue(pixel) - 78) <= 1
        for (y in 0 until a.height) for (x in 0 until a.width) {
            val on = a.getPixel(x, y)
            val off = b.getPixel(x, y)
            // Compositing antialiased blue corners can round by one channel value. All Japanese
            // glyphs must still match exactly; the panel must remain fully opaque in both states.
            if (on != off && !(panelBlue(on) && panelBlue(off))) {
                assertEquals("Japanese/blank panel: $label at $x,$y", on, off)
            }
        }
    }
}
