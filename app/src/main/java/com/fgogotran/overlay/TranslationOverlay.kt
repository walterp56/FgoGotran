package com.fgogotran.overlay

import android.content.ComponentCallbacks
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import com.fgogotran.diagnostic.DiagnosticEventStore
import com.fgogotran.util.FgoLogger
import com.fgogotran.util.setFloatingHandleGestureExclusion
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages the TYPE_ACCESSIBILITY_OVERLAY system windows.
 *
 * The full-screen image overlay displays the rendered translated screenshot.
 * It is created/updated on each pipeline completion and removed when the user leaves FGO.
 *
 * ## Window flags
 * - The translated image receives a tap and asks the accessibility service to forward it to FGO.
 * - FLAG_LAYOUT_IN_SCREEN: overlay fills the entire screen, including behind status/nav bars.
 *
 * ## Lifecycle
 * init() → showTranslatedImage() → updateImage() (repeat) → hideAll() → destroy()
 */
@Singleton
class TranslationOverlay @Inject constructor(
    @ApplicationContext private val context: Context,
    private val diagnosticEventStore: DiagnosticEventStore
) {
    private var windowManager: WindowManager? = null
    private var overlayView: DialoguePlaybackView? = null
    private var isOverlayShowing = false
    private var overlayTouchable = true
    private var requestedOverlayTouchable = true
    private var controlsVisible = false
    private var screenWidth = 0
    private var screenHeight = 0
    private var latestTranslatedBitmap: Bitmap? = null
    private var onOverlayTap: ((Float, Float) -> Unit)? = null
    private var onOverlayTouch: ((MotionEvent) -> Boolean)? = null
    private var floatingHandleBounds: Rect? = null
    private var callbacksRegistered = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val componentCallbacks = object : ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: Configuration) {
            // Window metrics can settle after the configuration notification.
            mainHandler.post { refreshScreenBounds() }
        }
        override fun onLowMemory() = Unit
    }

    private val tag = "Overlay"

    fun setFloatingHandleBounds(screenBounds: Rect?) {
        floatingHandleBounds = screenBounds?.let { Rect(it) }
        updateFloatingHandleGestureExclusion()
    }

    private fun updateFloatingHandleGestureExclusion() {
        overlayView?.setFloatingHandleGestureExclusion(
            floatingHandleBounds.takeIf { isOverlayShowing && overlayTouchable }
        )
    }

    /**
     * Layout params for the full-screen translated image overlay.
     * TYPE_ACCESSIBILITY_OVERLAY is the correct type for accessibility-service-managed overlays.
     */
    private val overlayParams: WindowManager.LayoutParams
        get() = WindowManager.LayoutParams().apply {
            type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            format = PixelFormat.TRANSLUCENT
            width = screenWidth
            height = screenHeight
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

    private val overlayPassthroughParams: WindowManager.LayoutParams
        get() = WindowManager.LayoutParams().apply {
            type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            format = PixelFormat.TRANSLUCENT
            width = screenWidth
            height = screenHeight
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

    /**
     * Must be called once from the AccessibilityService after onServiceConnected().
     *
     * @param serviceContext the AccessibilityService context — TYPE_ACCESSIBILITY_OVERLAY
     *        requires a WindowManager obtained from the service, not the application
     * @param screenWidth raw screen width in pixels
     * @param screenHeight raw screen height in pixels
     */
    fun init(
        serviceContext: Context,
        screenWidth: Int,
        screenHeight: Int,
        onOverlayTap: (Float, Float) -> Unit,
        onOverlayTouch: ((MotionEvent) -> Boolean)? = null
    ) {
        this.screenWidth = screenWidth
        this.screenHeight = screenHeight
        this.onOverlayTap = onOverlayTap
        this.onOverlayTouch = onOverlayTouch
        // Must use the service context — Application context has no valid window token
        windowManager = serviceContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (!callbacksRegistered) {
            context.registerComponentCallbacks(componentCallbacks)
            callbacksRegistered = true
        }
        FgoLogger.info(tag, "Overlay initialized: ${screenWidth}x${screenHeight}")
    }

    /** Displays a current-sized rendered bitmap, reusing the native window when possible. */
    fun showTranslatedImage(bitmap: Bitmap) {
        overlayView?.clearPlayback()
        showOrUpdateTranslatedImage(bitmap)
    }

    /** Ownership transfers only after successful presentation. Caller closes rejected frames. */
    internal fun updatePlayback(frame: DialoguePlaybackFrame): Boolean {
        hideOverlayView(clearBitmap = true)
        showOrUpdateTranslatedImage(frame.bitmap)
        if (!isOverlayShowing || latestTranslatedBitmap !== frame.bitmap) return false
        overlayView?.startPlayback(frame)
        if (!overlayTouchable) overlayView?.pausePlayback()
        return true
    }

    internal fun handleDialoguePlaybackTouch(event: MotionEvent): Boolean =
        isOverlayShowing && overlayTouchable && overlayView?.handlePlaybackTouch(event) == true

    private fun showOrUpdateTranslatedImage(bitmap: Bitmap) {
        val wm = windowManager ?: return
        refreshScreenBounds()
        // A completed render may belong to the screen size from before a rotation/resize.
        // Never stretch it or retain it for a later capture restore.
        if (bitmap.width != screenWidth || bitmap.height != screenHeight) {
            hideOverlayView(clearBitmap = true)
            FgoLogger.debug(tag, "Ignoring old-sized translated image: ${bitmap.width}x${bitmap.height}, screen=${screenWidth}x${screenHeight}")
            return
        }

        overlayView?.let {
            if (!setTranslatedOverlayTouchable(true)) return
            latestTranslatedBitmap = bitmap
            it.setImageBitmap(bitmap)
            it.visibility = View.VISIBLE
            it.alpha = 1f
            isOverlayShowing = true
            if (overlayTouchable) it.resumePlayback() else it.pausePlayback()
            FgoLogger.info(tag, "Showing translated image: ${bitmap.width}x${bitmap.height}")
            return
        }

        val imageView = DialoguePlaybackView(context).apply {
            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateFloatingHandleGestureExclusion() }
            setImageBitmap(bitmap)
            alpha = 1f
            scaleType = ImageView.ScaleType.FIT_XY
            setOnTouchListener { _, event ->
                // A queued event from the old input target must not replay through a menu.
                if (controlsVisible) return@setOnTouchListener true
                if (onOverlayTouch?.invoke(event) == true) {
                    return@setOnTouchListener true
                }
                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    FgoLogger.debug(
                        this@TranslationOverlay.tag,
                        "Translated overlay tapped at ${event.rawX},${event.rawY}"
                    )
                    onOverlayTap?.invoke(event.rawX, event.rawY)
                }
                true
            }
        }

        try {
            requestedOverlayTouchable = true
            wm.addView(imageView, if (controlsVisible) overlayPassthroughParams else overlayParams)
        } catch (e: Exception) {
            imageView.setImageBitmap(null)
            isOverlayShowing = false
            overlayTouchable = true
            latestTranslatedBitmap = null
            diagnosticEventStore.record(
                level = DiagnosticEventStore.LEVEL_ERROR,
                category = DiagnosticEventStore.CATEGORY_APP_ERROR,
                eventId = "translated_overlay_show_failed",
                title = "翻译结果悬浮层显示失败",
                message = e.message.orEmpty().ifBlank { e::class.java.simpleName },
                detail = "TYPE_ACCESSIBILITY_OVERLAY addView failed",
                errorCode = e::class.java.simpleName
            )
            FgoLogger.warn(tag, "Failed to show translated overlay", e)
            return
        }
        overlayView = imageView
        latestTranslatedBitmap = bitmap
        isOverlayShowing = true
        overlayTouchable = !controlsVisible
        updateFloatingHandleGestureExclusion()
        FgoLogger.info(tag, "Showing translated image: ${bitmap.width}x${bitmap.height}")
    }

    /**
     * Updates the bitmap of an existing overlay, or creates a new one if not showing.
     * This is the preferred method — it avoids removing/re-adding the window on every frame.
     */
    fun updateImage(bitmap: Bitmap) {
        overlayView?.clearPlayback()
        if (overlayView != null) {
            FgoLogger.debug(tag, "Updating overlay image")
            showOrUpdateTranslatedImage(bitmap)
        } else {
            FgoLogger.debug(tag, "No existing overlay, creating new")
            showTranslatedImage(bitmap)
        }
    }

    /** Temporarily hides the translated window during OCR, retaining its rendered image. */
    fun hideForCapture() {
        if (isOverlayShowing) {
            FgoLogger.debug(tag, "Temporarily hiding overlay for OCR capture")
        }
        hideOverlayView(clearBitmap = false)
    }

    fun setTranslatedOverlayTouchable(touchable: Boolean): Boolean {
        requestedOverlayTouchable = touchable
        val effectiveTouchable = touchable && !controlsVisible
        val wm = windowManager ?: return false
        val view = overlayView ?: return false
        val currentParams = view.layoutParams
        if (overlayTouchable == effectiveTouchable && currentParams?.width == screenWidth &&
            currentParams.height == screenHeight) return true
        val params = if (effectiveTouchable) overlayParams else overlayPassthroughParams
        try {
            wm.updateViewLayout(view, params)
            overlayTouchable = effectiveTouchable
            if (!effectiveTouchable) view.pausePlayback() else if (isOverlayShowing) view.resumePlayback()
            updateFloatingHandleGestureExclusion()
            FgoLogger.debug(tag, "Translated overlay touchable=$effectiveTouchable, requested=$touchable, controlsVisible=$controlsVisible")
            return true
        } catch (e: Exception) {
            FgoLogger.warn(tag, "Failed to update translated overlay touchable=$touchable", e)
            return false
        }
    }

    /** Modal controls own input until every menu/history window has closed. The image stays visible. */
    fun setControlsVisible(visible: Boolean) {
        if (controlsVisible == visible) return
        controlsVisible = visible
        setTranslatedOverlayTouchable(requestedOverlayTouchable)
    }

    /** Restores the translated window after an OCR check found no source-text change. */
    fun restoreAfterCapture() {
        refreshScreenBounds()
        if (isOverlayShowing) return
        latestTranslatedBitmap?.let {
            FgoLogger.debug(tag, "Restoring unchanged translated overlay")
            showOrUpdateTranslatedImage(it)
        }
    }

    /** Pixel geometry is independent of Android density/font scale. */
    private fun refreshScreenBounds() {
        val bounds = windowManager?.currentWindowMetrics?.bounds ?: return
        val width = bounds.width()
        val height = bounds.height()
        if (width <= 0 || height <= 0 || (width == screenWidth && height == screenHeight)) return
        screenWidth = width
        screenHeight = height
        // Also clears the image retained by hideForCapture; old geometry must not return.
        hideOverlayView(clearBitmap = true)
        FgoLogger.info(tag, "Translation overlay screen changed: ${width}x${height}")
    }

    /** Hides the full-screen overlay. Safe to call even if nothing is showing. */
    fun hide() {
        hideOverlayView(clearBitmap = true)
    }

    private fun hideOverlayView(clearBitmap: Boolean) {
        overlayView?.let {
            it.pausePlayback()
            it.alpha = 0f
            if (clearBitmap) {
                it.setImageBitmap(null)
                it.clearPlayback()
            }
            FgoLogger.info(tag, "Overlay hidden")
        }
        isOverlayShowing = false
        setTranslatedOverlayTouchable(false)
        if (clearBitmap) {
            latestTranslatedBitmap = null
        }
    }

    private fun removeOverlayView() {
        val wm = windowManager ?: return
        overlayView?.let {
            try {
                it.clearPlayback()
                it.setFloatingHandleGestureExclusion(null)
                wm.removeView(it)
                FgoLogger.info(tag, "Overlay hidden")
            } catch (e: Exception) {
                FgoLogger.warn(tag, "Failed to remove overlay view", e)
            }
        }
        overlayView = null
        isOverlayShowing = false
        overlayTouchable = true
    }

    /** Hides the full-screen overlay. */
    fun hideAll() {
        if (isOverlayShowing || overlayView != null) {
            FgoLogger.info(tag, "Hiding translation overlay")
        }
        latestTranslatedBitmap = null
        removeOverlayView()
    }

    /** Whether the full-screen overlay is currently displayed. */
    fun isShowing(): Boolean = isOverlayShowing

    /** Removes all overlays and releases the WindowManager reference. */
    fun destroy() {
        if (callbacksRegistered) {
            context.unregisterComponentCallbacks(componentCallbacks)
            callbacksRegistered = false
        }
        mainHandler.removeCallbacksAndMessages(null)
        hideAll()
        onOverlayTap = null
        onOverlayTouch = null
        floatingHandleBounds = null
        windowManager = null
        FgoLogger.info(tag, "Overlay destroyed")
    }
}
