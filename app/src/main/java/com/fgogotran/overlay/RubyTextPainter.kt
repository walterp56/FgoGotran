package com.fgogotran.overlay

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import kotlin.math.min

/** Draws annotations as decorations; the main font metrics and advances are never expanded. */
internal object RubyTextPainter {
    const val SIZE_RATIO = 0.40f
    const val GAP_RATIO = 0.10f

    fun drawAnnotations(
        canvas: Canvas,
        paint: Paint,
        text: RubyRenderText.Text,
        x: Float,
        baseline: Float,
        color: Int,
        bounds: RectF,
        minimumTop: Float = bounds.top,
        shadowScale: Float = 0f
    ) {
        if (text.annotations.isEmpty() || bounds.width() <= 0f) return
        val rubyPaint = Paint(paint).apply {
            this.color = color
            textAlign = Paint.Align.LEFT
            clearShadowLayer()
        }
        val gap = paint.textSize * GAP_RATIO
        var previousRight = bounds.left
        text.annotations.forEach { annotation ->
            val bodyBounds = Rect()
            paint.getTextBounds(text.body, annotation.start, annotation.end, bodyBounds)
            val baseTop = baseline + bodyBounds.top
            val availableHeight = baseTop - gap - maxOf(bounds.top, minimumTop)
            if (availableHeight < 1f) return@forEach
            val baseLeft = x + paint.getRunAdvance(text.body, 0, text.body.length, 0, text.body.length, false, annotation.start)
            val baseRight = x + paint.getRunAdvance(text.body, 0, text.body.length, 0, text.body.length, false, annotation.end)
            val center = (baseLeft + baseRight) / 2f
            val rightLimit = min(bounds.right, text.annotations.firstOrNull { it.start >= annotation.end && it !== annotation }?.let { next ->
                val nextLeft = x + paint.getRunAdvance(text.body, 0, text.body.length, 0, text.body.length, false, next.start)
                (baseRight + nextLeft) / 2f
            } ?: bounds.right)
            val leftLimit = maxOf(bounds.left, previousRight)
            val availableWidth = rightLimit - leftLimit
            if (availableWidth < 1f) return@forEach
            rubyPaint.textSize = paint.textSize * SIZE_RATIO
            val ink = Rect()
            rubyPaint.getTextBounds(annotation.reading, 0, annotation.reading.length, ink)
            if (ink.height() > availableHeight) rubyPaint.textSize *= availableHeight / ink.height()
            val naturalWidth = rubyPaint.measureText(annotation.reading)
            if (naturalWidth > availableWidth) rubyPaint.textSize *= availableWidth / naturalWidth
            rubyPaint.getTextBounds(annotation.reading, 0, annotation.reading.length, ink)
            val width = rubyPaint.measureText(annotation.reading)
            val left = (center - width / 2f).coerceIn(leftLimit, (rightLimit - width).coerceAtLeast(leftLimit))
            val rubyBaseline = baseTop - gap - ink.bottom
            if (shadowScale > 0f) {
                val shadow = 2f * shadowScale * (rubyPaint.textSize / paint.textSize)
                rubyPaint.setShadowLayer(shadow, shadow, shadow, Color.BLACK)
                canvas.drawText(annotation.reading, left, rubyBaseline, rubyPaint)
                rubyPaint.clearShadowLayer()
            }
            canvas.drawText(annotation.reading, left, rubyBaseline, rubyPaint)
            previousRight = left + width + paint.textSize * 0.05f
        }
    }

    fun bodyBottom(paint: Paint, text: String, baseline: Float): Float {
        val body = RubyRenderText.body(text)
        val ink = Rect()
        paint.getTextBounds(body, 0, body.length, ink)
        return baseline + ink.bottom
    }
}
