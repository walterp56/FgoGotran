package com.fgogotran.crop

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.fgogotran.util.FgoLogger
import com.fgogotran.util.overlayType
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.roundToInt

@Singleton
class CropSelectionOverlay @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private var windowManager: WindowManager? = null
    private var selectionView: CropSelectionView? = null
    private val tag = "CropSelection"

    private val layoutParams: WindowManager.LayoutParams
        get() = WindowManager.LayoutParams().apply {
            type = overlayType
            format = PixelFormat.TRANSLUCENT
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
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

    fun show() {
        val wm = windowManager
            ?: (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
                .also { windowManager = it }
        if (selectionView != null) return

        val bounds = wm.currentWindowMetrics.bounds
        selectionView = CropSelectionView(context).apply {
            setScreenSize(bounds.width(), bounds.height())
        }
        wm.addView(selectionView, layoutParams)
        FgoLogger.info(tag, "Crop selector shown")
    }

    fun hide() {
        val wm = windowManager
        selectionView?.let { view ->
            try {
                wm?.removeView(view)
            } catch (e: Exception) {
                FgoLogger.warn(tag, "Failed to remove crop selector", e)
            }
        }
        selectionView = null
    }

    fun selectedBounds(): Rect? {
        return selectionView?.selectedBounds()
    }

    fun isShowing(): Boolean = selectionView != null
}

private class CropSelectionView(context: Context) : View(context) {
    private val cropRect = RectF()
    private var activeDrag = DragTarget.NONE
    private var lastX = 0f
    private var lastY = 0f
    private var minWidth = 180f
    private var minHeight = 96f

    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(110, 0, 0, 0)
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 4f
        style = Paint.Style.STROKE
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(80, 220, 255)
        strokeWidth = 8f
        strokeCap = Paint.Cap.ROUND
        style = Paint.Style.STROKE
    }

    fun setScreenSize(screenWidth: Int, screenHeight: Int) {
        if (screenWidth <= 0 || screenHeight <= 0) return
        minWidth = (screenWidth * 0.16f).coerceAtLeast(180f)
        minHeight = (screenHeight * 0.10f).coerceAtLeast(96f)
        val defaultWidth = screenWidth * 0.58f
        val defaultHeight = screenHeight * 0.20f
        val left = (screenWidth - defaultWidth) / 2f
        val top = screenHeight * 0.36f
        cropRect.set(left, top, left + defaultWidth, top + defaultHeight)
        clampRect()
        invalidate()
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        if (cropRect.isEmpty) {
            setScreenSize(width, height)
        } else {
            clampRect()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawMask(canvas)
        canvas.drawRect(cropRect, borderPaint)
        drawHandles(canvas)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activeDrag = dragTargetFor(event.x, event.y)
                lastX = event.x
                lastY = event.y
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - lastX
                val dy = event.y - lastY
                applyDrag(dx, dy)
                lastX = event.x
                lastY = event.y
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                activeDrag = DragTarget.NONE
                return true
            }
        }
        return true
    }

    fun selectedBounds(): Rect {
        clampRect()
        return Rect(
            cropRect.left.roundToInt(),
            cropRect.top.roundToInt(),
            cropRect.right.roundToInt(),
            cropRect.bottom.roundToInt()
        )
    }

    private fun drawMask(canvas: Canvas) {
        val fullWidth = width.toFloat()
        val fullHeight = height.toFloat()
        canvas.drawRect(0f, 0f, fullWidth, cropRect.top, maskPaint)
        canvas.drawRect(0f, cropRect.bottom, fullWidth, fullHeight, maskPaint)
        canvas.drawRect(0f, cropRect.top, cropRect.left, cropRect.bottom, maskPaint)
        canvas.drawRect(cropRect.right, cropRect.top, fullWidth, cropRect.bottom, maskPaint)
    }

    private fun drawHandles(canvas: Canvas) {
        val handleLength = (minOf(cropRect.width(), cropRect.height()) * 0.22f)
            .coerceIn(28f, 56f)

        drawCorner(canvas, cropRect.left, cropRect.top, handleLength, 1f, 1f)
        drawCorner(canvas, cropRect.right, cropRect.top, handleLength, -1f, 1f)
        drawCorner(canvas, cropRect.left, cropRect.bottom, handleLength, 1f, -1f)
        drawCorner(canvas, cropRect.right, cropRect.bottom, handleLength, -1f, -1f)
    }

    private fun drawCorner(
        canvas: Canvas,
        x: Float,
        y: Float,
        length: Float,
        horizontalDirection: Float,
        verticalDirection: Float
    ) {
        canvas.drawLine(x, y, x + length * horizontalDirection, y, handlePaint)
        canvas.drawLine(x, y, x, y + length * verticalDirection, handlePaint)
    }

    private fun dragTargetFor(x: Float, y: Float): DragTarget {
        val radius = 44f
        val nearLeft = abs(x - cropRect.left) <= radius
        val nearRight = abs(x - cropRect.right) <= radius
        val nearTop = abs(y - cropRect.top) <= radius
        val nearBottom = abs(y - cropRect.bottom) <= radius

        return when {
            nearLeft && nearTop -> DragTarget.TOP_LEFT
            nearRight && nearTop -> DragTarget.TOP_RIGHT
            nearLeft && nearBottom -> DragTarget.BOTTOM_LEFT
            nearRight && nearBottom -> DragTarget.BOTTOM_RIGHT
            nearLeft && y in cropRect.top..cropRect.bottom -> DragTarget.LEFT
            nearRight && y in cropRect.top..cropRect.bottom -> DragTarget.RIGHT
            nearTop && x in cropRect.left..cropRect.right -> DragTarget.TOP
            nearBottom && x in cropRect.left..cropRect.right -> DragTarget.BOTTOM
            cropRect.contains(x, y) -> DragTarget.MOVE
            else -> DragTarget.NONE
        }
    }

    private fun applyDrag(dx: Float, dy: Float) {
        when (activeDrag) {
            DragTarget.MOVE -> cropRect.offset(dx, dy)
            DragTarget.LEFT -> cropRect.left += dx
            DragTarget.RIGHT -> cropRect.right += dx
            DragTarget.TOP -> cropRect.top += dy
            DragTarget.BOTTOM -> cropRect.bottom += dy
            DragTarget.TOP_LEFT -> {
                cropRect.left += dx
                cropRect.top += dy
            }
            DragTarget.TOP_RIGHT -> {
                cropRect.right += dx
                cropRect.top += dy
            }
            DragTarget.BOTTOM_LEFT -> {
                cropRect.left += dx
                cropRect.bottom += dy
            }
            DragTarget.BOTTOM_RIGHT -> {
                cropRect.right += dx
                cropRect.bottom += dy
            }
            DragTarget.NONE -> Unit
        }
        normalizeSize()
        clampRect()
    }

    private fun normalizeSize() {
        if (cropRect.width() < minWidth) {
            when (activeDrag) {
                DragTarget.LEFT,
                DragTarget.TOP_LEFT,
                DragTarget.BOTTOM_LEFT -> cropRect.left = cropRect.right - minWidth
                else -> cropRect.right = cropRect.left + minWidth
            }
        }
        if (cropRect.height() < minHeight) {
            when (activeDrag) {
                DragTarget.TOP,
                DragTarget.TOP_LEFT,
                DragTarget.TOP_RIGHT -> cropRect.top = cropRect.bottom - minHeight
                else -> cropRect.bottom = cropRect.top + minHeight
            }
        }
    }

    private fun clampRect() {
        if (width <= 0 || height <= 0) return
        if (cropRect.left < 0f) cropRect.offset(-cropRect.left, 0f)
        if (cropRect.top < 0f) cropRect.offset(0f, -cropRect.top)
        if (cropRect.right > width) cropRect.offset(width - cropRect.right, 0f)
        if (cropRect.bottom > height) cropRect.offset(0f, height - cropRect.bottom)
    }

    private enum class DragTarget {
        NONE,
        MOVE,
        LEFT,
        RIGHT,
        TOP,
        BOTTOM,
        TOP_LEFT,
        TOP_RIGHT,
        BOTTOM_LEFT,
        BOTTOM_RIGHT
    }
}
