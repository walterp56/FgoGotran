package com.fgogotran.crop

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import com.fgogotran.util.FgoLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CropResultOverlay @Inject constructor(
    @ApplicationContext private val appContext: Context
) {
    private var overlayContext: Context = appContext
    private var windowManager: WindowManager? = null
    private var rootView: FrameLayout? = null
    private var imageView: ImageView? = null
    private var latestBitmap: Bitmap? = null
    private var onTap: ((Float, Float) -> Unit)? = null
    private val tag = "CropResultOverlay"

    fun init(
        serviceContext: Context,
        onTap: (Float, Float) -> Unit
    ) {
        overlayContext = serviceContext
        windowManager = serviceContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        this.onTap = onTap
        FgoLogger.info(tag, "Crop result overlay initialized")
    }

    fun show(bounds: Rect, bitmap: Bitmap) {
        val wm = windowManager ?: run {
            bitmap.recycle()
            return
        }
        val root = rootView
        val image = imageView
        if (root != null && image != null) {
            replaceBitmap(image, bitmap)
            image.layoutParams = imageLayoutParams(bounds)
            image.requestLayout()
            return
        }

        val newRoot = FrameLayout(overlayContext).apply {
            setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_UP) {
                    onTap?.invoke(event.rawX, event.rawY)
                }
                true
            }
        }
        val newImage = ImageView(overlayContext).apply {
            scaleType = ImageView.ScaleType.FIT_XY
            setImageBitmap(bitmap)
        }
        newRoot.addView(newImage, imageLayoutParams(bounds))
        latestBitmap = bitmap
        wm.addView(newRoot, layoutParams())
        rootView = newRoot
        imageView = newImage
        FgoLogger.info(tag, "Crop result shown at ${bounds.flattenToString()}")
    }

    fun hide() {
        val wm = windowManager
        rootView?.let { view ->
            try {
                imageView?.setImageBitmap(null)
                wm?.removeView(view)
            } catch (e: Exception) {
                FgoLogger.warn(tag, "Failed to remove crop result overlay", e)
            }
        }
        rootView = null
        imageView = null
        latestBitmap?.recycle()
        latestBitmap = null
    }

    fun destroy() {
        hide()
        windowManager = null
        overlayContext = appContext
        onTap = null
    }

    private fun replaceBitmap(view: ImageView, bitmap: Bitmap) {
        val oldBitmap = latestBitmap
        latestBitmap = bitmap
        view.setImageBitmap(bitmap)
        oldBitmap?.recycle()
    }

    private fun layoutParams(): WindowManager.LayoutParams {
        return WindowManager.LayoutParams().apply {
            type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            format = PixelFormat.TRANSLUCENT
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = WindowManager.LayoutParams.MATCH_PARENT
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }

    private fun imageLayoutParams(bounds: Rect): FrameLayout.LayoutParams {
        return FrameLayout.LayoutParams(
            bounds.width().coerceAtLeast(1),
            bounds.height().coerceAtLeast(1)
        ).apply {
            leftMargin = bounds.left
            topMargin = bounds.top
        }
    }
}
