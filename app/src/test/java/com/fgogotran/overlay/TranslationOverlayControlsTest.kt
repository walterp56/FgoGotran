package com.fgogotran.overlay

import android.graphics.Bitmap
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.view.WindowMetrics
import com.fgogotran.diagnostic.DiagnosticEventStore
import com.fgogotran.translation.TranslationTrigger
import java.lang.reflect.Proxy
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.*

/** Exercise actual overlay transitions/flags against a recording WindowManager, without a device. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TranslationOverlayControlsTest {
    private class Harness : AutoCloseable {
        val app=RuntimeEnvironment.getApplication()
        val overlay=TranslationOverlay(app,DiagnosticEventStore(app))
        val bitmap=Bitmap.createBitmap(320,240,Bitmap.Config.ARGB_8888)
        var view:View?=null
        private val wm=Proxy.newProxyInstance(WindowManager::class.java.classLoader,
            arrayOf(WindowManager::class.java)) { _,method,args ->
            when(method.name) {
                "getCurrentWindowMetrics" -> WindowMetrics(Rect(0,0,320,240),WindowInsets.Builder().build())
                "addView","updateViewLayout" -> {
                    view=args!![0] as View
                    view!!.layoutParams=args[1] as ViewGroup.LayoutParams
                    null
                }
                "removeView" -> { view=null;null }
                "toString" -> "RecordingWindowManager"
                else -> null
            }
        } as WindowManager
        init {
            overlay.init(app,320,240,{ _,_ -> })
            TranslationOverlay::class.java.getDeclaredField("windowManager").apply { isAccessible=true }.set(overlay,wm)
        }
        fun show()=overlay.showTranslatedImage(bitmap)
        fun acceptsInput():Boolean = ((view!!.layoutParams as WindowManager.LayoutParams).flags and
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)==0
        fun playbackPaused():Boolean=DialoguePlaybackView::class.java.getDeclaredField("paused")
            .apply { isAccessible=true }.getBoolean(view)
        override fun close() {
            overlay.destroy()
            if(!bitmap.isRecycled)bitmap.recycle()
        }
    }

    @Test fun `menu yields translation input while retaining the rendered image and restores on close`() {
        Harness().use { h ->
            h.show();assertTrue(h.acceptsInput())
            h.overlay.setControlsVisible(true)
            assertTrue(h.overlay.isShowing())
            assertFalse(h.acceptsInput(),"Translation still intercepts the lower menu")
            h.overlay.setControlsVisible(false)
            assertTrue(h.acceptsInput())
        }
    }
    @Test fun `late render or replay completion cannot recapture input over visible controls`() {
        Harness().use { h ->
            h.overlay.setControlsVisible(true)
            h.show();assertFalse(h.acceptsInput(),"New window ignored modal ownership")
            h.overlay.updateImage(h.bitmap);assertFalse(h.acceptsInput())
            assertTrue(h.overlay.setTranslatedOverlayTouchable(true))
            assertFalse(h.acceptsInput(),"Pending tap finally block stole menu input")
            h.overlay.hideForCapture();h.overlay.restoreAfterCapture()
            assertTrue(h.overlay.isShowing());assertFalse(h.acceptsInput())
            h.overlay.setControlsVisible(false);assertTrue(h.acceptsInput())
        }
    }
    @Test fun `closing controls does not cancel an independent gesture passthrough or revive a hidden scene`() {
        Harness().use { h ->
            h.show();h.overlay.setTranslatedOverlayTouchable(false)
            h.overlay.setControlsVisible(true);h.overlay.setControlsVisible(false)
            assertFalse(h.acceptsInput(),"Menu close overrode active tap replay")
            h.overlay.setTranslatedOverlayTouchable(true);assertTrue(h.acceptsInput())
            h.overlay.setControlsVisible(true);h.overlay.hide();h.overlay.setControlsVisible(false)
            assertFalse(h.overlay.isShowing());assertFalse(h.acceptsInput())
        }
    }
    @Test fun `late playback frame stays paused behind controls and resumes after they close`() {
        Harness().use { h ->
            h.overlay.setControlsVisible(true)
            val frame=DialoguePlaybackFrame(h.bitmap,emptyList(),emptyList(),DialoguePlaybackSettings(DialogueTextSpeed.NORMAL,1))
            assertTrue(h.overlay.updatePlayback(frame))
            assertFalse(h.acceptsInput());assertTrue(h.playbackPaused())
            h.overlay.setControlsVisible(false)
            assertTrue(h.acceptsInput());assertFalse(h.playbackPaused())
        }
    }
    @Test fun `closing menu keeps input with history until the last modal control closes`() {
        Harness().use { h ->
            try {
                h.show()
                TranslationTrigger.setMenuVisible(true)
                h.overlay.setControlsVisible(TranslationTrigger.isUiBlockingOcr())
                TranslationTrigger.setHistoryVisible(true)
                TranslationTrigger.setMenuVisible(false)
                h.overlay.setControlsVisible(TranslationTrigger.isUiBlockingOcr())
                assertFalse(h.acceptsInput())
                TranslationTrigger.setHistoryVisible(false)
                h.overlay.setControlsVisible(TranslationTrigger.isUiBlockingOcr())
                assertTrue(h.acceptsInput())
            } finally {
                TranslationTrigger.setMenuVisible(false);TranslationTrigger.setHistoryVisible(false)
            }
        }
    }
}
