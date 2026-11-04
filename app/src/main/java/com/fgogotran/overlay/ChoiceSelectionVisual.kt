package com.fgogotran.overlay

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF

/** Frozen, full-screen-coordinate drawing commands. No renderer bitmap is retained. */
internal class ChoiceSelectionVisualPlan(
    clearBox: RectF,
    private val cornerRadius: Float,
    private val drawBackground: (Canvas) -> Unit,
    private val drawText: (Canvas) -> Unit
) {
    private val clearBox = RectF(clearBox)

    /** Background-only cover for either row; no text rasterization or annotation planning. */
    fun captureBackground(bounds: Rect): Bitmap = captureLayer(bounds, drawBackground)

    private fun captureLayer(bounds: Rect, draw: (Canvas) -> Unit): Bitmap {
        val bitmap = Bitmap.createBitmap(bounds.width(), bounds.height(), Bitmap.Config.ARGB_8888)
        bitmap.density = Bitmap.DENSITY_NONE
        try {
            val canvas = Canvas(bitmap)
            canvas.translate(-bounds.left.toFloat(), -bounds.top.toFloat())
            draw(canvas)
            return bitmap
        } catch (error: Throwable) {
            bitmap.recycle()
            throw error
        }
    }

    /** Allocate only for the selected row, using its existing screenshot-pixel geometry. */
    fun capture(bounds: Rect): ChoiceSelectionLayers {
        val allocated = mutableListOf<Bitmap>()
        fun layer(draw: (Canvas) -> Unit): Bitmap {
            return captureLayer(bounds, draw).also { allocated += it }
        }
        try {
            val background = layer(drawBackground)
            val text = layer(drawText)
            // Include annotations and shadows in the common bilingual/text-group pivot.
            val pixels = IntArray(text.width * text.height)
            text.getPixels(pixels, 0, text.width, 0, 0, text.width, text.height)
            var left = text.width
            var top = text.height
            var right = -1
            var bottom = -1
            pixels.forEachIndexed { index, color ->
                if (color ushr 24 != 0) {
                    val x = index % text.width
                    val y = index / text.width
                    left = minOf(left, x); right = maxOf(right, x)
                    top = minOf(top, y); bottom = maxOf(bottom, y)
                }
            }
            check(right >= left && bottom >= top) { "Selected choice has no visible text" }
            return ChoiceSelectionLayers(background, text, bounds, clearBox, cornerRadius,
                bounds.left + (left + right + 1) / 2f, bounds.top + (top + bottom + 1) / 2f)
        } catch (error: Throwable) {
            allocated.forEach { it.recycle() }
            throw error
        }
    }
}

/** Owned patches used only during the short confirmation echo, before the existing fade. */
internal class ChoiceSelectionLayers(
    private val background: Bitmap,
    private val text: Bitmap,
    bounds: Rect,
    clearBox: RectF,
    cornerRadius: Float,
    private val pivotX: Float,
    private val pivotY: Float
) : AutoCloseable {
    private val destination = RectF(bounds)
    private val ghostClip = Path().apply { addRoundRect(clearBox, cornerRadius, cornerRadius, Path.Direction.CW) }
    private val ghostPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var closed = false

    fun draw(canvas: Canvas, scale: Float, alpha: Int) {
        if (closed) return
        canvas.drawBitmap(background, null, destination, null)
        canvas.save()
        canvas.clipPath(ghostClip)
        canvas.scale(scale, scale, pivotX, pivotY)
        ghostPaint.alpha = alpha
        canvas.drawBitmap(text, null, destination, ghostPaint)
        canvas.restore()
        canvas.drawBitmap(text, null, destination, null)
    }

    override fun close() {
        if (closed) return
        closed = true
        background.recycle()
        text.recycle()
    }
}
