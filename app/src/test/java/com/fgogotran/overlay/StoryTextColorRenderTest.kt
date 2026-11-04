package com.fgogotran.overlay

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import com.fgogotran.accessibility.FgoAccessibilityService
import com.fgogotran.ocr.OcrEngineId
import com.fgogotran.ocr.OcrTextLine
import com.fgogotran.text.EmphasisRange
import java.io.File
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StoryTextColorRenderTest {
    private val blue = 0xff6079f0.toInt()
    private val white = Color.rgb(245, 245, 240)
    private val cyan = Color.rgb(80, 235, 235)

    private fun sampledBlue(): Int? {
        val bitmap = javaClass.getResourceAsStream("/ocr/jp-blue-dialogue.png")!!.use { BitmapFactory.decodeStream(it)!! }
        try {
            val bounds = Rect(0, 0, bitmap.width, bitmap.height)
            val region = ClassifiedRegion(TextRegion.DIALOGUE_BOX,
                listOf(OcrTextLine("『違う』", bounds, 1f)), bounds, OcrEngineId.PADDLE_OCR)
            val method = FgoAccessibilityService::class.java.getDeclaredMethod("renderTextColorForRegion",
                Bitmap::class.java, ClassifiedRegion::class.java).apply { isAccessible = true }
            return method.invoke(Robolectric.buildService(FgoAccessibilityService::class.java).get(), bitmap, region) as Int?
        } finally { bitmap.recycle() }
    }

    @Test fun `sampled blue reaches names dialogue annotations and playback without moving text`() = withFonts {
        val sampled = sampledBlue()
        assertEquals(blue, sampled)
        val source = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
        val renderer = OverlayRenderer(RuntimeEnvironment.getApplication())
        try {
            for (locale in listOf("zh-Hans", "zh-Hant", "en")) for (bilingual in listOf(false, true)) {
                val text = when (locale) {
                    "en" -> "No.\nThe Observatory〈Chaldea〉 is ahead."
                    "zh-Hant" -> "『不對』\n觀星臺〈迦勒底〉就在前方。"
                    else -> "『不对』\n观星台〈迦勒底〉就在前方。"
                }
                val input = listOf(
                    RenderInstruction(ClassifiedRegion(TextRegion.DIALOGUE_BOX, emptyList(),
                        Rect(35, 830, 1805, 1055), OcrEngineId.PADDLE_OCR), text,
                        "『違う』\n星見台〈カルデア〉は前方です。", textColor = sampled,
                        targetLocale = locale, showOriginalText = bilingual,
                        emphasisRanges = listOf(EmphasisRange(0, 2))),
                    RenderInstruction(ClassifiedRegion(TextRegion.NAME_LABEL, emptyList(),
                        Rect(35, 740, 600, 828), OcrEngineId.PADDLE_OCR,
                        sourcePlateBounds = Rect(35, 740, 600, 828), sourceNameTextBounds = Rect(90, 755, 380, 815)),
                        if (locale == "en") "Shadow" else "黑影", textColor = sampled, targetLocale = locale)
                )
                val plain = renderer.render(source, input.map { it.copy(textColor = white) }, 1920, 1080)
                    .copy(Bitmap.Config.ARGB_8888, false)
                val coloured = renderer.render(source, input, 1920, 1080).copy(Bitmap.Config.ARGB_8888, false)
                val frame = renderer.prepareDialoguePlayback(source, input, DialoguePlaybackSettings(DialogueTextSpeed.HALF))
                val partial = coloured.copy(Bitmap.Config.ARGB_8888, true)
                try {
                    assertTrue(frame.bitmap.sameAs(coloured), "playback final colour: $locale/$bilingual")
                    val before = IntArray(1920 * 1080)
                    val after = IntArray(before.size)
                    plain.getPixels(before, 0, 1920, 0, 0, 1920, 1080)
                    coloured.getPixels(after, 0, 1920, 0, 0, 1920, 1080)
                    var nameCores = 0
                    var dialogueCores = 0
                    var originalCores = 0
                    before.indices.forEach { index ->
                        if (before[index] ushr 24 != after[index] ushr 24) fail("alpha/layout at $index")
                        if (before[index] == white) {
                            // Skia can round a fully covered channel down by one during compositing.
                            if (!sameRgb(blue, after[index])) fail("same glyph core at $index: $locale/$bilingual")
                            if (index / 1920 < 830) nameCores++ else dialogueCores++
                        }
                        if (before[index] == cyan) { assertEquals(cyan, after[index]); originalCores++ }
                    }
                    assertTrue(nameCores > 10 && dialogueCores > 10)
                    assertEquals(bilingual, originalCores > 10)
                    val readings = frame.panels.single().rows.flatMap { it.readings }
                    assertTrue(readings.size >= 2, "fixture must include both ruby and emphasis")
                    readings.forEach { reading ->
                        val pixels = IntArray(reading.layer.bitmap.width * reading.layer.bitmap.height)
                        reading.layer.bitmap.getPixels(pixels, 0, reading.layer.bitmap.width, 0, 0,
                            reading.layer.bitmap.width, reading.layer.bitmap.height)
                        assertTrue(pixels.any { it ushr 24 == 255 && sameRgb(blue, it) }, "ruby/dot colour")
                    }
                    frame.drawReveal(Canvas(partial), 2)
                    assertFalse(partial.sameAs(coloured))
                    for (y in 740 until 828) for (x in 35 until 600) {
                        assertEquals(coloured.getPixel(x, y), partial.getPixel(x, y), "name must stay static")
                    }
                    // ImageView redraws the final base bitmap before every reveal pass.
                    partial.eraseColor(Color.TRANSPARENT)
                    Canvas(partial).drawBitmap(frame.bitmap, 0f, 0f, null)
                    frame.drawReveal(Canvas(partial), frame.timeline.text.length)
                    assertTrue(partial.sameAs(coloured), "completed reveal: $locale/$bilingual")
                } finally { plain.recycle(); coloured.recycle(); partial.recycle(); frame.close() }
            }
        } finally { source.recycle(); renderer.releaseBuffers() }
    }

    private fun sameRgb(expected: Int, actual: Int): Boolean = listOf(0, 8, 16).all { shift ->
        kotlin.math.abs((expected ushr shift and 255) - (actual ushr shift and 255)) <= 1
    }

    private fun withFonts(block: () -> Unit) {
        val root = generateSequence(File(requireNotNull(System.getProperty("user.dir"))).absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }
        val fields = listOf("cachedSimplifiedTypeface" to "FgoStory.ttf",
            "cachedTraditionalTypeface" to "FgoStory_td.otf", "cachedEnglishTypeface" to "FgoStory_en.ttf").map { (name, file) ->
            val field = FgoTypefaceProvider::class.java.getDeclaredField(name).apply { isAccessible = true }
            Triple(field, field.get(null), File(root, "app/src/main/assets/fonts/$file"))
        }
        try {
            fields.forEach { (field, _, file) -> field.set(null, Typeface.createFromFile(file)) }
            block()
        } finally { fields.forEach { (field, original, _) -> field.set(null, original) } }
    }
}
