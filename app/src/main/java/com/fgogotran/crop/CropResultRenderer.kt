package com.fgogotran.crop

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import com.fgogotran.data.SettingsRepository
import com.fgogotran.overlay.FgoTypefaceProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CropResultRenderer @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(190, 0, 0, 0)
        style = Paint.Style.FILL
    }
    private val overlayClearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(235, 27, 51, 85)
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
        textColor: Int = FGO_TEXT_COLOR,
        targetLocale: String = SettingsRepository.TARGET_LOCALE_SIMPLIFIED
    ): Bitmap {
        val bitmapWidth = width.coerceAtLeast(1)
        val bitmapHeight = height.coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawRect(0f, 0f, bitmapWidth.toFloat(), bitmapHeight.toFloat(), backgroundPaint)

        textPaint.typeface = FgoTypefaceProvider.storyTypeface(context, targetLocale)
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

    fun renderOverlay(
        width: Int,
        height: Int,
        text: String,
        sourceLines: List<CropTextLine>,
        textColor: Int = FGO_TEXT_COLOR,
        targetLocale: String = SettingsRepository.TARGET_LOCALE_SIMPLIFIED
    ): Bitmap {
        val bitmapWidth = width.coerceAtLeast(1)
        val bitmapHeight = height.coerceAtLeast(1)
        val cropBounds = Rect(0, 0, bitmapWidth, bitmapHeight)
        val lineBounds = sourceLines.mapNotNull { clippedLineBounds(it.boundingBox, cropBounds) }
        if (lineBounds.isEmpty()) {
            return render(bitmapWidth, bitmapHeight, text, textColor, targetLocale)
        }

        val bitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        textPaint.typeface = FgoTypefaceProvider.storyTypeface(context, targetLocale)

        val layouts = fitTranslatedRows(
            text = text.trim(),
            sourceBounds = lineBounds,
            bitmapWidth = bitmapWidth,
            bitmapHeight = bitmapHeight
        )
        val renderedRows = layouts.map { layout ->
            textPaint.textSize = layout.textSize
            val renderedWidth = textPaint.measureText(layout.text)
            val clearBox = rowClearBox(
                sourceBounds = layout.sourceBounds,
                textArea = layout.textArea,
                renderedWidth = renderedWidth,
                lineHeight = layout.lineHeight,
                bitmapWidth = bitmapWidth,
                bitmapHeight = bitmapHeight
            )
            RenderedCropRow(layout, clearBox)
        }
        renderedRows.forEach { row ->
            val clearBox = row.clearBox
            val radius = (minOf(clearBox.width(), clearBox.height()) * 0.12f).coerceIn(4f, 9f)
            canvas.drawRoundRect(clearBox, radius, radius, overlayClearPaint)
        }
        renderedRows.forEach { row ->
            val layout = row.layout
            if (layout.text.isNotBlank()) {
                textPaint.textSize = layout.textSize
                val baseline = rowBaseline(layout.textArea, layout.lineHeight)
                canvas.save()
                canvas.clipRect(row.clearBox)
                drawTranslatedLine(canvas, layout.text, layout.textArea.left, baseline, textColor)
                canvas.restore()
            }
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

    private fun fitLines(
        text: String,
        maxWidth: Float,
        maxHeight: Float,
        minSize: Float = 12f,
        maxSizeLimit: Float? = null
    ): FittedLines {
        val source = text.ifBlank { "未识别到文字" }
        val safeMinSize = minSize.coerceAtLeast(6f)
        val maxSize = maxOf(
            safeMinSize,
            maxSizeLimit ?: minOf(maxHeight * 0.92f, maxWidth * 0.72f, 180f)
        )
        var low = safeMinSize
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

        textPaint.textSize = safeMinSize
        val lineHeight = textPaint.fontSpacing
        val maxLines = (maxHeight / lineHeight).toInt().coerceAtLeast(1)
        val lines = wrapText(source, maxWidth).take(maxLines).toMutableList()
        if (lines.isNotEmpty()) {
            lines[lines.lastIndex] = ellipsize(lines.last(), maxWidth)
        }
        return FittedLines(lines.ifEmpty { listOf("...") }, lineHeight, safeMinSize)
    }

    private fun fitTranslatedRows(
        text: String,
        sourceBounds: List<Rect>,
        bitmapWidth: Int,
        bitmapHeight: Int
    ): List<CropRowLayout> {
        val rows = sourceBounds
            .sortedWith(compareBy<Rect> { it.top }.thenBy { it.left })
            .map { bounds ->
                CropTextRow(
                    sourceBounds = Rect(bounds),
                    textArea = rowTextArea(bounds, bitmapWidth, bitmapHeight)
                )
            }
        if (rows.isEmpty()) return emptyList()

        val sourceLineHeight = medianLineHeight(sourceBounds)
        val smallestRowHeight = rows.minOf { it.textArea.height() }
        val maxTextSize = (sourceLineHeight * 0.98f)
            .coerceIn(12f, minOf(smallestRowHeight * 0.78f, 60f).coerceAtLeast(12f))
        val minTextSize = (sourceLineHeight * 0.42f).coerceIn(8f, maxTextSize)
        var low = minTextSize
        var high = maxTextSize
        var best: List<CropRowLayout>? = null

        repeat(18) {
            val size = (low + high) / 2f
            val candidate = layoutRowsAtSize(text, rows, size, ellipsizeLast = false)
            if (candidate != null) {
                best = candidate
                low = size
            } else {
                high = size
            }
        }

        return best ?: layoutRowsAtSize(text, rows, minTextSize, ellipsizeLast = true).orEmpty()
    }

    private fun layoutRowsAtSize(
        text: String,
        rows: List<CropTextRow>,
        textSize: Float,
        ellipsizeLast: Boolean
    ): List<CropRowLayout>? {
        textPaint.textSize = textSize
        val lineHeight = textPaint.fontSpacing
        if (lineHeight > rows.minOf { it.textArea.height() } + 0.5f) return null
        val rowTexts = splitTextForRows(text, rows, ellipsizeLast) ?: return null
        return rows.mapIndexed { index, row ->
            CropRowLayout(
                sourceBounds = row.sourceBounds,
                textArea = row.textArea,
                text = rowTexts.getOrElse(index) { "" },
                textSize = textSize,
                lineHeight = lineHeight
            )
        }
    }

    private fun splitTextForRows(
        text: String,
        rows: List<CropTextRow>,
        ellipsizeLast: Boolean
    ): List<String>? {
        val source = text.replace(Regex("""\s+"""), " ").trim().ifBlank { "..." }
        val result = MutableList(rows.size) { "" }
        var remaining = source

        for (index in rows.indices) {
            if (remaining.isBlank()) break
            val width = rows[index].textArea.width()
            if (index == rows.lastIndex) {
                if (textPaint.measureText(remaining) <= width) {
                    result[index] = remaining
                    remaining = ""
                } else if (ellipsizeLast) {
                    result[index] = ellipsize(remaining, width)
                    remaining = ""
                } else {
                    return null
                }
            } else {
                val count = textPaint.breakText(remaining, true, width, null)
                    .coerceAtLeast(1)
                val split = splitIndexForRow(remaining, count)
                result[index] = remaining.take(split).trimEnd()
                remaining = remaining.drop(split).trimStart()
            }
        }

        return if (remaining.isBlank()) result else null
    }

    private fun splitIndexForRow(text: String, maxCount: Int): Int {
        if (maxCount >= text.length) return text.length
        val safeMax = maxCount.coerceIn(1, text.length)
        val minSplit = (safeMax * 0.55f).toInt().coerceAtLeast(1)
        for (index in safeMax downTo minSplit) {
            if (text[index - 1] in ROW_BREAK_CHARS) return index
        }
        for (index in safeMax downTo minSplit) {
            if (text[index - 1].isWhitespace()) return index
        }
        return safeMax
    }

    private fun rowTextArea(sourceBounds: Rect, width: Int, height: Int): RectF {
        val horizontalInset = (width * 0.03f).coerceIn(5f, 24f)
        val verticalInset = (height * 0.035f).coerceIn(3f, 14f)
        val sourceHeight = sourceBounds.height().coerceAtLeast(1).toFloat()
        val sourcePadX = (sourceHeight * 0.24f).coerceIn(4f, 16f)
        val sourcePadY = (sourceHeight * 0.14f).coerceIn(3f, 9f)
        val desiredHeight = (sourceHeight + sourcePadY * 2f).coerceAtLeast(18f)
        val leftLimit = (width - horizontalInset - 1f).coerceAtLeast(horizontalInset)
        val topLimit = (height - verticalInset - desiredHeight).coerceAtLeast(verticalInset)
        val left = (sourceBounds.left - sourcePadX).coerceIn(horizontalInset, leftLimit)
        val top = (sourceBounds.top - sourcePadY).coerceIn(verticalInset, topLimit)
        val right = (sourceBounds.right + sourcePadX)
            .coerceAtMost(width - horizontalInset)
            .coerceAtLeast(left + 1f)
        return RectF(
            left,
            top,
            right,
            (top + desiredHeight).coerceAtMost(height - verticalInset).coerceAtLeast(top + 1f)
        )
    }

    private fun rowClearBox(
        sourceBounds: Rect,
        textArea: RectF,
        renderedWidth: Float,
        lineHeight: Float,
        bitmapWidth: Int,
        bitmapHeight: Int
    ): RectF {
        val sourceHeight = sourceBounds.height().coerceAtLeast(1).toFloat()
        val sourcePadX = (sourceHeight * 0.24f).coerceIn(4f, 16f)
        val sourcePadY = (sourceHeight * 0.14f).coerceIn(3f, 9f)
        val textPadX = (lineHeight * 0.16f).coerceIn(4f, 12f)
        val textPadY = (lineHeight * 0.08f).coerceIn(3f, 8f)
        return boundedRect(
            left = minOf(sourceBounds.left - sourcePadX, textArea.left - textPadX),
            top = minOf(sourceBounds.top - sourcePadY, textArea.top - textPadY),
            right = maxOf(sourceBounds.right + sourcePadX, textArea.left + renderedWidth + textPadX),
            bottom = maxOf(sourceBounds.bottom + sourcePadY, textArea.top + lineHeight + textPadY),
            bounds = RectF(0f, 0f, bitmapWidth.toFloat(), bitmapHeight.toFloat())
        )
    }

    private fun rowBaseline(textArea: RectF, lineHeight: Float): Float {
        val extraHeight = (textArea.height() - lineHeight).coerceAtLeast(0f)
        return textArea.top + extraHeight / 2f - textPaint.fontMetrics.ascent
    }

    private fun clippedLineBounds(sourceBounds: Rect, cropBounds: Rect): Rect? {
        val bounds = Rect(sourceBounds)
        if (!bounds.intersect(cropBounds)) return null
        if (bounds.width() <= 0 || bounds.height() <= 0) return null
        return bounds
    }

    private fun boundedRect(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        bounds: RectF
    ): RectF {
        val safeLeft = left.coerceIn(bounds.left, (bounds.right - 1f).coerceAtLeast(bounds.left))
        val safeTop = top.coerceIn(bounds.top, (bounds.bottom - 1f).coerceAtLeast(bounds.top))
        val safeRight = right.coerceAtMost(bounds.right).coerceAtLeast(safeLeft + 1f)
        val safeBottom = bottom.coerceAtMost(bounds.bottom).coerceAtLeast(safeTop + 1f)
        return RectF(safeLeft, safeTop, safeRight, safeBottom)
    }

    private fun medianLineHeight(bounds: List<Rect>): Float {
        val heights = bounds.map { it.height().coerceAtLeast(1) }.sorted()
        return heights[heights.size / 2].toFloat()
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

    private data class CropTextRow(
        val sourceBounds: Rect,
        val textArea: RectF
    )

    private data class CropRowLayout(
        val sourceBounds: Rect,
        val textArea: RectF,
        val text: String,
        val textSize: Float,
        val lineHeight: Float
    )

    private data class RenderedCropRow(
        val layout: CropRowLayout,
        val clearBox: RectF
    )

    private companion object {
        private val FGO_TEXT_COLOR = Color.rgb(245, 245, 240)
        private const val SHADOW_OFFSET = 2f
        private val ROW_BREAK_CHARS = setOf(
            '，', '。', '！', '？', '、', '；', '：',
            ',', '.', '!', '?', ';', ':', ' '
        )
    }
}
