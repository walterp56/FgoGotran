package com.fgogotran.overlay

import com.fgogotran.text.EmphasisText
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.text.SpannableStringBuilder
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/** Native text layout with ruby decorations; line height, padding and body baselines stay native. */
internal class RubyTextView(context: Context) : TextView(context) {
    private var ruby = RubyRenderText.Text("")
    private var decoration: Drawable? = null
    private var decorationHost: ViewGroup? = null
    private val ownerPosition = IntArray(2)
    private val hostPosition = IntArray(2)
    private val hostLayoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        updateDecorationBounds()
    }
    private val hostScrollListener = object : RecyclerView.OnScrollListener() {
        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
            decoration?.invalidateSelf()
        }
    }
    var annotationColor: Int? = null

    fun setRubyText(value: CharSequence) {
        ruby = RubyRenderText.parse(value.toString())
        val display = SpannableStringBuilder(value)
        EmphasisText.controlOffsets(value.toString()).asReversed().forEach { display.delete(it, it + 1) }
        ruby.markers.asReversed().forEach { display.delete(it.start, it.end) }
        // Deletion adjusts existing quote/colour spans rather than flattening their styling.
        text = display
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val host = findDecorationHost() ?: return
        decorationHost = host
        decoration = object : Drawable() {
            override fun draw(canvas: Canvas) {
                if (!isShown) return
                getLocationInWindow(ownerPosition)
                host.getLocationInWindow(hostPosition)
                val textOriginX = (ownerPosition[0] - hostPosition[0] + totalPaddingLeft - scrollX).toFloat()
                val textOriginY = (ownerPosition[1] - hostPosition[1] + extendedPaddingTop - scrollY).toFloat()
                canvas.save()
                canvas.clipRect(0, 0, host.width, host.height)
                canvas.translate(textOriginX, textOriginY)
                val bounds = HistoryRubyViewport.textBounds(host.width, host.height, textOriginX, textOriginY,
                    paint.textSize * (RubyTextPainter.SIZE_RATIO + RubyTextPainter.GAP_RATIO))
                drawRuby(canvas, RectF(bounds.left, bounds.top, bounds.right, bounds.bottom))
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
        host.addOnLayoutChangeListener(hostLayoutListener)
        (host as? RecyclerView)?.addOnScrollListener(hostScrollListener)
    }

    override fun onDetachedFromWindow() {
        decoration?.let { decorationHost?.overlay?.remove(it) }
        decorationHost?.removeOnLayoutChangeListener(hostLayoutListener)
        (decorationHost as? RecyclerView)?.removeOnScrollListener(hostScrollListener)
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
        updateDecorationBounds()
    }

    private fun updateDecorationBounds() {
        decorationHost?.let { decoration?.setBounds(0, 0, it.width, it.height) }
        decoration?.invalidateSelf()
    }

    private fun findDecorationHost(): ViewGroup? {
        val fallback = parent as? ViewGroup
        var ancestor = fallback
        while (ancestor != null) {
            // A choice-only item has no header above its first text line. Drawing on the list
            // lets ruby use the existing inter-item/top padding without changing row geometry.
            if (ancestor is RecyclerView) return ancestor
            ancestor = ancestor.parent as? ViewGroup
        }
        return fallback
    }

    private fun drawRuby(canvas: Canvas, bounds: RectF) {
        val nativeLayout = layout ?: return
        if (ruby.annotations.isEmpty()) return
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
            // Native TextView.text already contains exactly the visible glyphs, including
            // inline Japanese readings on the previous bilingual source row.
            RubyTextPainter.bodyBottom(previous.paint, text, previousLayout.getLineBaseline(last).toFloat(),
                RubyRenderText.Mode.INLINE) -
            top - extendedPaddingTop + 1f)
    }
}
