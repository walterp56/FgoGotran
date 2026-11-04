package com.fgogotran.overlay

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.SystemClock
import android.widget.ImageView
import androidx.test.platform.app.InstrumentationRegistry
import com.fgogotran.ocr.OcrEngineId
import com.fgogotran.text.EmphasisRange
import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Real Android fonts/canvas. No game, OCR, translation backend or service is invoked. */
class ChoiceExitDeviceRenderTest {
    @Test fun choicePixelsRetireWithoutChangingDialogueNamesOrLayout() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.runOnMainSync {
            val renderer = OverlayRenderer(context)
            val source = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
            fun region(kind: TextRegion, box: Rect) = ClassifiedRegion(kind, emptyList(), box, OcrEngineId.PADDLE_OCR)
            try {
                for (locale in listOf("zh-Hans", "zh-Hant", "en")) for (bilingual in listOf(false, true)) {
                    val choice = when (locale) {
                        "en" -> "The Observatory〈Chaldea〉"
                        "zh-Hant" -> "觀星臺〈迦勒底〉"
                        else -> "观星台〈迦勒底〉"
                    }
                    val emphasized = if (locale == "en") "Let's go together." else "一起前進吧。"
                    val input = listOf(
                        RenderInstruction(region(TextRegion.DIALOGUE_BOX, Rect(35, 830, 1805, 1055)),
                            if (locale == "en") "We are ready.\nOur story continues." else "準備好了。\n故事繼續。",
                            "準備できました。\n物語は続きます。", targetLocale = locale, showOriginalText = bilingual),
                        RenderInstruction(region(TextRegion.NAME_LABEL, Rect(35, 740, 600, 828)), "Mash", targetLocale = locale),
                        RenderInstruction(region(TextRegion.CHOICE_BUTTON, Rect(300, 250, 1600, 385)),
                            choice, "星見台〈カルデア〉へ", targetLocale = locale, showOriginalText = bilingual),
                        RenderInstruction(region(TextRegion.CHOICE_BUTTON, Rect(300, 437, 1600, 572)),
                            emphasized, "一緒に進もう。", targetLocale = locale, showOriginalText = bilingual,
                            emphasisRanges = listOf(EmphasisRange(0, emphasized.length - 1)))
                    )
                    val expected = renderer.render(source, input, 1920, 1080).copy(Bitmap.Config.ARGB_8888, false)
                    val result = renderer.renderWithChoices(source, input)
                    assertTrue("unchanged static pixels $locale/$bilingual", expected.sameAs(result.bitmap))
                    assertEquals(2, result.choices.size)
                    val playback = renderer.prepareDialoguePlayback(source, input, DialoguePlaybackSettings(DialogueTextSpeed.HALF))
                    assertEquals(2, playback.choices.size)
                    val view = DialoguePlaybackView(context).apply {
                        scaleType = ImageView.ScaleType.FIT_XY
                        setImageBitmap(playback.bitmap)
                        layout(0, 0, 1920, 1080)
                    }
                    val actual = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
                    try {
                        val baseline = IntArray(1920 * 1080)
                        expected.getPixels(baseline, 0, 1920, 0, 0, 1920, 1080)
                        for (selectedIndex in playback.choices.indices) {
                            actual.eraseColor(0)
                            // Sample a deterministic elapsed time, independent of device/GC delays.
                            val frame = ChoiceExitFrame.capture(playback.bitmap, playback.choices, selectedIndex)
                            try { frame.draw(Canvas(actual), 60) } finally { frame.close() }
                            val echo = IntArray(baseline.size)
                            actual.getPixels(echo, 0, 1920, 0, 0, 1920, 1080)
                            val bounds = playback.choices[selectedIndex].paintBounds
                            var changes = 0
                            for (index in baseline.indices) {
                                val x = index % 1920; val y = index / 1920
                                if (playback.choices.none { it.paintBounds.contains(x, y) }) {
                                    if (echo[index] != 0) fail("echo escaped choices at $x,$y: $locale/$bilingual/$selectedIndex")
                                } else if (!bounds.contains(x, y)) {
                                    if (baseline[index] != echo[index]) fail("echo escaped selected row at $x,$y: $locale/$bilingual/$selectedIndex")
                                } else if (baseline[index] != echo[index]) changes++
                            }
                            assertTrue("selected text echo visible: $locale/$bilingual/$selectedIndex", changes > 0)
                        }
                        actual.eraseColor(0)
                        view.startChoiceExit(ChoiceExitFrame.capture(playback.bitmap, playback.choices, 1),
                            SystemClock.elapsedRealtime() - 1_000) {}
                        view.draw(Canvas(actual))
                        val before = IntArray(1920 * 1080)
                        val after = IntArray(before.size)
                        expected.getPixels(before, 0, 1920, 0, 0, 1920, 1080)
                        actual.getPixels(after, 0, 1920, 0, 0, 1920, 1080)
                        for (index in before.indices) {
                            val x = index % 1920; val y = index / 1920
                            if (result.choices.none { it.paintBounds.contains(x, y) }) {
                                if (before[index] != after[index]) fail("non-choice pixel changed at $x,$y: $locale/$bilingual")
                            } else if (result.choices[0].paintBounds.contains(x, y)) {
                                if ((after[index] ushr 24) != 0) fail("unselected ruby/text/plane remained at $x,$y")
                            }
                        }
                        val selected = result.choices[1].paintBounds
                        assertTrue("selected row still fading", (actual.getPixel(selected.centerX(), selected.centerY()) ushr 24) in 1..254)
                        if (locale == "en" && bilingual) {
                            val directory = File(context.getExternalFilesDir(null), "choice-exit-validation").apply { mkdirs() }
                            for (elapsed in listOf(0L, 60L, 150L, 300L, 600L, 700L, 850L, 1_000L, 1_150L, 1_300L)) {
                                view.resetChoiceExit()
                                view.startChoiceExit(ChoiceExitFrame.capture(playback.bitmap, playback.choices, 1),
                                    SystemClock.elapsedRealtime() - elapsed) {}
                                actual.eraseColor(0)
                                view.draw(Canvas(actual))
                                File(directory, "exit-$elapsed.png").outputStream().use {
                                    actual.compress(Bitmap.CompressFormat.PNG, 100, it)
                                }
                            }
                        }
                    } finally {
                        view.resetChoiceExit(); view.setImageBitmap(null)
                        playback.close(); actual.recycle(); expected.recycle()
                    }
                }
            } finally { source.recycle(); renderer.releaseBuffers() }
        }
    }
}
