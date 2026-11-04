package com.fgogotran.overlay

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.view.WindowMetrics
import com.fgogotran.diagnostic.DiagnosticEventStore
import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import kotlin.test.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChoiceExitOverlayTest {
    private class Harness : AutoCloseable {
        val app = RuntimeEnvironment.getApplication()
        val overlay = TranslationOverlay(app, DiagnosticEventStore(app))
        val bitmap = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888).apply { density = Bitmap.DENSITY_NONE }
        var view: View? = null
        var width = 320
        var height = 240
        private val wm = Proxy.newProxyInstance(WindowManager::class.java.classLoader,
            arrayOf(WindowManager::class.java)) { _, method, args ->
            when (method.name) {
                "getCurrentWindowMetrics" -> WindowMetrics(Rect(0, 0, width, height), WindowInsets.Builder().build())
                "addView", "updateViewLayout" -> {
                    view = args!![0] as View
                    view!!.layoutParams = args[1] as ViewGroup.LayoutParams
                    view!!.layout(0, 0, width, height)
                    null
                }
                "removeView" -> { view = null; null }
                "toString" -> "ChoiceExitWindowManager"
                else -> null
            }
        } as WindowManager

        init {
            overlay.init(app, 320, 240, { _, _ -> })
            TranslationOverlay::class.java.getDeclaredField("windowManager").apply { isAccessible = true }.set(overlay, wm)
        }

        fun show(count: Int = 2, mixed: Boolean = false): List<ChoiceRenderRegion> {
            bitmap.eraseColor(Color.TRANSPARENT)
            val paint = Paint().apply { color = Color.WHITE }
            val regions = (0 until count).map { index ->
                val bounds = Rect(20, 12 + index * 32, 280, 36 + index * 32)
                Canvas(bitmap).drawRect(bounds, paint)
                ChoiceRenderRegion(Rect(bounds), Rect(bounds))
            }
            if (mixed) {
                paint.color = Color.GREEN
                Canvas(bitmap).drawRect(Rect(10, 210, 310, 235), paint)
            }
            assertTrue(overlay.updateStoryImage(bitmap, regions, mixed))
            return regions
        }

        fun touch(action: Int, x: Float, y: Float) {
            val event = MotionEvent.obtain(0, 1, action, x, y, 0)
            try { view!!.dispatchTouchEvent(event) } finally { event.recycle() }
        }

        fun tap(x: Float, y: Float): TranslationOverlay.ChoiceTap? {
            touch(MotionEvent.ACTION_DOWN, x, y)
            touch(MotionEvent.ACTION_UP, x, y)
            return overlay.takeChoiceTap()
        }

        fun acceptsInput() = ((view!!.layoutParams as WindowManager.LayoutParams).flags and
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) == 0

        fun pixels(): Bitmap = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888).also { view!!.draw(Canvas(it)) }

        override fun close() {
            overlay.destroy()
            bitmap.recycle()
        }
    }

    @Test fun `one through six rows identify the actual selected button without a story handoff`() {
        Harness().use { h ->
            for (count in 1..6) {
                val regions = h.show(count)
                regions.forEachIndexed { index, region ->
                    val tap = assertNotNull(h.tap(60f, region.hitBounds.centerY().toFloat()))
                    assertEquals(index, tap.selectedIndex)
                    assertEquals(h.overlay.renderGeneration, tap.generation)
                    assertNull(h.overlay.takeChoiceTap(), "ticket must be consumed once")
                }
            }
        }
    }

    @Test fun `outside drags cancelled multi touch and controls do not select an option`() {
        Harness().use { h ->
            h.show()
            assertNull(h.tap(5f, 20f))
            h.touch(MotionEvent.ACTION_DOWN, 60f, 20f)
            h.touch(MotionEvent.ACTION_MOVE, 140f, 20f)
            h.touch(MotionEvent.ACTION_UP, 140f, 20f)
            assertNull(h.overlay.takeChoiceTap())
            for (action in listOf(MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN)) {
                h.touch(MotionEvent.ACTION_DOWN, 60f, 20f)
                h.touch(action, 60f, 20f)
                h.touch(MotionEvent.ACTION_UP, 60f, 20f)
                assertNull(h.overlay.takeChoiceTap())
            }
            assertNull(h.tap(315f, 10f))
        }
    }

    @Test fun `failed replay keeps the list retryable and a later render invalidates its ticket`() {
        Harness().use { h ->
            h.show()
            val old = assertNotNull(h.tap(60f, 20f))
            assertNotNull(h.tap(60f, 20f)) // Failure never begins the exit.
            h.show() // Same source text and same reusable bitmap, but a new presentation.
            assertNull(h.overlay.beginChoiceExit(old, SystemClock.elapsedRealtime()))
            assertTrue(h.overlay.isShowing())
            assertTrue(h.acceptsInput())
        }
    }

    @Test fun `capture cancels exit and restoring mixed content never resurrects retired choices`() {
        Harness().use { h ->
            h.show(mixed = true)
            val tap = assertNotNull(h.tap(60f, 20f))
            val exit = assertNotNull(h.overlay.beginChoiceExit(tap, SystemClock.elapsedRealtime()))
            h.overlay.setTranslatedOverlayTouchable(false)
            h.overlay.hideNonChoiceContentDuringExit(tap.generation)
            h.overlay.hideForCapture()
            assertFalse(runBlocking { exit.await() })
            h.overlay.restoreAfterCapture()
            assertTrue(h.overlay.isShowing())
            val result = h.pixels()
            assertEquals(Color.TRANSPARENT, result.getPixel(60, 20))
            assertEquals(Color.TRANSPARENT, result.getPixel(60, 52))
            assertEquals(Color.GREEN, result.getPixel(60, 220))
            result.recycle()
            assertNull(h.tap(60f, 20f))
        }
    }

    @Test fun `a completed choice only frame stays hidden after capture restoration`() {
        Harness().use { h ->
            h.show()
            val tap = assertNotNull(h.tap(60f, 20f))
            val exit = assertNotNull(h.overlay.beginChoiceExit(tap,
                SystemClock.elapsedRealtime() - ChoiceExitController.DURATION_MS))
            assertTrue(runBlocking { exit.await() })
            assertFalse(h.overlay.isShowing())
            h.overlay.restoreAfterCapture()
            assertFalse(h.overlay.isShowing())
            assertFalse(h.acceptsInput())
        }
    }

    @Test fun `menu cancellation cannot reclaim input or resume old choices when menu closes`() {
        Harness().use { h ->
            h.show()
            val tap = assertNotNull(h.tap(60f, 20f))
            val exit = assertNotNull(h.overlay.beginChoiceExit(tap, SystemClock.elapsedRealtime()))
            h.overlay.setTranslatedOverlayTouchable(false)
            h.overlay.setControlsVisible(true)
            assertFalse(runBlocking { exit.await() })
            h.overlay.setControlsVisible(false)
            assertFalse(h.overlay.isShowing())
            assertFalse(h.acceptsInput())
        }
    }

    @Test fun `new render stop and resize cancel old completion without changing the next image`() {
        Harness().use { h ->
            h.show()
            val tap = assertNotNull(h.tap(60f, 20f))
            val exit = assertNotNull(h.overlay.beginChoiceExit(tap, SystemClock.elapsedRealtime()))
            h.show()
            assertFalse(runBlocking { exit.await() })
            h.overlay.cancelChoiceExit(tap.generation)
            assertTrue(h.overlay.isShowing())
            assertTrue(h.acceptsInput())
            val next = assertNotNull(h.tap(60f, 20f))
            val resized = assertNotNull(h.overlay.beginChoiceExit(next, SystemClock.elapsedRealtime()))
            h.width = 640
            h.overlay.restoreAfterCapture()
            assertFalse(runBlocking { resized.await() })
            assertFalse(h.overlay.isShowing())
        }
    }

    @Test fun `menu closing cannot restore an invisible input target from a mixed scene exit`() {
        Harness().use { h ->
            h.show(mixed = true)
            val tap = assertNotNull(h.tap(60f, 20f))
            val exit = assertNotNull(h.overlay.beginChoiceExit(tap, SystemClock.elapsedRealtime()))
            h.overlay.setTranslatedOverlayTouchable(false)
            h.overlay.hideNonChoiceContentDuringExit(tap.generation)
            h.overlay.setControlsVisible(true)
            assertFalse(runBlocking { exit.await() })
            h.overlay.setControlsVisible(false)
            assertFalse(h.overlay.isShowing())
            assertFalse(h.acceptsInput())
        }
    }
}
