package com.fgogotran.overlay

import android.content.Context
import android.graphics.*
import com.fgogotran.util.FgoLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Instruction to render translated text into one screen region.
 * @property translatedText the Chinese text to render
 */
data class RenderInstruction(
    val region: ClassifiedRegion,
    val translatedText: String,
    val textColor: Int? = null
)

/**
 * Renders translated Chinese text onto a transparent overlay bitmap.
 *
 * ## Rendering process (per region)
 * 1. **Cover** the fixed FGO text surface with its matching UI color
 * 2. **Wrap** translated text into the available fixed layout width
 * 3. **Render** the Chinese text with FGO-like sizing, position, and shadow
 *
 * ## Shadow technique
 * Each text character is drawn twice:
 * - First pass: dark semi-transparent shadow offset by 1px (provides contrast)
 * - Second pass: the actual text color on top
 * This works with light OR dark backgrounds without needing to detect background brightness.
 *
 * ## Font
 * Uses the configured FGO story font asset if present, then falls back to the bundled CJK font.
 */
@Singleton
class OverlayRenderer @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private var typeface: Typeface? = null
    private var reusableBitmap: Bitmap? = null
    private val reusableCanvas = Canvas()
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
    }
    private val dialogueClearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = DIALOGUE_BACKGROUND
        style = Paint.Style.FILL
    }
    private val nameClearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = NAME_BACKGROUND
        style = Paint.Style.FILL
    }
    private val choiceClearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = CHOICE_BACKGROUND
        style = Paint.Style.FILL
    }

    companion object {
        private val DIALOGUE_BACKGROUND = Color.rgb(20, 34, 67)
        private val NAME_BACKGROUND = Color.rgb(52, 89, 138)
        private val CHOICE_BACKGROUND = Color.rgb(0, 0, 0)
        private val FGO_TEXT_COLOR = Color.rgb(245, 245, 240)
        private const val MIN_NAME_PLATE_WIDTH = 500f
        private const val CHOICE_REFERENCE_WIDTH = 1470f
        private const val CHOICE_REFERENCE_HEIGHT = 87f
        private const val CHOICE_MIN_WIDTH_RATIO = 0.78f
        private const val CHOICE_MAX_WIDTH_RATIO = 1.08f
    }

    /** Shadow offset in pixels at the marked 1080px reference height. */
    private val shadowOffset = 2f

    private val tag = "OverlayRenderer"

    init {
        typeface = FgoTypefaceProvider.storyTypeface(context)
        textPaint.typeface = typeface ?: Typeface.DEFAULT
    }

    /**
     * Renders all [instructions] onto a transparent bitmap matching [bitmap].
     *
     * @param bitmap the original screenshot (not modified — we work on a copy)
     * @param instructions one per classified region
     * @param screenWidth screen width (unused currently, kept for future positioning)
     * @param screenHeight screen height (unused currently)
     * @return a reusable bitmap with Chinese text rendered over cleared JP regions
     */
    fun render(
        bitmap: Bitmap,
        instructions: List<RenderInstruction>,
        screenWidth: Int,
        screenHeight: Int
    ): Bitmap {
        FgoLogger.info(tag, "Rendering ${instructions.size} instructions onto ${bitmap.width}x${bitmap.height}")

        val result = obtainReusableBitmap(bitmap.width, bitmap.height)
        result.eraseColor(Color.TRANSPARENT)
        reusableCanvas.setBitmap(result)
        textPaint.apply {
            typeface = this@OverlayRenderer.typeface ?: Typeface.DEFAULT
            textAlign = Paint.Align.LEFT
            isFakeBoldText = false
            isSubpixelText = true
            isLinearText = true
            clearShadowLayer()
        }

        for (instruction in instructions) {
            when (instruction.region.region) {
                TextRegion.DIALOGUE_BOX -> renderDialogueBox(reusableCanvas, textPaint, instruction)
                TextRegion.NAME_LABEL -> renderNameLabel(reusableCanvas, textPaint, instruction)
                TextRegion.CHOICE_BUTTON -> renderChoiceButton(reusableCanvas, textPaint, instruction)
            }
        }

        return result
    }

    private fun obtainReusableBitmap(width: Int, height: Int): Bitmap {
        val current = reusableBitmap
        if (current != null && !current.isRecycled && current.width == width && current.height == height) {
            return current
        }
        reusableBitmap?.recycle()
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
            reusableBitmap = it
            FgoLogger.info(tag, "Allocated reusable overlay bitmap: ${width}x$height")
        }
    }

    /**
     * Renders the dialogue box: clears the area, then paints multi-line CN text.
     */
    private fun renderDialogueBox(
        canvas: Canvas,
        paint: Paint,
        instruction: RenderInstruction
    ) {
        val box = instruction.region.boundingBox
        val scale = screenScale(canvas)

        canvas.drawRoundRect(
            box.left.toFloat(), box.top.toFloat(),
            box.right.toFloat(), box.bottom.toFloat(),
            12f, 12f, dialogueClearPaint
        )

        val leftInset = 92f * scale
        val textArea = RectF(
            box.left + leftInset,
            box.top + 24f * scale,
            box.right - 46f * scale,
            box.bottom - 18f * scale
        )

        val textColor = instruction.textColor ?: FGO_TEXT_COLOR
        paint.color = textColor
        val (lines, lineHeight) = fitWrappedText(
            text = instruction.translatedText,
            paint = paint,
            initialTextSize = 50f * scale,
            minimumTextSize = 28f * scale,
            maxWidth = textArea.width(),
            maxHeight = textArea.height()
        )
        val firstBaseline = textArea.top - paint.fontMetrics.ascent

        canvas.save()
        canvas.clipRect(textArea)
        drawLines(
            canvas = canvas,
            paint = paint,
            lines = lines,
            x = textArea.left,
            firstBaseline = firstBaseline,
            lineHeight = lineHeight,
            textColor = textColor
        )
        canvas.restore()
    }

    /**
     * Renders the character name label with smaller text.
     */
    private fun renderNameLabel(
        canvas: Canvas,
        paint: Paint,
        instruction: RenderInstruction
    ) {
        val box = instruction.region.boundingBox
        val scale = screenScale(canvas)
        val name = instruction.translatedText.trim()
        val minimumRenderedWidth = MIN_NAME_PLATE_WIDTH * scale

        paint.apply {
            color = instruction.textColor ?: FGO_TEXT_COLOR
            textSize = 48f * scale
        }

        val requiredWidth = 52f * scale + paint.measureText(name) + 28f * scale
        val renderedRight = (box.left + maxOf(minimumRenderedWidth, requiredWidth))
            .coerceAtMost(canvas.width.toFloat())

        canvas.drawRoundRect(
            box.left + 42f * scale, box.top + 8f * scale,
            renderedRight - 10f * scale, box.bottom - 8f * scale,
            8f, 8f, nameClearPaint
        )

        // FGO draws the speaker name inset from the arrow-shaped leading edge.
        val textArea = RectF(
            box.left + 52f * scale,
            box.top + 8f * scale,
            renderedRight - 20f * scale,
            box.bottom - 8f * scale
        )
        val fittedName = fitSingleLine(
            text = instruction.translatedText.trim(),
            paint = paint,
            initialTextSize = 48f * scale,
            minimumTextSize = 28f * scale,
            maxWidth = textArea.width()
        )

        canvas.save()
        canvas.clipRect(textArea)
        drawLines(
            canvas = canvas,
            paint = paint,
            lines = listOf(fittedName),
            x = textArea.left,
            firstBaseline = textArea.top - paint.fontMetrics.ascent,
            lineHeight = paint.textSize,
            textColor = instruction.textColor ?: FGO_TEXT_COLOR
        )
        canvas.restore()
    }

    /**
     * Renders a choice button with horizontally centered text.
     */
    private fun renderChoiceButton(
        canvas: Canvas,
        paint: Paint,
        instruction: RenderInstruction
    ) {
        val scale = screenScale(canvas)
        val box = fixedChoiceRenderBox(instruction.region.boundingBox, canvas, scale) ?: run {
            FgoLogger.debug(
                tag,
                "Skipping choice render with unexpected bounds: ${instruction.region.boundingBox.flattenToString()}"
            )
            return
        }
        val clearInsetX = 47f * scale
        val textInsetX = 70f * scale
        val preflightTextArea = RectF(
            box.left + textInsetX,
            box.top + 12f * scale,
            box.right - textInsetX,
            box.bottom - 12f * scale
        )
        if (fitSingleLineOrNull(
                text = instruction.translatedText.trim(),
                paint = paint,
                initialTextSize = 49f * scale,
                minimumTextSize = 27f * scale,
                maxWidth = preflightTextArea.width()
            ) == null
        ) {
            FgoLogger.debug(tag, "Skipping choice render because text does not fit fixed box")
            return
        }

        canvas.drawRoundRect(
            box.left + clearInsetX, box.top + 8f * scale,
            box.right - clearInsetX, box.bottom - 8f * scale,
            10f, 10f, choiceClearPaint
        )

        // ── 2. Render CN choice text (center-aligned) ──
        val textArea = RectF(
            box.left + textInsetX,
            box.top + 12f * scale,
            box.right - textInsetX,
            box.bottom - 12f * scale
        )
        val textColor = instruction.textColor ?: FGO_TEXT_COLOR
        paint.apply {
            color = textColor
        }
        val text = fitSingleLineOrNull(
            text = instruction.translatedText.trim(),
            paint = paint,
            initialTextSize = 49f * scale,
            minimumTextSize = 27f * scale,
            maxWidth = textArea.width()
        ) ?: run {
            FgoLogger.debug(tag, "Skipping choice render because text does not fit fixed box")
            return
        }

        // Center the text horizontally within the button bounding box
        val textWidth = paint.measureText(text)
        val x = box.centerX() - textWidth / 2f
        val y = box.centerY() + paint.textSize / 3f

        canvas.save()
        canvas.clipRect(textArea)
        paint.setShadowLayer(2f * scale, shadowOffset * scale, shadowOffset * scale, Color.BLACK)
        canvas.drawText(text, x, y, paint)
        paint.clearShadowLayer()
        paint.color = textColor
        canvas.drawText(text, x, y, paint)
        canvas.restore()
    }

    private fun fixedChoiceRenderBox(rawBox: Rect, canvas: Canvas, scale: Float): RectF? {
        val expectedWidth = CHOICE_REFERENCE_WIDTH * scale
        val expectedHeight = CHOICE_REFERENCE_HEIGHT * scale
        val rawWidth = rawBox.width().toFloat()
        if (rawWidth !in expectedWidth * CHOICE_MIN_WIDTH_RATIO..expectedWidth * CHOICE_MAX_WIDTH_RATIO) {
            return null
        }

        val left = (rawBox.centerX() - expectedWidth / 2f)
            .coerceIn(0f, (canvas.width - expectedWidth).coerceAtLeast(0f))
        val top = (rawBox.centerY() - expectedHeight / 2f)
            .coerceIn(0f, (canvas.height - expectedHeight).coerceAtLeast(0f))
        return RectF(left, top, left + expectedWidth, top + expectedHeight)
    }

    private fun wrapText(text: String, paint: Paint, maxWidth: Float): List<String> {
        val wrapped = mutableListOf<String>()
        text.lines().filter { it.isNotBlank() }.forEach { paragraph ->
            var remaining = paragraph.trim()
            while (remaining.isNotEmpty()) {
                val count = paint.breakText(remaining, true, maxWidth, null).coerceAtLeast(1)
                wrapped.add(remaining.take(count))
                remaining = remaining.drop(count).trimStart()
            }
        }
        return wrapped
    }

    private fun fitWrappedText(
        text: String,
        paint: Paint,
        initialTextSize: Float,
        minimumTextSize: Float,
        maxWidth: Float,
        maxHeight: Float
    ): Pair<List<String>, Float> {
        var textSize = initialTextSize
        while (true) {
            paint.textSize = textSize
            val lineHeight = textSize * 1.45f
            val lines = wrapText(text, paint, maxWidth)
            val maximumLines = (maxHeight / lineHeight).toInt().coerceAtLeast(1)
            if (lines.size <= maximumLines || textSize <= minimumTextSize) {
                return limitLines(lines, maximumLines, paint, maxWidth) to lineHeight
            }
            textSize = (textSize - 2f).coerceAtLeast(minimumTextSize)
        }
    }

    private fun fitSingleLine(
        text: String,
        paint: Paint,
        initialTextSize: Float,
        minimumTextSize: Float,
        maxWidth: Float
    ): String {
        paint.textSize = initialTextSize
        while (paint.measureText(text) > maxWidth && paint.textSize > minimumTextSize) {
            paint.textSize = (paint.textSize - 2f).coerceAtLeast(minimumTextSize)
        }
        return ellipsize(text, paint, maxWidth)
    }

    private fun fitSingleLineOrNull(
        text: String,
        paint: Paint,
        initialTextSize: Float,
        minimumTextSize: Float,
        maxWidth: Float
    ): String? {
        if (text.isBlank() || maxWidth <= 0f) return null
        paint.textSize = initialTextSize
        while (paint.measureText(text) > maxWidth && paint.textSize > minimumTextSize) {
            paint.textSize = (paint.textSize - 2f).coerceAtLeast(minimumTextSize)
        }
        return text.takeIf { paint.measureText(it) <= maxWidth }
    }

    private fun limitLines(
        lines: List<String>,
        maximumLines: Int,
        paint: Paint,
        maxWidth: Float
    ): List<String> {
        if (lines.size <= maximumLines) return lines
        val visible = lines.take(maximumLines).toMutableList()
        visible[visible.lastIndex] = ellipsize("${visible.last()}...", paint, maxWidth)
        return visible
    }

    private fun ellipsize(text: String, paint: Paint, maxWidth: Float): String {
        if (paint.measureText(text) <= maxWidth) return text
        val suffix = "..."
        val count = paint.breakText(
            text,
            true,
            (maxWidth - paint.measureText(suffix)).coerceAtLeast(1f),
            null
        )
        return text.take(count).trimEnd() + suffix
    }

    private fun drawLines(
        canvas: Canvas,
        paint: Paint,
        lines: List<String>,
        x: Float,
        firstBaseline: Float,
        lineHeight: Float,
        textColor: Int
    ) {
        for ((index, line) in lines.withIndex()) {
            val y = firstBaseline + index * lineHeight
            paint.setShadowLayer(2f, shadowOffset, shadowOffset, Color.BLACK)
            paint.color = textColor
            canvas.drawText(line, x, y, paint)
            paint.clearShadowLayer()
            paint.color = textColor
            canvas.drawText(line, x, y, paint)
        }
    }

    private fun screenScale(canvas: Canvas): Float {
        return (canvas.height / 1080f).coerceIn(0.6f, 1.4f)
    }
}
