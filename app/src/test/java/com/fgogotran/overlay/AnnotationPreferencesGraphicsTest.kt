package com.fgogotran.overlay

import android.graphics.*
import com.fgogotran.ocr.OcrEngineId
import com.fgogotran.text.EmphasisText
import com.fgogotran.ui.component.DialoguePlaybackExample
import com.fgogotran.ui.component.DialoguePlaybackPreviewScene
import java.io.File
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.*

/** Actual renderer/font coverage of the shared display preference; no OCR or API calls. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AnnotationPreferencesGraphicsTest {
    private val root = generateSequence(File(requireNotNull(System.getProperty("user.dir"))).absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }
    private val context get() = RuntimeEnvironment.getApplication()

    @Test fun `Mash previews show ruby and dessert dots without moving either language when toggled`() = withFonts {
        for (locale in listOf("zh-Hans", "zh-Hant", "en")) for (bilingual in listOf(false, true)) {
            val sample = DialoguePlaybackExample.forTarget(locale)
            DialoguePlaybackPreviewScene.create(context, sample, locale, bilingual).use { scene ->
                val on = scene.frame(true)
                val off = scene.frame(false)
                val panel = on.panels.single()
                assertEquals(2, panel.rows.size, "$locale/$bilingual")
                assertEquals(sample.text, on.timeline.text)
                assertEquals(on.timeline.text, off.timeline.text)
                assertEquals(1, panel.rows.first().readings.size, "Lunch ruby")
                assertEquals(if (locale == "en") 1 else 2, panel.rows.last().readings.size, "Dessert dots")
                assertTrue(off.panels.single().rows.all { it.readings.isEmpty() })
                for ((a, b) in panel.rows.zip(off.panels.single().rows)) {
                    assertEquals(a.text, b.text)
                    assertEquals(a.layer.top, b.layer.top, "$locale/$bilingual")
                    assertEquals(a.x, b.x, 0f)
                    assertArrayEquals(a.advances, b.advances)
                    assertTrue(a.layer.bitmap.sameAs(b.layer.bitmap), "Body glyphs must not change")
                }
                assertEquals(if (bilingual) 2 else 0, panel.originalRows.size)
                for ((a, b) in panel.originalRows.zip(off.panels.single().originalRows)) {
                    assertEquals(a.text, b.text)
                    assertEquals(a.layer.top, b.layer.top)
                    assertTrue(a.layer.bitmap.sameAs(b.layer.bitmap), "Japanese stays inline and unchanged")
                }
                for (enabled in listOf(true, false)) {
                    val image = Bitmap.createBitmap(scene.crop.width(), scene.crop.height(), Bitmap.Config.ARGB_8888)
                    try {
                        image.eraseColor(Color.rgb(27, 48, 78))
                        scene.draw(Canvas(image), enabled, scene.frame(enabled).timeline.text.length)
                        val directory = File(root, "app/build/reports/annotation-preferences").apply { mkdirs() }
                        File(directory, "$locale-${if (bilingual) "bilingual" else "single"}-${if (enabled) "on" else "off"}.png")
                            .outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    } finally { image.recycle() }
                }
            }
        }
    }

    @Test fun `choice display and stored history retain words and annotation data across toggles`() = withFonts {
        val source = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
        try {
            for (locale in listOf("zh-Hans", "zh-Hant", "en")) {
                val sample = DialoguePlaybackExample.forTarget(locale)
                val encoded = EmphasisText.encode(sample.annotatedText, sample.emphasisRanges)
                val hidden = RubyRenderText.forDisplay(encoded, false)
                assertEquals(sample.text, hidden)
                assertTrue(RubyRenderText.parse(hidden).annotations.isEmpty())
                assertEquals(encoded, RubyRenderText.forDisplay(encoded, true))
                assertEquals(sample.emphasisRanges, EmphasisText.decode(encoded).ranges)
                assertEquals(if (locale == "en") 2 else 3, RubyRenderText.parse(encoded).annotations.size)
                val choice = RenderInstruction(ClassifiedRegion(TextRegion.CHOICE_BUTTON, emptyList(),
                    Rect(330, 300, 1590, 460), OcrEngineId.PADDLE_OCR), sample.annotatedText,
                    targetLocale = locale, emphasisRanges = sample.emphasisRanges, showAnnotations = false)
                val renderer = OverlayRenderer(context)
                try {
                    val hiddenBitmap = renderer.render(source, listOf(choice), 1920, 1080).copy(Bitmap.Config.ARGB_8888, false)
                    try {
                        val plainBitmap = renderer.render(source, listOf(choice.copy(translatedText = hidden,
                            emphasisRanges = emptyList())), 1920, 1080)
                        assertTrue(hiddenBitmap.sameAs(plainBitmap), "Choice OFF is actually plain: $locale")
                    } finally { hiddenBitmap.recycle() }
                    // Rendering OFF never destroys the original instruction's history data.
                    assertEquals(sample.annotatedText, choice.translatedText)
                    assertEquals(sample.emphasisRanges, choice.emphasisRanges)
                } finally { renderer.releaseBuffers() }
            }
        } finally { source.recycle() }
    }

    private fun assertArrayEquals(a: FloatArray, b: FloatArray) {
        assertEquals(a.size, b.size)
        a.indices.forEach { assertEquals(a[it], b[it], 0f) }
    }

    private fun withFonts(test: () -> Unit) {
        val fonts = listOf("cachedSimplifiedTypeface" to "FgoStory.ttf",
            "cachedTraditionalTypeface" to "FgoStory_td.otf", "cachedEnglishTypeface" to "FgoStory_en.ttf")
        val fields = fonts.map { (name, file) ->
            val field = FgoTypefaceProvider::class.java.getDeclaredField(name).apply { isAccessible = true }
            Triple(field, field.get(null), file)
        }
        try {
            fields.forEach { (field, _, file) -> field.set(null, Typeface.createFromFile(File(root, "app/src/main/assets/fonts/$file"))) }
            test()
        } finally { fields.forEach { (field, old, _) -> field.set(null, old) } }
    }
}
