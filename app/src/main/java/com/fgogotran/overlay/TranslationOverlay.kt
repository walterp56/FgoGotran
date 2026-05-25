package com.fgogotran.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import com.fgogotran.util.FgoLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages the TYPE_ACCESSIBILITY_OVERLAY system windows.
 *
 * Two overlay views are maintained:
 * 1. **Indicator dot** — a small 12x12px semi-transparent white square in the top-right
 *    corner that signals "service is active." Always shown when FGO is in the foreground.
 * 2. **Full-screen image** — displays the rendered translated screenshot.
 *    Created/updated on each pipeline completion, removed when the user leaves FGO.
 *
 * ## Window flags
 * - FLAG_NOT_FOCUSABLE + FLAG_NOT_TOUCHABLE: the overlay is completely touch-transparent;
 *   all touch events pass through to FGO underneath.
 * - FLAG_LAYOUT_IN_SCREEN: overlay fills the entire screen, including behind status/nav bars.
 *
 * ## Lifecycle
 * init() → showIndicator() → showTranslatedImage() → updateImage() (repeat) → hideAll() → destroy()
 */
@Singleton
class TranslationOverlay @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private var windowManager: WindowManager? = null
    private var overlayView: ImageView? = null
    private var indicatorView: View? = null
    private var isOverlayShowing = false
    private var screenWidth = 0
    private var screenHeight = 0
    private var onOverlayTap: ((Float, Float) -> Unit)? = null

    private val tag = "Overlay"

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
        }

    /**
     * Layout params for the small indicator dot (top-right corner).
     */
    private val indicatorParams: WindowManager.LayoutParams
        get() = WindowManager.LayoutParams().apply {
            type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            format = PixelFormat.TRANSLUCENT
            width = 12
            height = 12
            gravity = Gravity.TOP or Gravity.END
            x = 24
            y = 24
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
        onOverlayTap: (Float, Float) -> Unit
    ) {
        this.screenWidth = screenWidth
        this.screenHeight = screenHeight
        this.onOverlayTap = onOverlayTap
        // Must use the service context — Application context has no valid window token
        windowManager = serviceContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        FgoLogger.info(tag, "Overlay initialized: ${screenWidth}x${screenHeight}")
    }

    /** Shows the small indicator dot. No-op if already showing. */
    fun showIndicator() {
        if (indicatorView != null) return
        val wm = windowManager ?: return

        indicatorView = View(context).apply {
            setBackgroundColor(0x44FFFFFF.toInt())
        }
        wm.addView(indicatorView, indicatorParams)
        FgoLogger.debug(tag, "Indicator shown")
    }

    /** Removes the indicator dot. */
    fun hideIndicator() {
        val wm = windowManager ?: return
        indicatorView?.let {
            try { wm.removeView(it) } catch (e: Exception) {
                FgoLogger.warn(tag, "Failed to remove indicator view", e)
            }
        }
        indicatorView = null
    }

    /**
     * Creates a new full-screen ImageView with the rendered bitmap and adds it to the window.
     * Removes any previously showing overlay first.
     */
    fun showTranslatedImage(bitmap: Bitmap) {
        val wm = windowManager ?: return

        hide()
        screenWidth = bitmap.width
        screenHeight = bitmap.height

        val imageView = ImageView(context).apply {
            setImageBitmap(bitmap)
            scaleType = ImageView.ScaleType.FIT_XY
            setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_UP) {
                    FgoLogger.debug(this@TranslationOverlay.tag, "Translated overlay tapped at ${event.rawX},${event.rawY}")
                    onOverlayTap?.invoke(event.rawX, event.rawY)
                }
                true
            }
        }

        wm.addView(imageView, overlayParams)
        overlayView = imageView
        isOverlayShowing = true
        FgoLogger.info(tag, "Showing translated image: ${bitmap.width}x${bitmap.height}")
    }

    /**
     * Updates the bitmap of an existing overlay, or creates a new one if not showing.
     * This is the preferred method — it avoids removing/re-adding the window on every frame.
     */
    fun updateImage(bitmap: Bitmap) {
        if (isOverlayShowing) {
            FgoLogger.debug(tag, "Updating overlay image")
            overlayView?.setImageBitmap(bitmap)
        } else {
            FgoLogger.debug(tag, "No existing overlay, creating new")
            showTranslatedImage(bitmap)
        }
    }

    /** Hides the full-screen overlay. Safe to call even if nothing is showing. */
    fun hide() {
        val wm = windowManager ?: return
        overlayView?.let {
            try {
                wm.removeView(it)
                FgoLogger.info(tag, "Overlay hidden")
            } catch (e: Exception) {
                FgoLogger.warn(tag, "Failed to remove overlay view", e)
            }
        }
        overlayView = null
        isOverlayShowing = false
    }

    /** Hides both the full-screen overlay and the indicator dot. */
    fun hideAll() {
        if (isOverlayShowing || indicatorView != null) {
            FgoLogger.info(tag, "Hiding all overlays")
        }
        hide()
        hideIndicator()
    }

    /** Whether the full-screen overlay is currently displayed. */
    fun isShowing(): Boolean = isOverlayShowing

    /** Removes all overlays and releases the WindowManager reference. */
    fun destroy() {
        hideAll()
        onOverlayTap = null
        windowManager = null
        FgoLogger.info(tag, "Overlay destroyed")
    }
}
