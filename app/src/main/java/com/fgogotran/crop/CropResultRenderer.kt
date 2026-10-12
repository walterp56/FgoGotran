package com.fgogotran.crop

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.util.TypedValue
import com.fgogotran.data.SettingsRepository
import com.fgogotran.overlay.FgoTypefaceProvider
import com.fgogotran.overlay.VisualTextMetrics
import com.fgogotran.translation.FgoDialogueSymbols
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ceil

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
        referenceScale: Float,
        textColor: Int = FGO_TEXT_COLOR,
        targetLocale: String = SettingsRepository.TARGET_LANGUAGE_SIMPLIFIED
    ): Bitmap {
        val bitmapWidth = width.coerceAtLeast(1)
        val bitmapHeight = height.coerceAtLeast(1)
        val sizing = CropRenderSizing(referenceScale)
        val bitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawRect(0f, 0f, bitmapWidth.toFloat(), bitmapHeight.toFloat(), backgroundPaint)

        textPaint.typeface = FgoTypefaceProvider.storyTypeface(context, targetLocale)
        val padding = sizing.panelPadding(bitmapWidth, bitmapHeight)
        val maxWidth = (bitmapWidth - padding * 2f).coerceAtLeast(1f)
        val maxHeight = (bitmapHeight - padding * 2f).coerceAtLeast(1f)
        val fitted = fitLines(
            normalizeForRender(text.trim(), targetLocale),
            maxWidth,
            maxHeight,
            minSize = sizing.px(12f),
            maxSizeLimit = sizing.maximumPanelTextSize(maxWidth, maxHeight),
            widthTolerance = sizing.px(1f),
            wordWrap = isEnglishTarget(targetLocale),
            targetLanguage = targetLocale
        )

        textPaint.textSize = fitted.textSize
        val totalTextHeight = fitted.lines.size * fitted.lineHeight
        var baseline = VisualTextMetrics.baselineForTop(
            paint = textPaint,
            top = padding + ((maxHeight - totalTextHeight) / 2f).coerceAtLeast(0f),
            targetLanguage = targetLocale,
            text = fitted.lines.firstOrNull().orEmpty()
        )
        fitted.lines.forEach { line ->
            drawTranslatedLine(canvas, line, padding, baseline, textColor, sizing.px(2f))
            baseline += fitted.lineHeight
        }
        return bitmap
    }

    fun renderOverlay(
        width: Int,
        height: Int,
        text: String,
        sourceLines: List<CropTextLine>,
        referenceScale: Float,
        textColor: Int = FGO_TEXT_COLOR,
        targetLocale: String = SettingsRepository.TARGET_LANGUAGE_SIMPLIFIED
    ): Bitmap {
        val bitmapWidth = width.coerceAtLeast(1)
        val bitmapHeight = height.coerceAtLeast(1)
        val sizing = CropRenderSizing(referenceScale)
        val cropBounds = Rect(0, 0, bitmapWidth, bitmapHeight)
        val lineBounds = sourceLines.mapNotNull { clippedLineBounds(it.boundingBox, cropBounds) }
        if (lineBounds.isEmpty()) {
            return render(bitmapWidth, bitmapHeight, text, referenceScale, textColor, targetLocale)
        }

        val bitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        textPaint.typeface = FgoTypefaceProvider.storyTypeface(context, targetLocale)

        val layouts = fitTranslatedRows(
            text = normalizeForRender(text.trim(), targetLocale),
            sourceBounds = lineBounds,
            bitmapWidth = bitmapWidth,
            bitmapHeight = bitmapHeight,
            sizing = sizing
        )
        val renderedRows = layouts.map { layout ->
            textPaint.textSize = layout.textSize
            val renderedWidth = textPaint.measureText(layout.text)
            val clearBox = sizing.clearBox(
                source = layout.sourceBounds.toRenderRect(),
                area = layout.textArea.toRenderRect(),
                renderedWidthPx = renderedWidth,
                lineHeightPx = layout.lineHeight,
                width = bitmapWidth,
                height = bitmapHeight
            ).toRectF()
            RenderedCropRow(layout, clearBox)
        }
        renderedRows.forEach { row ->
            val clearBox = row.clearBox
            val radius = sizing.cornerRadius(clearBox.width(), clearBox.height())
            canvas.drawRoundRect(clearBox, radius, radius, overlayClearPaint)
        }
        renderedRows.forEach { row ->
            val layout = row.layout
            if (layout.text.isNotBlank()) {
                textPaint.textSize = layout.textSize
                val baseline = rowBaseline(
                    textArea = layout.textArea,
                    lineHeight = layout.lineHeight,
                    text = layout.text,
                    targetLanguage = targetLocale
                )
                canvas.save()
                canvas.clipRect(row.clearBox)
                drawTranslatedLine(canvas, layout.text, layout.textArea.left, baseline, textColor, sizing.px(2f))
                canvas.restore()
            }
        }
        return bitmap
    }

    /** App UI message: fixed Android sp, not enlarged to fill the crop or tied to FGO's scale. */
    fun renderStatus(width: Int, height: Int, text: String, maximumHeight: Int): Bitmap {
        val metrics = context.resources.displayMetrics
        textPaint.typeface = Typeface.DEFAULT
        textPaint.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 16f, metrics)
        val bitmapWidth = width.coerceAtLeast(1)
        val padding = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 8f, metrics)
            .coerceAtMost((bitmapWidth - 1f) / 2f)
        val maxWidth = (bitmapWidth - padding * 2f).coerceAtLeast(1f)
        val lineHeight = textPaint.fontSpacing
        val wrapped = wrapText(text, maxWidth, wordWrap = true)
        val desiredHeight = ceil(wrapped.size * lineHeight + padding * 2f).toInt()
        val bitmapHeight = maxOf(height, desiredHeight, 1).coerceAtMost(maximumHeight.coerceAtLeast(1))
        val maxLines = ((bitmapHeight - padding * 2f) / lineHeight).toInt().coerceAtLeast(1)
        val lines = wrapped.take(maxLines).toMutableList()
        if (wrapped.size > maxLines && lines.isNotEmpty()) {
            lines[lines.lastIndex] = ellipsize(lines.last(), maxWidth, wrapped.last(), forceTruncationMarker = true)
        }
        val bitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawRect(0f, 0f, bitmapWidth.toFloat(), bitmapHeight.toFloat(), backgroundPaint)
        val top = padding + ((bitmapHeight - padding * 2f - lines.size * lineHeight) / 2f).coerceAtLeast(0f)
        val shadowPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 2f / 3f, metrics)
        var baseline = top - textPaint.fontMetrics.ascent
        lines.forEach { line ->
            drawTranslatedLine(canvas, line, padding, baseline, FGO_TEXT_COLOR, shadowPx)
            baseline += lineHeight
        }
        return bitmap
    }

    private fun drawTranslatedLine(
        canvas: Canvas,
        line: String,
        x: Float,
        baseline: Float,
        textColor: Int,
        shadowPx: Float
    ) {
        textPaint.setShadowLayer(shadowPx, shadowPx, shadowPx, Color.BLACK)
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
        minSize: Float,
        maxSizeLimit: Float,
        widthTolerance: Float,
        wordWrap: Boolean = false,
        targetLanguage: String = SettingsRepository.TARGET_LANGUAGE_SIMPLIFIED
    ): FittedLines {
        val source = if (isEnglishTarget(targetLanguage)) {
            text
        } else {
            text.ifBlank { "未识别到文字" }
        }
        val safeMinSize = minSize
        val maxSize = maxOf(safeMinSize, maxSizeLimit)
        var low = safeMinSize
        var high = maxSize
        var best: FittedLines? = null

        repeat(18) {
            val size = (low + high) / 2f
            textPaint.textSize = size
            val lineHeight = textPaint.fontSpacing
            val lines = wrapText(source, maxWidth, wordWrap)
            val fits = lines.isNotEmpty() &&
                    lines.size * lineHeight <= maxHeight &&
                    lines.all { textPaint.measureText(it) <= maxWidth + widthTolerance }
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
        val wrappedLines = wrapText(source, maxWidth, wordWrap)
        val lines = wrappedLines.take(maxLines).toMutableList()
        if (lines.isNotEmpty() && wrappedLines.size > maxLines) {
            lines[lines.lastIndex] = ellipsize(
                text = lines.last(),
                maxWidth = maxWidth,
                terminalSourceText = wrappedLines.last(),
                forceTruncationMarker = true
            )
        }
        return FittedLines(
            lines.ifEmpty { listOf(FgoDialogueSymbols.PAUSE_ELLIPSIS) },
            lineHeight,
            safeMinSize
        )
    }

    private fun fitTranslatedRows(
        text: String,
        sourceBounds: List<Rect>,
        bitmapWidth: Int,
        bitmapHeight: Int,
        sizing: CropRenderSizing
    ): List<CropRowLayout> {
        val rows = sourceBounds
            .sortedWith(compareBy<Rect> { it.top }.thenBy { it.left })
            .map { bounds ->
                val textArea = sizing.textArea(bounds.toRenderRect(), bitmapWidth, bitmapHeight)
                CropTextRow(
                    sourceBounds = Rect(bounds),
                    textArea = textArea.toRectF(),
                    maxTextWidth = sizing.maximumTextWidth(textArea, bitmapWidth)
                )
            }
        if (rows.isEmpty()) return emptyList()

        return layoutRowsAtSourceSize(text, rows, sizing, ellipsizeLast = false)
            ?: layoutRowsAtSourceSize(text, rows, sizing, ellipsizeLast = true).orEmpty()
    }

    private fun layoutRowsAtSourceSize(
        text: String,
        rows: List<CropTextRow>,
        sizing: CropRenderSizing,
        ellipsizeLast: Boolean
    ): List<CropRowLayout>? {
        explicitTranslatedRows(text, rows)?.let { explicitRows ->
            return explicitRows.mapIndexed { index, rowText ->
                layoutExplicitRow(rows[index], rowText, sizing)
            }
        }

        val rowTextSizes = rows.map { sourceSizedTextSize(it, sizing) }
        val rowTexts = splitTextForRows(text, rows, rowTextSizes, sizing, ellipsizeLast) ?: return null
        return rows.mapIndexed { index, row ->
            val textSize = rowTextSizes.getOrElse(index) { sizing.minimumRowTextSize }
            textPaint.textSize = textSize
            CropRowLayout(
                sourceBounds = row.sourceBounds,
                textArea = row.textArea,
                text = rowTexts.getOrElse(index) { "" },
                textSize = textSize,
                lineHeight = textPaint.fontSpacing
            )
        }
    }

    private fun splitTextForRows(
        text: String,
        rows: List<CropTextRow>,
        rowTextSizes: List<Float>,
        sizing: CropRenderSizing,
        ellipsizeLast: Boolean
    ): List<String>? {
        val source = text.replace(Regex("""\s+"""), " ")
            .trim()
            .ifBlank { FgoDialogueSymbols.PAUSE_ELLIPSIS }
        val result = MutableList(rows.size) { "" }
        var remaining = source

        for (index in rows.indices) {
            if (remaining.isBlank()) break
            textPaint.textSize = rowTextSizes.getOrElse(index) { sizing.minimumRowTextSize }
            val width = if (index == rows.lastIndex) {
                rows[index].maxTextWidth
            } else {
                rows[index].textArea.width()
            }
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

    private fun explicitTranslatedRows(
        text: String,
        rows: List<CropTextRow>
    ): List<String>? {
        val lines = text.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }
        if (lines.size != rows.size) return null
        return lines
    }

    private fun layoutExplicitRow(
        row: CropTextRow,
        text: String,
        sizing: CropRenderSizing
    ): CropRowLayout {
        val textSize = fittedExplicitRowTextSize(row, text, sizing)
        textPaint.textSize = textSize
        val renderedText = if (textPaint.measureText(text) <= row.maxTextWidth + sizing.px(1f)) {
            text
        } else {
            ellipsize(text, row.maxTextWidth)
        }
        return CropRowLayout(
            sourceBounds = row.sourceBounds,
            textArea = row.textArea,
            text = renderedText,
            textSize = textSize,
            lineHeight = textPaint.fontSpacing
        )
    }

    private fun sourceSizedTextSize(row: CropTextRow, sizing: CropRenderSizing): Float {
        val sourceHeight = row.sourceBounds.height().coerceAtLeast(1).toFloat()
        val targetSize = sizing.sourceTextSize(sourceHeight)
        val minimumSize = sizing.minimumSourceTextSize(sourceHeight, targetSize)
        var low = minimumSize
        var high = targetSize
        var best = minimumSize

        repeat(14) {
            val size = (low + high) / 2f
            textPaint.textSize = size
            if (textPaint.fontSpacing <= row.textArea.height() + sizing.px(0.5f)) {
                best = size
                low = size
            } else {
                high = size
            }
        }

        return best
    }

    private fun fittedExplicitRowTextSize(row: CropTextRow, text: String, sizing: CropRenderSizing): Float {
        val maxSize = sourceSizedTextSize(row, sizing)
        val minSize = sizing.minimumSourceTextSize(row.sourceBounds.height().coerceAtLeast(1).toFloat(), maxSize)
        textPaint.textSize = maxSize
        if (textPaint.measureText(text) <= row.maxTextWidth + sizing.px(1f)) return maxSize

        var low = minSize
        var high = maxSize
        var best = minSize
        repeat(14) {
            val size = (low + high) / 2f
            textPaint.textSize = size
            if (textPaint.measureText(text) <= row.maxTextWidth + sizing.px(1f) &&
                textPaint.fontSpacing <= row.textArea.height() + sizing.px(0.5f)
            ) {
                best = size
                low = size
            } else {
                high = size
            }
        }
        return best
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

    private fun rowBaseline(
        textArea: RectF,
        lineHeight: Float,
        text: String,
        targetLanguage: String
    ): Float {
        val extraHeight = (textArea.height() - lineHeight).coerceAtLeast(0f)
        return VisualTextMetrics.baselineForTop(
            paint = textPaint,
            top = textArea.top + extraHeight / 2f,
            targetLanguage = targetLanguage,
            text = text
        )
    }

    private fun clippedLineBounds(sourceBounds: Rect, cropBounds: Rect): Rect? {
        val bounds = Rect(sourceBounds)
        if (!bounds.intersect(cropBounds)) return null
        if (bounds.width() <= 0 || bounds.height() <= 0) return null
        return bounds
    }

    private fun Rect.toRenderRect() = CropRenderRect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())

    private fun RectF.toRenderRect() = CropRenderRect(left, top, right, bottom)

    private fun CropRenderRect.toRectF() = RectF(left, top, right, bottom)

    private fun wrapText(text: String, maxWidth: Float, wordWrap: Boolean = false): List<String> {
        return text
            .lines()
            .flatMap { paragraph ->
                if (paragraph.isBlank()) {
                    listOf("")
                } else if (wordWrap) {
                    wrapEnglishParagraph(paragraph.trim(), maxWidth)
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

    private fun wrapEnglishParagraph(paragraph: String, maxWidth: Float): List<String> {
        if (paragraph.isBlank()) return emptyList()
        val lines = mutableListOf<String>()
        val words = paragraph.split(Regex("\\s+")).filter { it.isNotBlank() }
        var current = ""
        words.forEach { word ->
            val candidate = if (current.isBlank()) word else "$current $word"
            if (textPaint.measureText(candidate) <= maxWidth) {
                current = candidate
            } else {
                if (current.isNotBlank()) {
                    lines += current
                    current = ""
                }
                if (textPaint.measureText(word) <= maxWidth) {
                    current = word
                } else {
                    var remaining = word
                    while (remaining.isNotEmpty()) {
                        val count = textPaint.breakText(remaining, true, maxWidth, null).coerceAtLeast(1)
                        lines += remaining.take(count)
                        remaining = remaining.drop(count)
                    }
                }
            }
        }
        if (current.isNotBlank()) lines += current
        return lines
    }

    private fun normalizeForRender(text: String, targetLocale: String): String {
        if (isEnglishTarget(targetLocale)) {
            return text.replace("\r\n", "\n").replace('\r', '\n').trim()
        }
        return FgoDialogueSymbols.normalizeForRender(text)
    }

    private fun isEnglishTarget(targetLocale: String): Boolean {
        return SettingsRepository.normalizeTargetLanguage(targetLocale) ==
            SettingsRepository.TARGET_LANGUAGE_ENGLISH
    }

    private fun ellipsize(
        text: String,
        maxWidth: Float,
        terminalSourceText: String = text,
        forceTruncationMarker: Boolean = false
    ): String {
        if (!forceTruncationMarker && textPaint.measureText(text) <= maxWidth) return text
        val terminalSuffix = FgoDialogueSymbols.terminalDisplaySuffix(terminalSourceText)
        val suffix = if (terminalSuffix.startsWith('…')) {
            terminalSuffix
        } else {
            FgoDialogueSymbols.PAUSE_ELLIPSIS + terminalSuffix
        }
        val visibleTerminalSuffix = FgoDialogueSymbols.terminalDisplaySuffix(text)
        val body = if (visibleTerminalSuffix.isNotEmpty()) {
            text.dropLast(visibleTerminalSuffix.length)
        } else {
            text
        }
        val count = textPaint.breakText(
            body,
            true,
            (maxWidth - textPaint.measureText(suffix)).coerceAtLeast(1f),
            null
        )
        return body.take(count).trimEnd() + suffix
    }

    private data class FittedLines(
        val lines: List<String>,
        val lineHeight: Float,
        val textSize: Float
    )

    private data class CropTextRow(
        val sourceBounds: Rect,
        val textArea: RectF,
        val maxTextWidth: Float
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
        private val ROW_BREAK_CHARS = setOf(
            '，', '。', '！', '？', '、', '；', '：',
            ',', '.', '!', '?', ';', ':', ' '
        )
    }
}
