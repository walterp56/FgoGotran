package com.fgogotran.overlay

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Paint
import android.graphics.BlendMode
import android.icu.text.BreakIterator
import java.util.Locale

internal object DialoguePlaybackCharacters {
    fun ends(text: String): IntArray {
        val iterator = BreakIterator.getCharacterInstance(Locale.ROOT).apply { setText(text) }
        val result = ArrayList<Int>()
        var next = iterator.next()
        while (next != BreakIterator.DONE) { result += next; next = iterator.next() }
        return result.toIntArray()
    }
}

internal class DialoguePlaybackFrame(
    val bitmap: Bitmap,
    val panels: List<Panel>,
    private val exclusions: List<Rect>,
    settings: DialoguePlaybackSettings
) : AutoCloseable {
    data class Layer(val bitmap: Bitmap, val left: Int, val top: Int) {
        private val destination = RectF(left.toFloat(), top.toFloat(), left + bitmap.width.toFloat(), top + bitmap.height.toFloat())
        // Explicit pixel destination avoids density-based rescaling of the cached patches.
        fun draw(canvas: Canvas, paint: Paint? = null) = canvas.drawBitmap(bitmap, null, destination, paint)
    }
    data class Reading(val end: Int, val layer: Layer)
    data class Row(
        val text: String, val x: Float, val ends: IntArray, val advances: FloatArray,
        val layer: Layer, val readings: List<Reading>, var start: Int = 0
    )
    data class Panel(val bounds: Rect, val hitBounds: Rect, val blank: Layer, val rows: List<Row>)

    val timeline: DialoguePlaybackTimeline
    private var closed = false
    private val replacementPaint = Paint().apply { blendMode = BlendMode.SRC }

    init {
        val text = StringBuilder()
        for (row in panels.flatMap { it.rows }) {
            if (text.isNotEmpty()) text.append('\n')
            row.start = text.length
            text.append(row.text)
        }
        timeline = DialoguePlaybackTimeline(text.toString(), DialoguePlaybackCharacters.ends(text.toString()), settings)
    }

    /** Draw over the final bitmap only while revealing; completion uses the untouched legacy pixels. */
    fun drawReveal(canvas: Canvas, visibleEnd: Int) {
        if (closed || visibleEnd >= timeline.text.length) return
        for (panel in panels) {
            canvas.save()
            canvas.clipRect(panel.bounds)
            for (bounds in exclusions) canvas.clipOutRect(bounds)
            panel.blank.draw(canvas, replacementPaint)
            for (row in panel.rows) {
                val end = (visibleEnd - row.start).coerceIn(0, row.text.length)
                if (end == 0) continue
                if (end == row.text.length) row.layer.draw(canvas) else {
                    val index = row.ends.indexOfLast { it <= end }
                    if (index >= 0) {
                        canvas.save()
                        canvas.clipRect(panel.bounds.left.toFloat(), panel.bounds.top.toFloat(),
                            row.x + row.advances[index], panel.bounds.bottom.toFloat())
                        row.layer.draw(canvas)
                        canvas.restore()
                    }
                }
                for (reading in row.readings) if (reading.end <= end) reading.layer.draw(canvas)
            }
            canvas.restore()
        }
    }

    fun acceptsTap(x: Float, y: Float): Boolean =
        panels.any { it.hitBounds.contains(x.toInt(), y.toInt()) } &&
            exclusions.none { it.contains(x.toInt(), y.toInt()) } &&
            !com.fgogotran.story.StoryTapHandoff.isControlTap(x, y, bitmap.width, bitmap.height)

    override fun close() {
        if (closed) return
        closed = true
        if (!bitmap.isRecycled) bitmap.recycle()
        panels.forEach(::releasePanel)
    }

    companion object {
        fun releasePanel(panel: Panel) {
            val layers = listOf(panel.blank) + panel.rows.flatMap { row -> listOf(row.layer) + row.readings.map { it.layer } }
            layers.forEach { if (!it.bitmap.isRecycled) it.bitmap.recycle() }
        }
    }
}
