package com.fgogotran.overlay

import android.app.Activity
import android.graphics.*
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.View
import android.widget.FrameLayout
import com.fgogotran.ocr.OcrEngineId
import com.fgogotran.text.EmphasisRange
import com.fgogotran.text.EmphasisText
import java.io.File
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.*

/** Actual fonts, layout, clipping and styled history; no OCR model or translation API is invoked. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RubySpacingGraphicsTest {
    private val root = generateSequence(File(requireNotNull(System.getProperty("user.dir"))).absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }
    private val directory get() = File(root, "app/build/reports/ruby-spacing").apply { mkdirs() }
    private val original = "彼女〈オルガマリー〉が次元孔に消えていくのを見届けたあと、\n意識を失った時はどうなるかと思った。"

    @Test fun `reported dialogue ruby renders identically with Unicode separator spaces in all targets`() = withFonts {
        val source = Bitmap.createBitmap(2340, 1080, Bitmap.Config.ARGB_8888)
        try {
            for (target in listOf("zh-Hans", "zh-Hant", "en")) for (bilingual in listOf(false, true)) {
                val tight = when(target) {
                    "en" -> "After I saw her〈Olga Marie〉 vanish into the dimensional hole,\n" +
                        "I wondered what would become of me when I lost consciousness."
                    "zh-Hant" -> "目送她〈奧爾加瑪麗〉消失在次元孔之後，\n失去意識時，我還以為會怎樣呢。"
                    else -> "目送她〈奥尔加玛丽〉消失在次元孔之后，\n失去意识时，我还以为会怎样呢。"
                }
                val spaced = tight.replace("〈", " \u3000〈").replace("〉", "〉\t")
                val region = ClassifiedRegion(TextRegion.DIALOGUE_BOX, emptyList(), Rect(316,833,2015,1052), OcrEngineId.PADDLE_OCR)
                val instruction = RenderInstruction(region, tight, sourceText = original, targetLocale = target, showOriginalText = bilingual)
                val renderer = OverlayRenderer(RuntimeEnvironment.getApplication())
                val settings = DialoguePlaybackSettings(DialogueTextSpeed.NORMAL, 1)
                renderer.prepareDialoguePlayback(source, listOf(instruction), settings).use { expected ->
                    renderer.prepareDialoguePlayback(source, listOf(instruction.copy(translatedText = spaced)), settings).use { actual ->
                        val before = expected.panels.single()
                        val panel = actual.panels.single()
                        assertEquals(before.bounds, panel.bounds, "$target/$bilingual")
                        assertEquals(before.rows.map { it.x to it.layer.top }, panel.rows.map { it.x to it.layer.top })
                        assertTrue(expected.bitmap.pixels().contentEquals(actual.bitmap.pixels()), "$target/$bilingual pixels differ")
                        assertEquals(1, panel.rows.sumOf { it.readings.size }, "Ruby omitted: $target/$bilingual")
                        val row = panel.rows.single { it.readings.isNotEmpty() }
                        val reading = row.readings.single()
                        assertTrue(row.text.substring(0,reading.end).endsWith(if(target == "en") "her" else "她"))
                        assertTrue(reading.layer.top >= panel.bounds.top)
                        assertFalse(panel.rows.any { '〈' in it.text || '〉' in it.text })
                        if (bilingual) {
                            assertTrue(panel.originalRows.all { it.readings.isEmpty() })
                            assertTrue(panel.originalRows.joinToString("") { it.text }.contains("〈オルガマリー〉"))
                        }
                        File(directory, "dialogue-$target-${if(bilingual) "bilingual" else "mono"}.png").outputStream().use {
                            actual.bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                        }
                    }
                }
            }
        } finally { source.recycle() }
    }

    @Test fun `choices retain visible above-base ruby and body pixels for tight and spaced notation`() = withFonts {
        val source = Bitmap.createBitmap(2340, 1080, Bitmap.Config.ARGB_8888)
        try {
            for (target in listOf("zh-Hans", "zh-Hant", "en")) for (bilingual in listOf(false, true)) {
                val tight = if(target == "en") "The Great Command Spell〈Sirius Light〉." else "大令咒〈Sirius Light〉。"
                val spaced = tight.replace("〈", "\u00a0 〈").replace("〉", "〉\u3000")
                val region = ClassifiedRegion(TextRegion.CHOICE_BUTTON, emptyList(), Rect(435,300,1905,435), OcrEngineId.PADDLE_OCR)
                val instruction = RenderInstruction(region, tight, sourceText = "大令呪〈シリウスライト〉。", targetLocale = target, showOriginalText = bilingual)
                val renderer = OverlayRenderer(RuntimeEnvironment.getApplication())
                val expected = renderer.render(source, listOf(instruction), 2340, 1080).copy(Bitmap.Config.ARGB_8888, false)
                val actual = renderer.render(source, listOf(instruction.copy(translatedText = spaced)), 2340, 1080)
                    .copy(Bitmap.Config.ARGB_8888, false)
                val plain = renderer.render(source, listOf(instruction.copy(translatedText = RubyRenderText.body(tight))), 2340, 1080)
                try {
                    assertTrue(expected.pixels().contentEquals(actual.pixels()), "$target/$bilingual pixels differ")
                    assertFalse(plain.pixels().contentEquals(actual.pixels()), "No visible choice ruby: $target/$bilingual")
                } finally { expected.recycle(); actual.recycle() }
            }
        } finally { source.recycle() }
    }

    @Test fun `long adjacent readings near panel edges survive joint fitting with proportional ruby`() = withFonts {
        val source = Bitmap.createBitmap(2340, 1080, Bitmap.Config.ARGB_8888)
        try {
            for ((target, text) in listOf(
                "zh-Hant" to "大令咒 〈Sirius Light〉與異聞帶　〈Lostbelt〉。",
                "zh-Hans" to "大令咒 〈Sirius Light〉与异闻带　〈Lostbelt〉。",
                "en" to "Spell 〈Sirius Light〉 and world　〈Lostbelt〉.")) {
                val region = ClassifiedRegion(TextRegion.DIALOGUE_BOX, emptyList(), Rect(316,833,2015,1052), OcrEngineId.PADDLE_OCR)
                val renderer = OverlayRenderer(RuntimeEnvironment.getApplication())
                renderer.prepareDialoguePlayback(source, listOf(RenderInstruction(region,text,targetLocale=target)),
                    DialoguePlaybackSettings(DialogueTextSpeed.NORMAL,1)).use { frame ->
                    assertEquals(2, frame.panels.single().rows.sumOf { it.readings.size }, target)
                    assertTrue(frame.panels.single().rows.size <= 2)
                    assertEquals(RubyRenderText.body(text).filterNot(Char::isWhitespace),
                        frame.panels.single().rows.joinToString("") { it.text }.filterNot(Char::isWhitespace))
                }
            }
        } finally { source.recycle() }
    }

    @Test fun `styled history uses the same edits preserves spans and actually paints the reading`() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val host = FrameLayout(activity)
        val view = RubyTextView(activity).apply {
            typeface = Typeface.createFromFile(File(root, "app/src/main/assets/fonts/FgoStory_en.ttf"))
            setTextSize(TypedValue.COMPLEX_UNIT_PX, 54f)
            setTextColor(Color.WHITE)
        }
        activity.setContentView(host)
        host.addView(view, FrameLayout.LayoutParams(1400, 180).apply { leftMargin = 100; topMargin = 100 })
        fun draw(value: String): Bitmap {
            val start = value.indexOf("real")
            val encoded = EmphasisText.encode(value, listOf(EmphasisRange(start,start+"real reason".length)))
            val styled = SpannableStringBuilder(encoded)
            val quote = ForegroundColorSpan(Color.BLUE)
            val base = ForegroundColorSpan(Color.CYAN)
            val suffix = ForegroundColorSpan(Color.GREEN)
            styled.setSpan(quote, styled.length-1, styled.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            styled.setSpan(base, encoded.indexOf("her"), encoded.indexOf("her")+3, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            styled.setSpan(suffix, encoded.indexOf("reason"), encoded.indexOf("reason")+6, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            view.setRubyText(styled)
            val expected = "「After her, the real reason.」"
            assertEquals(expected, view.text.toString())
            val rendered = view.text as Spanned
            assertEquals(expected.lastIndex, rendered.getSpanStart(quote))
            assertEquals(expected.indexOf("her"), rendered.getSpanStart(base))
            assertEquals(expected.indexOf("her")+3, rendered.getSpanEnd(base))
            assertEquals(expected.indexOf("reason"), rendered.getSpanStart(suffix))
            shadowOf(Looper.getMainLooper()).idle()
            host.measure(View.MeasureSpec.makeMeasureSpec(1600,View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(350,View.MeasureSpec.EXACTLY))
            host.layout(0,0,1600,350)
            return Bitmap.createBitmap(1600,350,Bitmap.Config.ARGB_8888).also {
                val canvas = Canvas(it)
                canvas.drawColor(Color.rgb(27,48,78))
                host.draw(canvas)
            }
        }
        try {
            val tight = draw("「After her〈Olga Marie〉, the real reason.」")
            val spaced = draw("「After her \u3000〈Olga Marie〉 , the real reason.」")
            val plain = draw("「After her, the real reason.」")
            try {
                File(directory,"history-tight.png").outputStream().use { tight.compress(Bitmap.CompressFormat.PNG,100,it) }
                File(directory,"history-spaced.png").outputStream().use { spaced.compress(Bitmap.CompressFormat.PNG,100,it) }
                assertTrue(tight.pixels().contentEquals(spaced.pixels()), "History pixels differ")
                assertFalse(plain.pixels().contentEquals(spaced.pixels()), "History did not paint ruby")
                File(directory,"history-en.png").outputStream().use { spaced.compress(Bitmap.CompressFormat.PNG,100,it) }
            } finally { tight.recycle();spaced.recycle();plain.recycle() }
        } finally { controller.pause().stop().destroy() }
    }

    private inline fun withFonts(block: () -> Unit) {
        val originals = listOf("cachedSimplifiedTypeface" to "FgoStory.ttf", "cachedTraditionalTypeface" to "FgoStory_td.otf",
            "cachedEnglishTypeface" to "FgoStory_en.ttf").map { (name, file) ->
            val field = FgoTypefaceProvider::class.java.getDeclaredField(name).apply { isAccessible=true }
            val old = field.get(null)
            field.set(null, Typeface.createFromFile(File(root,"app/src/main/assets/fonts/$file")))
            field to old
        }
        try { block() } finally { originals.forEach { (field,old) -> field.set(null,old) } }
    }
    private fun Bitmap.pixels() = IntArray(width*height).also { getPixels(it,0,width,0,0,width,height) }
}
