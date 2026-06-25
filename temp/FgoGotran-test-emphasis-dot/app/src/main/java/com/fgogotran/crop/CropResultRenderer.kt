package com.fgogotran.crop

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.fgogotran.overlay.FgoTypefaceProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CropResultRenderer @Inject constructor(
    @ApplicationContext context: Context
) {
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(190, 0, 0, 0)
        style = Paint.Style.FILL
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = FGO_TEXT_COLOR
        typeface = FgoTypefaceProvider.storyTypeface(context)
        textAlign = Paint.Align.LEFT
        isFakeBoldText = false
        isSubpixelText = true
        isLinearText = true
    }

    fun render(
        width: Int,
        height: Int,
        text: String,
        textColor: Int = FGO_TEXT_COLOR
    ): Bitmap {
        val bitmapWidth = width.coerceAtLeast(1)
        val bitmapHeight = height.coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawRect(0f, 0f, bitmapWidth.toFloat(), bitmapHeight.toFloat(), backgroundPaint)

        val padding = (minOf(bitmapWidth, bitmapHeight) * 0.06f).coerceIn(6f, 24f)
        val maxWidth = (bitmapWidth - padding * 2f).coerceAtLeast(1f)
        val maxHeight = (bitmapHeight - padding * 2f).coerceAtLeast(1f)
        val fitted = fitLines(text.trim(), maxWidth, maxHeight)

        textPaint.textSize = fitted.textSize
        val totalTextHeight = fitted.lines.size * fitted.lineHeight
        var baseline = padding +
                ((maxHeight - totalTextHeight) / 2f).coerceAtLeast(0f) -
                textPaint.fontMetrics.ascent
        fitted.lines.forEach { line ->
            drawTranslatedLine(canvas, line, padding, baseline, textColor)
            baseline += fitted.lineHeight
        }
        return bitmap
    }

    private fun drawTranslatedLine(
        canvas: Canvas,
        line: String,
        x: Float,
        baseline: Float,
        textColor: Int
    ) {
        textPaint.setShadowLayer(2f, SHADOW_OFFSET, SHADOW_OFFSET, Color.BLACK)
        textPaint.color = textColor
        canvas.drawText(line, x, baseline, textPaint)
        textPaint.clearShadowLayer()
        textPaint.color = textColor
        canvas.drawText(line, x, baseline, textPaint)
    }

    private fun fitLines(text: String, maxWidth: Float, maxHeight: Float): FittedLines {
        val source = text.ifBlank { "未识别到文字" }
        val minSize = 12f
        val maxSize = maxOf(
            minSize,
            minOf(maxHeight * 0.92f, maxWidth * 0.72f, 180f)
        )
        var low = minSize
        var high = maxSize
        var best: FittedLines? = null

        repeat(18) {
            val size = (low + high) / 2f
            textPaint.textSize = size
            val lineHeight = textPaint.fontSpacing
            val lines = wrapText(source, maxWidth)
            val fits = lines.isNotEmpty() &&
                    lines.size * lineHeight <= maxHeight &&
                    lines.all { textPaint.measureText(it) <= maxWidth + 1f }
            if (fits) {
                best = FittedLines(lines, lineHeight, size)
                low = size
            } else {
                high = size
            }
        }

        best?.let { return it }

        textPaint.textSize = minSize
        val lineHeight = textPaint.fontSpacing
        val maxLines = (maxHeight / lineHeight).toInt().coerceAtLeast(1)
        val lines = wrapText(source, maxWidth).take(maxLines).toMutableList()
        if (lines.isNotEmpty()) {
            lines[lines.lastIndex] = ellipsize(lines.last(), maxWidth)
        }
        return FittedLines(lines.ifEmpty { listOf("...") }, lineHeight, minSize)
    }

    private fun wrapText(text: String, maxWidth: Float): List<String> {
        return text
            .lines()
            .flatMap { paragraph ->
                if (paragraph.isBlank()) {
                    listOf("")
                } else {
                    wrapParagraph(paragraph, maxWidth)
                }
            }
            .filter { it.isNotBlank() }
    }

    private fun wrapParagraph(paragraph: String, maxWidth: Float): List<String> {
        val lines = mutableListOf<String>()
        var remaining = paragraph.trim()
        while (remaining.isNotEmpty()) {
            val count = textPaint.breakText(remaining, true, maxWidth, null)
                .coerceAtLeast(1)
            lines.add(remaining.take(count).trim())
            remaining = remaining.drop(count).trimStart()
        }
        return lines
    }

    private fun ellipsize(text: String, maxWidth: Float): String {
        if (textPaint.measureText(text) <= maxWidth) return text
        val suffix = "..."
        val count = textPaint.breakText(
            text,
            true,
            (maxWidth - textPaint.measureText(suffix)).coerceAtLeast(1f),
            null
        )
        return text.take(count).trimEnd() + suffix
    }

    private data class FittedLines(
        val lines: List<String>,
        val lineHeight: Float,
        val textSize: Float
    )

    private companion object {
        private val FGO_TEXT_COLOR = Color.rgb(245, 245, 240)
        private const val SHADOW_OFFSET = 2f
    }
}
