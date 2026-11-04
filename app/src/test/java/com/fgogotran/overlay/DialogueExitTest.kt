package com.fgogotran.overlay

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.Looper
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.view.WindowMetrics
import android.widget.ImageView
import com.fgogotran.diagnostic.DiagnosticEventStore
import com.fgogotran.ocr.OcrEngineId
import com.fgogotran.text.EmphasisRange
import java.lang.reflect.Proxy
import java.time.Duration
import kotlinx.coroutines.*
import kotlin.test.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class DialogueExitTest {
    private class Harness : AutoCloseable {
        val app = RuntimeEnvironment.getApplication()
        val overlay = TranslationOverlay(app, DiagnosticEventStore(app))
        val bitmap = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888).apply { density = Bitmap.DENSITY_NONE }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        lateinit var view: DialoguePlaybackView
        var width = 320
        var height = 240
        private val wm = Proxy.newProxyInstance(WindowManager::class.java.classLoader,
            arrayOf(WindowManager::class.java)) { _, method, args ->
            when (method.name) {
                "getCurrentWindowMetrics" -> WindowMetrics(Rect(0, 0, width, height), WindowInsets.Builder().build())
                "addView", "updateViewLayout" -> {
                    view = args!![0] as DialoguePlaybackView
                    view.layoutParams = args[1] as ViewGroup.LayoutParams
                    view.layout(0, 0, width, height)
                    null
                }
                "removeView" -> null
                "toString" -> "DialogueExitWindowManager"
                else -> null
            }
        } as WindowManager

        init {
            overlay.init(app, 320, 240, { _, _ -> })
            TranslationOverlay::class.java.getDeclaredField("windowManager").apply { isAccessible = true }.set(overlay, wm)
        }

        fun show(dialogue: Boolean = true, choice: Boolean = false) {
            bitmap.eraseColor(Color.TRANSPARENT)
            val paint = Paint()
            val canvas = Canvas(bitmap)
            paint.color = Color.YELLOW; canvas.drawRect(Rect(20, 20, 150, 50), paint)
            paint.color = Color.GREEN; canvas.drawRect(Rect(20, 120, 300, 225), paint)
            paint.color = Color.WHITE; canvas.drawRect(Rect(40, 150, 55, 180), paint)
            val choices = if (choice) listOf(ChoiceRenderRegion(Rect(20, 70, 300, 100), Rect(20, 70, 300, 100))) else emptyList()
            if (choice) { paint.color = Color.RED; canvas.drawRect(choices.single().paintBounds, paint) }
            assertTrue(overlay.updateStoryImage(bitmap, choices, true,
                if (dialogue) listOf(Rect(20, 120, 300, 225)) else emptyList()))
        }

        fun begin() = scope.async(start = CoroutineStart.UNDISPATCHED) {
            overlay.awaitDialogueExitTail(overlay.renderGeneration)
        }
        fun pixels() = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
        fun acceptsInput() = ((view.layoutParams as WindowManager.LayoutParams).flags and
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) == 0
        override fun close() { scope.cancel(); overlay.destroy(); if (!bitmap.isRecycled) bitmap.recycle() }
    }

    private fun advance(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    @Test fun `dialogue and name keep their pixels for the extra 60ms and retire together`() {
        Harness().use { h ->
            h.show()
            h.overlay.setTranslatedOverlayTouchable(false) // Successful replay already owns input.
            val completion = h.begin()
            advance(59)
            assertFalse(completion.isCompleted)
            assertTrue(h.overlay.isShowing())
            assertFalse(h.acceptsInput())
            val held = h.pixels()
            try {
                assertEquals(Color.YELLOW, held.getPixel(40, 30), "name must follow the dialogue deadline")
                assertEquals(Color.GREEN, held.getPixel(100, 200))
                assertEquals(Color.WHITE, held.getPixel(45, 160))
            } finally { held.recycle() }
            advance(1)
            assertTrue(runBlocking { completion.await() })
            assertFalse(h.overlay.isShowing())
            assertFalse(h.acceptsInput())
            val retired = h.pixels()
            try {
                assertEquals(Color.TRANSPARENT, retired.getPixel(40, 30), "name must retire with dialogue")
                assertEquals(Color.TRANSPARENT, retired.getPixel(100, 200))
                assertEquals(Color.TRANSPARENT, retired.getPixel(45, 160))
            } finally { retired.recycle() }
            h.overlay.restoreAfterCapture()
            assertFalse(h.overlay.isShowing(), "retired dialogue cannot be resurrected")
        }
    }

    @Test fun `a new scene replaces the mask and old completion cannot hide the new image`() {
        Harness().use { h ->
            h.show()
            val completion = h.begin()
            h.show()
            advance(60)
            assertFalse(runBlocking { completion.await() })
            assertTrue(h.overlay.isShowing())
            assertTrue(h.acceptsInput())
            assertFalse(h.view.hasDialogueExitTail())
            val next = h.pixels()
            try { assertEquals(Color.YELLOW, next.getPixel(40, 30)) } finally { next.recycle() }
        }
    }

    @Test fun `menu capture stop and resize cancel the tail without leaving an invisible input target`() {
        for (reason in listOf("menu", "capture", "stop", "resize")) Harness().use { h ->
            h.show()
            h.overlay.setTranslatedOverlayTouchable(false)
            val completion = h.begin()
            when (reason) {
                "menu" -> { h.overlay.setControlsVisible(true); h.overlay.setControlsVisible(false) }
                "capture" -> h.overlay.hideForCapture()
                "stop" -> h.overlay.hide()
                else -> { h.width = 640; h.overlay.restoreAfterCapture() }
            }
            advance(60)
            assertFalse(runBlocking { completion.await() }, reason)
            h.overlay.restoreAfterCapture()
            assertFalse(h.overlay.isShowing(), reason)
            assertFalse(h.acceptsInput(), reason)
        }
    }

    @Test fun `canceling the replay owner releases the tail immediately`() {
        Harness().use { h ->
            h.show()
            val completion = h.begin()
            completion.cancel()
            advance(0)
            assertFalse(h.view.hasDialogueExitTail())
            assertFalse(h.overlay.isShowing())
            h.overlay.restoreAfterCapture()
            assertFalse(h.overlay.isShowing())
        }
    }

    @Test fun `name only and all choice frames have no extra wait or pixel changes`() {
        for ((dialogue, choices) in listOf(false to false, false to true, true to true)) Harness().use { h ->
            h.show(dialogue, choices)
            val before = h.pixels()
            val completion = h.begin()
            assertTrue(completion.isCompleted)
            assertTrue(runBlocking { completion.await() })
            assertFalse(h.view.hasDialogueExitTail())
            assertTrue(h.overlay.isShowing())
            val after = h.pixels()
            try { assertTrue(before.sameAs(after)) } finally { before.recycle(); after.recycle() }
        }
    }

    @Test fun `tail keeps the complete name and dialogue presentation at different drawable densities`() {
        for (density in listOf(160, 480)) {
            val source = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888).apply {
                this.density = density
                eraseColor(Color.GREEN)
            }
            val view = DialoguePlaybackView(RuntimeEnvironment.getApplication()).apply {
                scaleType = ImageView.ScaleType.FIT_XY
                setImageBitmap(source)
                layout(0, 0, 640, 480)
            }
            val before = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(before))
            view.showDialogueExitTail()
            val output = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888)
            try {
                view.draw(Canvas(output))
                assertTrue(before.sameAs(output), "density=$density moved or clipped the presentation")
            } finally { view.setImageBitmap(null); before.recycle(); output.recycle(); source.recycle() }
        }
    }

    @Test fun `static and playback paths retain the same complete ruby emphasis and bilingual dialogue plane`() {
        val renderer = OverlayRenderer(RuntimeEnvironment.getApplication())
        val source = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
        try {
            for (locale in listOf("zh-Hans", "zh-Hant", "en")) for (bilingual in listOf(false, true)) {
                val text = when (locale) {
                    "en" -> "The Observatory〈Chaldea〉.\nOur story continues."
                    "zh-Hant" -> "觀星臺〈迦勒底〉。\n故事繼續。"
                    else -> "观星台〈迦勒底〉。\n故事继续。"
                }
                val input = listOf(RenderInstruction(ClassifiedRegion(TextRegion.DIALOGUE_BOX, emptyList(),
                    Rect(35, 830, 1805, 1055), OcrEngineId.PADDLE_OCR), text,
                    "星見台〈カルデア〉。\n物語は続きます。", targetLocale = locale, showOriginalText = bilingual,
                    emphasisRanges = listOf(EmphasisRange(text.indexOf('\n') + 1, text.length - 1))),
                    RenderInstruction(ClassifiedRegion(TextRegion.NAME_LABEL, emptyList(),
                        Rect(35, 740, 600, 828), OcrEngineId.PADDLE_OCR,
                        sourcePlateBounds = Rect(35, 740, 600, 828),
                        sourceNameTextBounds = Rect(90, 755, 380, 815)), "Mash", targetLocale = locale))
                val bounds = mutableListOf<Rect>()
                val static = renderer.renderWithChoices(source, input, bounds).bitmap
                val expected = static.copy(Bitmap.Config.ARGB_8888, false)
                val frame = renderer.prepareDialoguePlayback(source, input, DialoguePlaybackSettings())
                assertEquals(frame.panels.map { it.bounds }, bounds)
                assertTrue(frame.bitmap.sameAs(expected))
                Harness().use { h ->
                    h.width = 1920
                    h.height = 1080
                    assertTrue(h.overlay.updatePlayback(frame))
                    val completion = h.begin()
                    assertFalse(completion.isCompleted, "playback metadata must enable the read tail")
                    val output = h.pixels()
                    try {
                        assertTrue(expected.sameAs(output), "$locale/$bilingual name or dialogue pixels changed")
                    } finally { output.recycle() }
                    advance(60)
                    assertTrue(runBlocking { completion.await() })
                }
                expected.recycle(); frame.close()
            }
        } finally { source.recycle(); renderer.releaseBuffers() }
    }
}
