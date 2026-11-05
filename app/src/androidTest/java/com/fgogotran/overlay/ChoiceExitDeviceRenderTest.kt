package com.fgogotran.overlay

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
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
    @Test fun coversFadeGraduallyWithoutAnOpaqueTextlessTail() {
        val box = Rect(10, 20, 310, 100)
        val background = Paint().apply { color = Color.BLUE }
        val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 25f }
        val plan = ChoiceSelectionVisualPlan(RectF(box), 0f,
            drawBackground = { it.drawRect(box, background) },
            drawText = { it.drawText("Go", 230f, 70f, white) })
        val source = Bitmap.createBitmap(320, 120, Bitmap.Config.ARGB_8888)
        val output = Bitmap.createBitmap(320, 120, Bitmap.Config.ARGB_8888)
        plan.capture(box).use { it.draw(Canvas(source), 1f, 0) }
        try {
            for (selected in listOf(false, true)) {
                val start = if (selected) 700L else 100L
                val end = if (selected) 1_300L else 600L
                val frame = ChoiceExitFrame.capture(source, listOf(
                    ChoiceRenderRegion(Rect(0, 0, 1, 1), Rect(0, 0, 1, 1)),
                    ChoiceRenderRegion(box, box, plan)), if (selected) 1 else 0)
                try {
                    val previewTimes = listOf(start + 150, start + 250, end - 100, end - 50, end - 20, end - 1, end)
                    val times = ((start..end step 17L).toList() + previewTimes + listOf(end + 33)).distinct().sorted()
                    for (time in times) {
                        output.eraseColor(Color.TRANSPARENT)
                        val canvas = Canvas(output)
                        frame.draw(canvas, time)
                        val opacity = Color.alpha(output.getPixel(180, 60))
                        if (time >= start + (end - start) / 2) assertTrue("plane is already fading", opacity <= 127)
                        if (time >= end - 100L) assertTrue("no late opaque block", opacity < 32)
                        if (time in previewTimes) {
                            val directory = File(InstrumentationRegistry.getInstrumentation().targetContext
                                .getExternalFilesDir(null), "choice-natural-fade-validation").apply { mkdirs() }
                            File(directory, "plane-$selected-$time.png").outputStream().use {
                                output.compress(Bitmap.CompressFormat.PNG, 100, it)
                            }
                        }
                        if (time == end - 20L) assertEquals("no plane after text disappears",
                            Color.TRANSPARENT, output.getPixel(180, 60))
                        if (time >= end) {
                            output.eraseColor(Color.TRANSPARENT)
                            frame.draw(Canvas(output), time)
                            assertEquals("native cover retired on deadline", Color.TRANSPARENT, output.getPixel(180, 60))
                        }
                    }
                } finally { frame.close() }
            }
        } finally { source.recycle(); output.recycle() }
    }

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
                            // Compare against the same retirement without the selected echo:
                            // the other row starts fading after 100ms and must still match.
                            val referenceBitmap = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
                            val withoutEcho = playback.choices.mapIndexed { index, region ->
                                if (index == selectedIndex) region.copy(selectionVisual = null) else region
                            }
                            val reference = ChoiceExitFrame.capture(playback.bitmap, withoutEcho, selectedIndex)
                            val frame = ChoiceExitFrame.capture(playback.bitmap, playback.choices, selectedIndex)
                            val echo = IntArray(baseline.size)
                            val normal = IntArray(baseline.size)
                            val bounds = playback.choices[selectedIndex].paintBounds
                            try {
                                for (elapsed in listOf(33L, 100L, 150L, 200L, 250L, 300L)) {
                                    actual.eraseColor(0); referenceBitmap.eraseColor(0)
                                    frame.draw(Canvas(actual), elapsed)
                                    reference.draw(Canvas(referenceBitmap), elapsed)
                                    actual.getPixels(echo, 0, 1920, 0, 0, 1920, 1080)
                                    referenceBitmap.getPixels(normal, 0, 1920, 0, 0, 1920, 1080)
                                    var changes = 0
                                    for (index in baseline.indices) {
                                        val x = index % 1920; val y = index / 1920
                                        if (!bounds.contains(x, y)) {
                                            if (normal[index] != echo[index]) fail("echo escaped selected row at $x,$y: $locale/$bilingual/$selectedIndex/$elapsed")
                                        } else if (normal[index] != echo[index]) changes++
                                    }
                                    if (elapsed < 300L) assertTrue("selected echo visible: $locale/$bilingual/$selectedIndex/$elapsed", changes > 0)
                                    else assertEquals("echo ends at 300ms", 0, changes)
                                }
                            } finally {
                                frame.close(); reference.close(); referenceBitmap.recycle()
                            }
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
                        assertTrue("selected plane fades with the option",
                            (actual.getPixel(selected.centerX(), selected.centerY()) ushr 24) in 1..254)
                        val whiteGlyph = before.indices.first { index ->
                            selected.contains(index % 1920, index / 1920) &&
                                Color.red(before[index]) >= 240 &&
                                Color.green(before[index]) >= 240 &&
                                Color.blue(before[index]) >= 240
                        }
                        // Bitmap.getPixel returns unpremultiplied RGB. Compare visible ink on
                        // black so a translucent plane does not make the same ink look brighter.
                        val originalInk = Color.red(before[whiteGlyph]) * Color.alpha(before[whiteGlyph]) / 255
                        val fadingInk = Color.red(after[whiteGlyph]) * Color.alpha(after[whiteGlyph]) / 255
                        assertTrue("translated glyph keeps fading independently of cover", fadingInk < originalInk / 2)
                        if (locale == "en" && bilingual) {
                            val directory = File(context.getExternalFilesDir(null), "choice-natural-fade-validation").apply { mkdirs() }
                            for (elapsed in listOf(0L, 33L, 60L, 100L, 150L, 200L, 250L, 300L, 500L, 550L, 580L, 599L,
                                600L, 700L, 850L, 1_000L, 1_150L, 1_200L, 1_225L, 1_250L, 1_280L, 1_299L, 1_300L)) {
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
