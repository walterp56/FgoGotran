package com.fgogotran.overlay

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF

/** Draws annotations as decorations; the main font metrics and advances are never expanded. */
internal object RubyTextPainter {
    const val SIZE_RATIO = ProportionalDialogueFit.RUBY_RATIO
    const val GAP_RATIO = 0.10f
    // Reference screenshots: FGO dots have 10–11 px visible ink above ~48–49 px body ink.
    // A 40%-sized bullet glyph is only ~3 px in our CJK font; draw a real circle instead.
    const val DOT_DIAMETER_RATIO = 0.20f

    data class PlannedAnnotation(
        val reading: String, val textSize: Float, val x: Float, val baseline: Float,
        val left: Float, val top: Float, val right: Float, val bottom: Float,
        val emphasis: Boolean = false
    )

    /** All-or-nothing planning at exactly 40%; the caller retries the entire dialogue on failure. */
    fun planExact(
        paint: Paint,
        text: RubyRenderText.Text,
        x: Float,
        baseline: Float,
        bounds: RectF,
        minimumTop: Float,
        gap: Float
    ): List<PlannedAnnotation>? {
        if (text.annotations.isEmpty()) return emptyList()
        val result = mutableListOf<PlannedAnnotation>()
        val rubyPaint = Paint(paint).apply { textSize = paint.textSize * SIZE_RATIO }
        val baseInk = Rect()
        val readingInk = Rect()
        var previousRight = bounds.left
        for (annotation in text.annotations) {
            if (annotation.emphasis) {
                val dot = planDot(paint, text.body, annotation, x, baseline, bounds, minimumTop, gap) ?: return null
                result += dot
                continue
            }
            paint.getTextBounds(text.body, annotation.start, annotation.end, baseInk)
            val baseTop = baseline + baseInk.top
            val baseLeft = x + paint.getRunAdvance(text.body, 0, text.body.length, 0, text.body.length, false, annotation.start)
            val endpoint = baseLeft + baseInk.right
            if (endpoint > bounds.right) return null
            rubyPaint.getTextBounds(annotation.reading, 0, annotation.reading.length, readingInk)
            val ink = RubyEndAlignment.InkBounds(readingInk.left.toFloat(), readingInk.top.toFloat(),
                readingInk.right.toFloat(), readingInk.bottom.toFloat())
            val placement = RubyEndAlignment.exact(rubyPaint.textSize, endpoint, previousRight,
                baseTop - gap - maxOf(bounds.top, minimumTop), ink) ?: return null
            val y = baseTop - gap - ink.bottom
            result += PlannedAnnotation(annotation.reading, placement.textSize, placement.drawX, y,
                placement.drawX + ink.left, y + ink.top, endpoint, y + ink.bottom)
            previousRight = endpoint + paint.textSize * 0.05f
        }
        return result
    }

    /** Paint the already validated plan without a second sizing or omission decision. */
    fun drawPlanned(canvas: Canvas, paint: Paint, annotations: List<PlannedAnnotation>, color: Int, shadowScale: Float) {
        if (annotations.isEmpty()) return
        val rubyPaint = Paint(paint).apply {
            this.color = color
            textAlign = Paint.Align.LEFT
            clearShadowLayer()
        }
        for (annotation in annotations) {
            rubyPaint.textSize = annotation.textSize
            if (shadowScale > 0f) {
                val shadow = 2f * shadowScale * SIZE_RATIO
                rubyPaint.setShadowLayer(shadow, shadow, shadow, Color.BLACK)
                drawAnnotation(canvas, rubyPaint, annotation)
                rubyPaint.clearShadowLayer()
            }
            drawAnnotation(canvas, rubyPaint, annotation)
        }
    }

    private fun drawAnnotation(canvas: Canvas, paint: Paint, annotation: PlannedAnnotation) {
        if (annotation.emphasis) {
            canvas.drawCircle((annotation.left + annotation.right) / 2f,
                (annotation.top + annotation.bottom) / 2f, (annotation.right - annotation.left) / 2f, paint)
        } else canvas.drawText(annotation.reading, annotation.x, annotation.baseline, paint)
    }

    fun drawAnnotations(
        canvas: Canvas,
        paint: Paint,
        text: RubyRenderText.Text,
        x: Float,
        baseline: Float,
        color: Int,
        bounds: RectF,
        minimumTop: Float = bounds.top,
        shadowScale: Float = 0f,
        gap: Float = paint.textSize * GAP_RATIO
    ) {
        if (text.annotations.isEmpty() || bounds.width() <= 0f) return
        val rubyPaint = Paint(paint).apply {
            this.color = color
            textAlign = Paint.Align.LEFT
            clearShadowLayer()
        }
        var previousRight = bounds.left
        text.annotations.forEach { annotation ->
            if (annotation.emphasis) {
                planDot(paint, text.body, annotation, x, baseline, bounds, minimumTop, gap)?.let {
                    drawPlanned(canvas, paint, listOf(it), color, shadowScale)
                }
                return@forEach
            }
            val bodyBounds = Rect()
            paint.getTextBounds(text.body, annotation.start, annotation.end, bodyBounds)
            val baseTop = baseline + bodyBounds.top
            val availableHeight = baseTop - gap - maxOf(bounds.top, minimumTop)
            if (availableHeight < 1f) return@forEach
            val baseLeft = x + paint.getRunAdvance(text.body, 0, text.body.length, 0, text.body.length, false, annotation.start)
            val endpoint = baseLeft + bodyBounds.right
            val leftLimit = maxOf(bounds.left, previousRight)
            if (endpoint > bounds.right) return@forEach
            val ink = Rect()
            val placement = RubyEndAlignment.fit(
                preferredTextSize = paint.textSize * SIZE_RATIO,
                endpoint = endpoint,
                minimumLeft = leftLimit,
                maximumHeight = availableHeight
            ) { size ->
                rubyPaint.textSize = size
                rubyPaint.getTextBounds(annotation.reading, 0, annotation.reading.length, ink)
                RubyEndAlignment.InkBounds(ink.left.toFloat(), ink.top.toFloat(), ink.right.toFloat(), ink.bottom.toFloat())
            } ?: return@forEach
            rubyPaint.textSize = placement.textSize
            val rubyBaseline = baseTop - gap - placement.ink.bottom
            if (shadowScale > 0f) {
                val shadow = 2f * shadowScale * (rubyPaint.textSize / paint.textSize)
                rubyPaint.setShadowLayer(shadow, shadow, shadow, Color.BLACK)
                canvas.drawText(annotation.reading, placement.drawX, rubyBaseline, rubyPaint)
                rubyPaint.clearShadowLayer()
            }
            canvas.drawText(annotation.reading, placement.drawX, rubyBaseline, rubyPaint)
            previousRight = endpoint + paint.textSize * 0.05f
        }
    }

    /** Each dot is a font-independent circle centered over its shaped character or Latin word. */
    private fun planDot(paint: Paint, body: String, annotation: RubyRenderText.Annotation,
                        x: Float, baseline: Float, bounds: RectF, minimumTop: Float, gap: Float): PlannedAnnotation? {
        val diameter = paint.textSize * DOT_DIAMETER_RATIO
        val bodyInk = Rect()
        // One common annotation band across the row, including tall capitals/accents.
        paint.getTextBounds(body,0,body.length,bodyInk)
        val left = x + paint.getRunAdvance(body,0,body.length,0,body.length,false,annotation.start)
        val right = x + paint.getRunAdvance(body,0,body.length,0,body.length,false,annotation.end)
        val center = (left + right) / 2f
        val dotLeft = center - diameter / 2f
        val bottom = baseline + bodyInk.top - gap
        val top = bottom - diameter
        if (top < maxOf(bounds.top,minimumTop) || dotLeft < bounds.left || dotLeft+diameter > bounds.right) return null
        return PlannedAnnotation("•",paint.textSize * SIZE_RATIO,dotLeft,bottom,
            dotLeft,top,dotLeft+diameter,bottom,emphasis=true)
    }

    fun bodyBottom(
        paint: Paint,
        text: String,
        baseline: Float,
        rubyMode: RubyRenderText.Mode = RubyRenderText.Mode.ABOVE_BASE
    ): Float {
        val body = RubyRenderText.body(text, rubyMode)
        val ink = Rect()
        paint.getTextBounds(body, 0, body.length, ink)
        return baseline + ink.bottom
    }
}
