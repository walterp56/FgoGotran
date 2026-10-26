package com.fgogotran.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.text.SpannableStringBuilder
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/** Native text layout with ruby decorations; line height, padding and body baselines stay native. */
internal class RubyTextView(context: Context) : TextView(context) {
    private var ruby = RubyRenderText.Text("")
    private var decoration: Drawable? = null
    private var decorationHost: ViewGroup? = null
    var annotationColor: Int? = null

    fun setRubyText(value: CharSequence) {
        ruby = RubyRenderText.parse(value.toString())
        val display = SpannableStringBuilder(value)
        ruby.markers.asReversed().forEach { display.delete(it.start, it.end) }
        // Deletion adjusts existing quote/colour spans rather than flattening their styling.
        text = display
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        var host = parent as? ViewGroup ?: return
        while (host.parent is ViewGroup && host.parent !is RecyclerView) {
            val ancestor = host.parent
            if (ancestor !is android.widget.LinearLayout && ancestor !is android.widget.FrameLayout) break
            host = ancestor
        }
        decorationHost = host
        decoration = object : Drawable() {
            override fun draw(canvas: Canvas) {
                if (!isShown) return
                val ownerPosition = IntArray(2)
                val hostPosition = IntArray(2)
                getLocationInWindow(ownerPosition)
                host.getLocationInWindow(hostPosition)
                canvas.save()
                canvas.translate((ownerPosition[0] - hostPosition[0]).toFloat(),
                    (ownerPosition[1] - hostPosition[1]).toFloat())
                drawRuby(canvas)
                canvas.restore()
            }
            override fun setAlpha(alpha: Int) = Unit
            override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) = Unit
            @Deprecated("Drawable compatibility")
            override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
        }.also {
            it.setBounds(0, 0, host.width, host.height)
            host.overlay.add(it)
        }
    }

    override fun onDetachedFromWindow() {
        decoration?.let { decorationHost?.overlay?.remove(it) }
        decoration = null
        decorationHost = null
        super.onDetachedFromWindow()
    }

    override fun invalidate() {
        super.invalidate()
        decoration?.invalidateSelf()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        decorationHost?.let { decoration?.setBounds(0, 0, it.width, it.height) }
        decoration?.invalidateSelf()
    }

    private fun drawRuby(canvas: Canvas) {
        val nativeLayout = layout ?: return
        if (ruby.annotations.isEmpty()) return
        canvas.save()
        canvas.translate((totalPaddingLeft - scrollX).toFloat(), (extendedPaddingTop - scrollY).toFloat())
        val bounds = RectF(canvas.clipBounds).apply {
            top = maxOf(top, -paint.textSize * (RubyTextPainter.SIZE_RATIO + RubyTextPainter.GAP_RATIO))
        }
        val decorationPaint = Paint(paint).apply { color = annotationColor ?: currentTextColor }
        ruby.annotations.groupBy { nativeLayout.getLineForOffset(it.end - 1) }.forEach { (line, annotations) ->
            val start = nativeLayout.getLineStart(line)
            val end = nativeLayout.getLineVisibleEnd(line)
            val body = ruby.body.substring(start, end)
            val visibleAnnotations = annotations.mapNotNull { annotation ->
                val localStart = maxOf(annotation.start, start) - start
                val localEnd = minOf(annotation.end, end) - start
                if (localEnd <= localStart) null else annotation.copy(start = localStart, end = localEnd)
            }
            val minimumTop = if (line > 0) {
                val previous = ruby.body.substring(nativeLayout.getLineStart(line - 1), nativeLayout.getLineVisibleEnd(line - 1))
                RubyTextPainter.bodyBottom(paint, previous, nativeLayout.getLineBaseline(line - 1).toFloat()) + 1f
            } else {
                previousSiblingBodyBottom(bounds.top)
            }
            RubyTextPainter.drawAnnotations(canvas, decorationPaint, RubyRenderText.Text(body, visibleAnnotations),
                nativeLayout.getLineLeft(line), nativeLayout.getLineBaseline(line).toFloat(),
                decorationPaint.color, bounds, minimumTop)
        }
        canvas.restore()
    }

    private fun previousSiblingBodyBottom(default: Float): Float {
        val container = parent as? android.widget.LinearLayout ?: return default
        val index = container.indexOfChild(this)
        val previous = if (index > 0) container.getChildAt(index - 1) as? TextView else null
        val previousLayout = previous?.layout ?: return default
        val last = previousLayout.lineCount - 1
        if (last < 0) return default
        val text = previous.text.subSequence(previousLayout.getLineStart(last), previousLayout.getLineVisibleEnd(last)).toString()
        return maxOf(default, previous.top + previous.extendedPaddingTop +
            RubyTextPainter.bodyBottom(previous.paint, text, previousLayout.getLineBaseline(last).toFloat()) -
            top - extendedPaddingTop + 1f)
    }
}
