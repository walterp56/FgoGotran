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
    fun ends(text: String, inlineRuby: Boolean = false): IntArray {
        val iterator = BreakIterator.getCharacterInstance(Locale.ROOT).apply { setText(text) }
        val result = ArrayList<Int>()
        var next = iterator.next()
        while (next != BreakIterator.DONE) { result += next; next = iterator.next() }
        if (!inlineRuby) return result.toIntArray()
        val markers = RubyRenderText.parse(text).markers
        // Japanese keeps inline 〈reading〉 notation. Reveal the entire annotation together.
        return result.filter { end -> markers.none { end > it.start && end < it.end } }.toIntArray()
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
    data class Panel(val bounds: Rect, val hitBounds: Rect, val blank: Layer, val rows: List<Row>,
                     val originalRows: List<Row> = emptyList())

    private class OriginalGroup(val rows: List<Row>, val drivers: List<Row>) {
        val sourceUnits = rows.sumOf { it.ends.size }
        val translationUnits = drivers.sumOf { it.ends.size }

        fun visibleUnits(visibleEnd: Int): Int {
            if (translationUnits == 0) return 0
            val visible = drivers.sumOf { row ->
                val end = (visibleEnd - row.start).coerceIn(0, row.text.length)
                row.ends.indexOfLast { it <= end } + 1
            }
            return (visible.toLong() * sourceUnits / translationUnits).toInt()
        }
    }

    val timeline: DialoguePlaybackTimeline
    private var closed = false
    private val replacementPaint = Paint().apply { blendMode = BlendMode.SRC }
    private val originalGroups: List<List<OriginalGroup>>

    init {
        val text = StringBuilder()
        for (row in panels.flatMap { it.rows }) {
            if (text.isNotEmpty()) text.append('\n')
            row.start = text.length
            text.append(row.text)
        }
        timeline = DialoguePlaybackTimeline(text.toString(), DialoguePlaybackCharacters.ends(text.toString()), settings)
        originalGroups = panels.map { panel ->
            when {
                panel.originalRows.isEmpty() || panel.rows.isEmpty() -> emptyList()
                panel.originalRows.size == panel.rows.size -> panel.originalRows.zip(panel.rows).map { (original, translated) ->
                    OriginalGroup(listOf(original), listOf(translated))
                }
                // Unequal wrapping must not finish one Japanese row early or omit another.
                else -> listOf(OriginalGroup(panel.originalRows, panel.rows))
            }
        }
    }

    /** Draw over the final bitmap only while revealing; completion uses the untouched legacy pixels. */
    fun drawReveal(canvas: Canvas, visibleEnd: Int) {
        if (closed || visibleEnd >= timeline.text.length) return
        for ((panelIndex, panel) in panels.withIndex()) {
            canvas.save()
            canvas.clipRect(panel.bounds)
            for (bounds in exclusions) canvas.clipOutRect(bounds)
            panel.blank.draw(canvas, replacementPaint)
            val groups = originalGroups[panelIndex]
            var remaining = groups.singleOrNull()?.visibleUnits(visibleEnd) ?: 0
            // Keep the renderer's translated/original row order, including overlapping shadows.
            for (index in 0 until maxOf(panel.rows.size, panel.originalRows.size)) {
                panel.rows.getOrNull(index)?.let { row ->
                    val end = (visibleEnd - row.start).coerceIn(0, row.text.length)
                    drawRow(canvas, panel.bounds, row, end)
                }
                panel.originalRows.getOrNull(index)?.let { row ->
                    val available = if (groups.size > 1) groups[index].visibleUnits(visibleEnd) else remaining
                    val count = available.coerceIn(0, row.ends.size)
                    val end = if (count == 0) 0 else row.ends[count - 1]
                    drawRow(canvas, panel.bounds, row, end)
                    if (groups.size == 1) remaining -= count
                }
            }
            canvas.restore()
        }
    }

    private fun drawRow(canvas: Canvas, bounds: Rect, row: Row, end: Int) {
        if (end == 0) return
        if (end == row.text.length) row.layer.draw(canvas) else {
            val index = row.ends.indexOfLast { it <= end }
            if (index >= 0) {
                canvas.save()
                canvas.clipRect(bounds.left.toFloat(), bounds.top.toFloat(),
                    row.x + row.advances[index], bounds.bottom.toFloat())
                row.layer.draw(canvas)
                canvas.restore()
            }
        }
        for (reading in row.readings) if (reading.end <= end) reading.layer.draw(canvas)
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
            val layers = listOf(panel.blank) + (panel.rows + panel.originalRows)
                .flatMap { row -> listOf(row.layer) + row.readings.map { it.layer } }
            layers.forEach { if (!it.bitmap.isRecycled) it.bitmap.recycle() }
        }
    }
}
