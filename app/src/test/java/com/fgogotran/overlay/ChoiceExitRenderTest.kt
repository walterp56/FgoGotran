package com.fgogotran.overlay

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.app.Activity
import android.os.Looper
import android.os.SystemClock
import android.widget.ImageView
import kotlin.test.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowSystemClock
import com.fgogotran.ocr.OcrEngineId
import com.fgogotran.text.EmphasisRange
import java.time.Duration
import java.io.File
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class ChoiceExitRenderTest {
    private val rows = listOf(Rect(20, 20, 300, 60), Rect(20, 90, 300, 130))
    private val regions get() = rows.map { ChoiceRenderRegion(Rect(it), Rect(it)) }

    private fun source(): Bitmap = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888).apply {
        density = Bitmap.DENSITY_NONE
        val canvas = Canvas(this)
        val paint = Paint()
        paint.color = Color.RED; canvas.drawRect(rows[0], paint)
        paint.color = Color.BLUE; canvas.drawRect(rows[1], paint)
        paint.color = Color.YELLOW; canvas.drawRect(Rect(10, 155, 80, 175), paint)
        paint.color = Color.GREEN; canvas.drawRect(Rect(10, 180, 310, 235), paint)
    }

    private fun view(bitmap: Bitmap) = DialoguePlaybackView(RuntimeEnvironment.getApplication()).apply {
        scaleType = ImageView.ScaleType.FIT_XY
        setImageBitmap(bitmap)
        layout(0, 0, 320, 240)
    }

    private fun pixels(view: DialoguePlaybackView): Bitmap =
        Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }

    private fun selectionPlan(onCapture: () -> Unit = {}): ChoiceSelectionVisualPlan {
        val bounds = RectF(rows[1])
        val background = Paint().apply { color = Color.BLUE }
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 23f }
        return ChoiceSelectionVisualPlan(bounds, 0f,
            drawBackground = { it.drawRect(bounds, background) },
            drawText = { onCapture(); it.drawText("GO", 130f, 120f, text) })
    }

    @Test fun `later cover suppresses fading Japanese without restoring full opacity to translated letters`() {
        for (selected in listOf(false, true)) {
            val source = source()
            val plan = selectionPlan()
            val original = plan.capture(rows[1])
            original.draw(Canvas(source), 1f, 0)
            original.close()
            val time = if (selected) 876L else 246L
            val chosen = if (selected) 1 else 0
            val fixed = ChoiceExitFrame.capture(source,
                listOf(regions[0], regions[1].copy(selectionVisual = plan)), chosen)
            val previous = ChoiceExitFrame.capture(source, regions, chosen)
            fun composite(frame: ChoiceExitFrame): Bitmap {
                val bitmap = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                canvas.drawColor(Color.BLACK)
                // A fading source glyph away from the translated glyph positions.
                canvas.drawRect(30f, 100f, 80f, 120f, Paint().apply { color = Color.rgb(127, 127, 127) })
                frame.draw(canvas, time)
                return bitmap
            }
            val actual = composite(fixed)
            val before = composite(previous)
            try {
                assertTrue(Color.red(before.getPixel(40, 110)) > 50, "fixture must expose the source text")
                assertTrue(Color.red(actual.getPixel(40, 110)) <= 2, "cover should suppress exposed Japanese")
                val white = (rows[1].top until rows[1].bottom).flatMap { y ->
                    (100 until 200).map { x -> x to y }
                }.first { (x, y) -> source.getPixel(x, y) == Color.WHITE }
                assertTrue(abs(ChoiceExitController.alpha(selected, time) -
                    Color.red(actual.getPixel(white.first, white.second))) <= 1, "translation must keep fading")
                assertEquals(0, Color.red(actual.getPixel(5, 5)), "cover must remain inside the choice")
            } finally {
                fixed.close(); previous.close(); actual.recycle(); before.recycle(); source.recycle()
            }
        }
    }

    @Test fun `cover visibly fades before retirement and cannot leave an opaque textless plane`() {
        for (selected in listOf(false, true)) {
            val source = source()
            val plan = selectionPlan()
            plan.capture(rows[1]).use { it.draw(Canvas(source), 1f, 0) }
            val chosen = if (selected) 1 else 0
            val frame = ChoiceExitFrame.capture(source,
                listOf(regions[0], regions[1].copy(selectionVisual = plan)), chosen)
            val output = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888)
            val start = if (selected) 700L else 100L
            val end = if (selected) 1_300L else 600L
            val times = ((start..end step 17L).toList() + listOf(end - 20, end - 1, end, end + 33)).sorted()
            try {
                for (time in times) {
                    output.eraseColor(Color.TRANSPARENT)
                    val canvas = Canvas(output)
                    frame.draw(canvas, time)
                    val opacity = Color.alpha(output.getPixel(100, 110))
                    if (time >= start + (end - start) / 2) {
                        assertTrue(opacity <= 127, "cover must already be translucent at $selected/$time")
                    }
                    if (time >= end - 100L) assertTrue(opacity < 32, "no late black block at $selected/$time")
                    if (time == end - 20L) {
                        assertEquals(0, ChoiceExitController.alpha(selected, time))
                        assertEquals(Color.TRANSPARENT, output.getPixel(100, 110), "no textless plane")
                    }
                    if (time >= end) assertEquals(Color.TRANSPARENT, output.getPixel(100, 110), "cover retired at $end")
                }
            } finally { frame.close(); output.recycle(); source.recycle() }
        }
    }

    @Test fun `release anchored start preserves the bright echo after the 80ms game gesture`() {
        val source = source()
        val plan = selectionPlan()
        val original = plan.capture(rows[1])
        original.draw(Canvas(source), 1f, 0); original.close()
        val view = view(source)
        val dispatchedAt = SystemClock.elapsedRealtime()
        ShadowSystemClock.advanceBy(Duration.ofMillis(80))
        try {
            view.startChoiceExit(ChoiceExitFrame.capture(source,
                listOf(regions[1].copy(selectionVisual = plan)), 0), dispatchedAt + 80L) {}
            // Scheduling a vsync may advance Robolectric's clock before the first draw.
            // Verify the anchor directly; the zero-time pixels are tested deterministically below.
            assertEquals(dispatchedAt + 80L, DialoguePlaybackView::class.java
                .getDeclaredField("choiceExitStartedAt").apply { isAccessible = true }.getLong(view))
            ShadowSystemClock.advanceBy(Duration.ofMillis(60))
            val atPeak = pixels(view)
            assertTrue((rows[1].top until rows[1].bottom).any { y ->
                (rows[1].left until rows[1].right).any { x -> source.getPixel(x, y) != atPeak.getPixel(x, y) }
            }, "peak should remain visible 60ms after release")
            atPeak.recycle()
            ShadowSystemClock.advanceBy(Duration.ofMillis(240))
            val afterEcho = pixels(view)
            for (y in rows[1].top until rows[1].bottom) for (x in rows[1].left until rows[1].right) {
                assertEquals(source.getPixel(x, y), afterEcho.getPixel(x, y), "echo must end at release + 300ms")
            }
            afterEcho.recycle()
        } finally { view.resetChoiceExit(); view.setImageBitmap(null); source.recycle() }
    }

    @Test fun `only the selected letters expand while the plane sharp text and surrounding render stay fixed`() {
        val source = source()
        val plan = selectionPlan()
        val original = plan.capture(rows[1])
        original.draw(Canvas(source), 1f, 0)
        original.close()
        val view = view(source)
        val selectedRegions = listOf(regions[0], regions[1].copy(selectionVisual = plan))
        try {
            view.startChoiceExit(ChoiceExitFrame.capture(source, selectedRegions, 1),
                SystemClock.elapsedRealtime() - 60) {}
            val echo = pixels(view)
            var changed = 0
            for (y in 0 until source.height) for (x in 0 until source.width) {
                val before = source.getPixel(x, y)
                val after = echo.getPixel(x, y)
                if (!rows[1].contains(x, y)) assertEquals(before, after, "outside selected row at $x,$y")
                else {
                    if (before != after) changed++
                    if (before == Color.WHITE) assertEquals(Color.WHITE, after, "sharp letter moved")
                }
            }
            assertTrue(changed > 0, "expected a visible text echo")
            assertEquals(Color.BLUE, echo.getPixel(30, 110), "background must not grow or brighten")
            assertEquals(Color.BLUE, echo.getPixel(290, 110))
            echo.recycle()
            view.resetChoiceExit()
            for (time in listOf(0L, 300L, 600L)) {
                view.startChoiceExit(ChoiceExitFrame.capture(source, selectedRegions, 1),
                    SystemClock.elapsedRealtime() - time) {}
                val after = pixels(view)
                for (y in rows[1].top until rows[1].bottom) for (x in rows[1].left until rows[1].right) {
                    assertEquals(source.getPixel(x, y), after.getPixel(x, y), "static selected text at $time")
                }
                after.recycle(); view.resetChoiceExit()
            }
        } finally {
            view.resetChoiceExit(); view.setImageBitmap(null); source.recycle()
        }
    }

    @Test fun `large echo clips long text to its row at every sampled frame`() {
        val source = source()
        val bounds = RectF(rows[1])
        val background = Paint().apply { color = Color.BLUE }
        val letters = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 23f }
        val plan = ChoiceSelectionVisualPlan(bounds, 0f,
            drawBackground = { it.drawRect(bounds, background) },
            drawText = { it.drawText("A long selected choice", 22f, 120f, letters) })
        plan.capture(rows[1]).use { it.draw(Canvas(source), 1f, 0) }
        val choices = listOf(regions[0], regions[1].copy(selectionVisual = plan))
        val actual = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888)
        val normal = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888)
        val frame = ChoiceExitFrame.capture(source, choices, 1)
        val reference = ChoiceExitFrame.capture(source, regions, 1)
        try {
            for (time in listOf(33L, 100L, 150L, 200L, 250L, 300L)) {
                actual.eraseColor(Color.TRANSPARENT); normal.eraseColor(Color.TRANSPARENT)
                frame.draw(Canvas(actual), time); reference.draw(Canvas(normal), time)
                var changes = 0
                for (y in 0 until 240) for (x in 0 until 320) {
                    if (!rows[1].contains(x, y)) {
                        assertEquals(normal.getPixel(x, y), actual.getPixel(x, y), "outside row at $time/$x,$y")
                    } else {
                        if (actual.getPixel(x, y) != normal.getPixel(x, y)) changes++
                        if (source.getPixel(x, y) == Color.WHITE) {
                            assertEquals(Color.WHITE, actual.getPixel(x, y), "sharp text at $time/$x,$y")
                        }
                    }
                }
                if (time < 300) assertTrue(changes > 0, "visible echo at $time")
                else assertEquals(0, changes, "echo must end at 300ms")
            }
        } finally {
            frame.close(); reference.close(); actual.recycle(); normal.recycle(); source.recycle()
        }
    }

    @Test fun `selection layers are lazy selected only owned and skipped for late callbacks`() {
        var unselectedCaptures = 0
        var selectedCaptures = 0
        val source = source()
        val choices = listOf(regions[0].copy(selectionVisual = selectionPlan { unselectedCaptures++ }),
            regions[1].copy(selectionVisual = selectionPlan { selectedCaptures++ }))
        val output = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888)
        val frame = ChoiceExitFrame.capture(source, choices, 1)
        try {
            assertEquals(0, selectedCaptures)
            frame.draw(Canvas(output), 60)
            frame.draw(Canvas(output), 150)
            assertEquals(0, unselectedCaptures)
            assertEquals(1, selectedCaptures)
            frame.draw(Canvas(output), 300)
            assertFalse(frame.hasSelectedEffect)
            frame.close(); frame.close()
            assertFalse(source.isRecycled)
            val late = ChoiceExitFrame.capture(source, choices, 1)
            late.draw(Canvas(output), 400)
            assertEquals(1, selectedCaptures, "late callback must not allocate a missed effect")
            late.close()
        } finally { frame.close(); output.recycle(); source.recycle() }
    }

    @Test fun `optional selection layer failure keeps the existing fade and completion`() {
        val source = source()
        val failing = ChoiceSelectionVisualPlan(RectF(rows[1]), 0f,
            drawBackground = { error("fixture capture failure") }, drawText = {})
        val frame = ChoiceExitFrame.capture(source, listOf(regions[1].copy(selectionVisual = failing)), 0)
        val output = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888)
        try {
            frame.draw(Canvas(output), 60)
            assertEquals(Color.BLUE, output.getPixel(100, 110))
            assertFalse(frame.hasSelectedEffect)
            output.eraseColor(Color.TRANSPARENT)
            frame.draw(Canvas(output), 1_000)
            assertTrue(Color.alpha(output.getPixel(100, 110)) in 62..63)
        } finally { frame.close(); output.recycle(); source.recycle() }
    }

    @Test fun `single choice requests display frames instead of skipping its echo during the hold`() {
        val source = source()
        val view = view(source)
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        var captured = 0
        try {
            activity.get().setContentView(view)
            view.layout(0, 0, 320, 240)
            view.startChoiceExit(ChoiceExitFrame.capture(source,
                listOf(regions[1].copy(selectionVisual = selectionPlan { captured++ })), 0),
                SystemClock.elapsedRealtime()) {}
            assertNotNull(DialoguePlaybackView::class.java.getDeclaredField("choiceFrameCallback")
                .apply { isAccessible = true }.get(view), "must request a frame before the 700ms hold ends")
            // Robolectric's automatic traversals may advance to the next idle boundary (300ms).
            // Sample the in-progress frame directly without consuming that whole traversal queue.
            ShadowSystemClock.advanceBy(Duration.ofMillis(60))
            val echo = pixels(view)
            assertEquals(1, captured)
            echo.recycle()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_300))
            assertFalse(view.hasChoiceExit())
        } finally {
            view.resetChoiceExit(); view.setImageBitmap(null)
            activity.pause().stop().destroy(); source.recycle()
        }
    }

    @Test fun `frozen renderer plans reconstruct ruby dots fonts and bilingual baselines after buffer reuse`() {
        val renderer = OverlayRenderer(RuntimeEnvironment.getApplication())
        val source = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
        val box = Rect(300, 250, 1600, 385)
        try {
            for (locale in listOf("zh-Hans", "zh-Hant", "en")) for (bilingual in listOf(false, true)) {
                for (ruby in listOf(false, true)) {
                    val text = if (ruby) when (locale) {
                        "en" -> "The Observatory〈Chaldea〉"
                        "zh-Hant" -> "觀星臺〈迦勒底〉"
                        else -> "观星台〈迦勒底〉"
                    } else if (locale == "en") "Let's go together." else "一起前進吧。"
                    val instruction = RenderInstruction(
                        ClassifiedRegion(TextRegion.CHOICE_BUTTON, emptyList(), box, OcrEngineId.PADDLE_OCR),
                        text, "星見台〈カルデア〉へ", targetLocale = locale, showOriginalText = bilingual,
                        emphasisRanges = if (ruby) emptyList() else listOf(EmphasisRange(0, text.length - 1)))
                    val result = renderer.renderWithChoices(source, listOf(instruction))
                    val expected = result.bitmap.copy(Bitmap.Config.ARGB_8888, false)
                    val region = result.choices.single()
                    if (locale == "en" && bilingual && ruby) {
                        System.getenv("FGO_CHOICE_PREVIEW_DIR")?.let { path ->
                            val directory = File(path).apply { mkdirs() }
                            val frame = ChoiceExitFrame.capture(expected, result.choices, 0)
                            val output = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
                            try {
                                for (time in listOf(0L, 33L, 60L, 100L, 150L, 200L, 250L, 300L, 600L, 700L, 850L, 1_000L, 1_150L, 1_300L)) {
                                    output.eraseColor(Color.TRANSPARENT)
                                    frame.draw(Canvas(output), time)
                                    val b = region.paintBounds
                                    val cropped = Bitmap.createBitmap(output, b.left, b.top, b.width(), b.height())
                                    try {
                                        File(directory, "selected-$time.png").outputStream().use {
                                            cropped.compress(Bitmap.CompressFormat.PNG, 100, it)
                                        }
                                    } finally { cropped.recycle() }
                                }
                            } finally { frame.close(); output.recycle() }
                        }
                    }
                    // A later scene changes both the shared paint/font and the reusable output bitmap.
                    renderer.render(source, listOf(instruction.copy(translatedText = "A different scene",
                        targetLocale = if (locale == "en") "zh-Hant" else "en")), 1920, 1080)
                    val layers = assertNotNull(region.selectionVisual).capture(region.paintBounds)
                    val actual = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
                    try {
                        layers.draw(Canvas(actual), 1f, 0)
                        val bounds = region.paintBounds
                        for (y in bounds.top until bounds.bottom) for (x in bounds.left until bounds.right) {
                            val before = expected.getPixel(x, y)
                            val after = actual.getPixel(x, y)
                            assertTrue((0..3).all { channel ->
                                abs((before ushr (channel * 8) and 255) - (after ushr (channel * 8) and 255)) <= 1
                            }, "changed layout/style at $x,$y: $locale/$bilingual/ruby=$ruby, $before vs $after")
                        }
                    } finally { layers.close(); actual.recycle(); expected.recycle() }
                }
            }
        } finally { source.recycle(); renderer.releaseBuffers() }
    }

    @Test fun `only choice patches fade and name dialogue and transparent surroundings remain unchanged`() {
        for (elapsed in listOf(0L, 225L, 350L, 600L, 700L, 850L, 1_000L, 1_150L, 1_300L)) {
            val source = source()
            val view = view(source)
            view.startChoiceExit(ChoiceExitFrame.capture(source, regions, 1),
                SystemClock.elapsedRealtime() - elapsed) {}
            val result = pixels(view)
            assertTrue(abs(ChoiceExitController.alpha(false, elapsed) - Color.alpha(result.getPixel(100, 40))) <= 1,
                "unselected alpha at $elapsed (Skia may round alpha down by one)")
            assertTrue(abs(ChoiceExitController.alpha(true, elapsed) - Color.alpha(result.getPixel(100, 110))) <= 1,
                "selected alpha at $elapsed")
            assertEquals(Color.YELLOW, result.getPixel(40, 165), "name changed at $elapsed")
            assertEquals(Color.GREEN, result.getPixel(40, 205), "dialogue changed at $elapsed")
            assertEquals(Color.TRANSPARENT, result.getPixel(5, 5))
            view.resetChoiceExit(); view.setImageBitmap(null)
            result.recycle(); source.recycle()
        }
    }

    @Test fun `choice patches own their pixels even when the renderer reuses its bitmap`() {
        val source = source()
        val frame = ChoiceExitFrame.capture(source, regions, 1)
        source.eraseColor(Color.MAGENTA)
        val result = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888)
        frame.draw(Canvas(result), 0)
        assertEquals(Color.RED, result.getPixel(100, 40))
        assertEquals(Color.BLUE, result.getPixel(100, 110))
        frame.close(); frame.close()
        assertFalse(source.isRecycled)
        result.recycle(); source.recycle()
    }

    @Test fun `whole bitmap patches also own their pixels and never recycle the source`() {
        val source = source()
        val frame = ChoiceExitFrame.capture(source,
            listOf(ChoiceRenderRegion(Rect(0, 0, 320, 240), Rect(0, 0, 320, 240))), 0)
        frame.close()
        assertFalse(source.isRecycled)
        source.recycle()
    }

    @Test fun `other render kinds can hide on their original deadline while choices keep exiting`() {
        val source = source()
        val view = view(source)
        view.startChoiceExit(ChoiceExitFrame.capture(source, regions, 1), SystemClock.elapsedRealtime() - 350) {}
        view.hideBaseDuringChoiceExit()
        view.pausePlayback() // Gesture passthrough pauses dialogue, never the choice clock.
        val result = pixels(view)
        assertTrue(Color.alpha(result.getPixel(100, 40)) in 62..63)
        assertEquals(Color.BLUE, result.getPixel(100, 110))
        assertEquals(Color.TRANSPARENT, result.getPixel(40, 165))
        assertEquals(Color.TRANSPARENT, result.getPixel(40, 205))
        view.resetChoiceExit(); view.setImageBitmap(null)
        result.recycle(); source.recycle()
    }

    @Test fun `density scaled drawables retain exact choice locations`() {
        for (density in listOf(160, 480)) {
            val source = source().apply { this.density = density }
            val view = view(source)
            view.startChoiceExit(ChoiceExitFrame.capture(source, regions, 1), SystemClock.elapsedRealtime() - 1_000) {}
            val result = pixels(view)
            val matrix = FloatArray(9).also { view.imageMatrix.getValues(it) }
            assertEquals(Color.TRANSPARENT, result.getPixel(100, 40),
                "density=$density intrinsic=${view.drawable.intrinsicWidth}x${view.drawable.intrinsicHeight} " +
                    "matrix=${matrix.toList()} view=${view.width}x${view.height}")
            assertTrue(Color.alpha(result.getPixel(100, 110)) in 62..63)
            assertEquals(Color.GREEN, result.getPixel(40, 205))
            view.resetChoiceExit(); view.setImageBitmap(null)
            result.recycle(); source.recycle()
        }
    }

    @Test fun `attached view completes once on the display clock even while dialogue playback is paused`() {
        val source = source()
        val view = view(source)
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        val finished = mutableListOf<Boolean>()
        try {
            activity.get().setContentView(view)
            view.layout(0, 0, 320, 240)
            view.startChoiceExit(ChoiceExitFrame.capture(source, regions, 1), SystemClock.elapsedRealtime()) { finished += it }
            view.pausePlayback()
            // Stay inside the static gap; Robolectric traversals can drain the next fade once its wake runs.
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(650))
            assertTrue(view.hasChoiceExit())
            assertTrue(finished.isEmpty())
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(700))
            assertFalse(view.hasChoiceExit())
            assertEquals(listOf(true), finished)
            view.cancelChoiceExit()
            assertEquals(listOf(true), finished)
        } finally {
            view.resetChoiceExit(); view.setImageBitmap(null)
            activity.pause().stop().destroy()
            source.recycle()
        }
    }

    @Test fun `already dispatched old callbacks cannot reschedule or finish a newer choice frame`() {
        val source = source()
        val view = view(source)
        fun field(name: String) = DialoguePlaybackView::class.java.getDeclaredField(name)
            .apply { isAccessible = true }.get(view)
        val completions = mutableListOf<Boolean>()
        try {
            view.startChoiceExit(ChoiceExitFrame.capture(source, regions, 0), SystemClock.elapsedRealtime() - 1_000) {}
            val oldCallback = field("choiceFrameCallback") as android.view.Choreographer.FrameCallback
            view.startChoiceExit(ChoiceExitFrame.capture(source, regions, 1), SystemClock.elapsedRealtime()) { completions += it }
            val wake = assertNotNull(field("choiceWake"))
            oldCallback.doFrame(0)
            assertSame(wake, field("choiceWake"))
            assertTrue(completions.isEmpty())
            view.resetChoiceExit()
            (wake as Runnable).run()
            assertFalse(view.hasChoiceExit())
            assertEquals(listOf(false), completions)
        } finally {
            view.resetChoiceExit(); view.setImageBitmap(null); source.recycle()
        }
    }
}
