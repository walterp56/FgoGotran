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
 * @property textColor sampled original text color from the screenshot
 * @property backgroundColor sampled background color for clearing the area
 */
data class RenderInstruction(
    val region: ClassifiedRegion,
    val translatedText: String,
    val textColor: Int,
    val backgroundColor: Int
)

/**
 * Renders translated Chinese text onto a copy of the screenshot bitmap.
 *
 * ## Rendering process (per region)
 * 1. **Clear** the region with the sampled background color (erases original JP text)
 * 2. **Measure** the CN text and adjust font size if it's too wide
 * 3. **Render** the CN text with a dark shadow for readability on any background
 *
 * ## Shadow technique
 * Each text character is drawn twice:
 * - First pass: dark semi-transparent shadow offset by 1px (provides contrast)
 * - Second pass: the actual text color on top
 * This works with light OR dark backgrounds without needing to detect background brightness.
 *
 * ## Font
 * Uses NotoSansCJKsc-Regular.otf (Noto Sans CJK SC) for proper Simplified Chinese rendering.
 * Falls back to the system default typeface if the font file is missing.
 */
@Singleton
class OverlayRenderer @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private var typeface: Typeface? = null

    /**
     * Base font size in pixels.
     * 32px is readable at FGO's typical dialogue line height on 1080p+ screens
     * without overlapping adjacent lines.
     */
    private var baseFontSize = 32f

    /** Shadow offset in pixels — small offset gives the best contrast-to-blur ratio. */
    private val shadowOffset = 1f

    private val tag = "OverlayRenderer"

    init {
        try {
            typeface = Typeface.createFromAsset(context.assets, "fonts/NotoSansCJKsc-Regular.otf")
            FgoLogger.info(tag, "CJK font loaded successfully")
        } catch (e: Exception) {
            FgoLogger.warn(tag, "Font load failed, using system default", e)
            typeface = Typeface.DEFAULT
        }
    }

    /**
     * Renders all [instructions] onto a copy of [bitmap].
     *
     * @param bitmap the original screenshot (not modified — we work on a copy)
     * @param instructions one per classified region
     * @param screenWidth screen width (unused currently, kept for future positioning)
     * @param screenHeight screen height (unused currently)
     * @return a new bitmap with Chinese text rendered over cleared JP regions
     */
    fun render(
        bitmap: Bitmap,
        instructions: List<RenderInstruction>,
        screenWidth: Int,
        screenHeight: Int
    ): Bitmap {
        FgoLogger.info(tag, "Rendering ${instructions.size} instructions onto ${bitmap.width}x${bitmap.height}")

        // Copy the bitmap so we don't modify the original (which may be a HardwareBuffer wrapper)
        val result = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(result)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = this@OverlayRenderer.typeface ?: Typeface.DEFAULT
            textAlign = Paint.Align.LEFT
        }

        for (instruction in instructions) {
            when (instruction.region.region) {
                TextRegion.DIALOGUE_BOX -> renderDialogueBox(canvas, paint, instruction)
                TextRegion.NAME_LABEL -> renderNameLabel(canvas, paint, instruction)
                TextRegion.CHOICE_BUTTON -> renderChoiceButton(canvas, paint, instruction)
            }
        }

        return result
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

        // ── 1. Clear the dialogue box area ──
        val clearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = instruction.backgroundColor
            style = Paint.Style.FILL
        }
        // Rounded rect matches FGO's dialogue box corner radius
        canvas.drawRoundRect(
            box.left.toFloat(), box.top.toFloat(),
            box.right.toFloat(), box.bottom.toFloat(),
            12f, 12f, clearPaint
        )

        // ── 2. Measure and size ──
        val cnLines = instruction.translatedText.split("\n").filter { it.isNotBlank() }
        val originalLines = instruction.region.lines

        paint.color = instruction.textColor
        paint.textSize = baseFontSize

        val maxWidth = box.width() - 24  // 12px padding on each side
        val lineHeight = paint.fontSpacing * 1.3f  // 30% extra for readability

        // Shrink font if any line is too wide for the box
        val adjustedSize = adjustFontSize(paint, cnLines, maxWidth.toFloat(), baseFontSize)
        paint.textSize = adjustedSize

        // ── 3. Render text (vertical center alignment within the box) ──
        val totalTextHeight = cnLines.size * lineHeight
        val startY = box.centerY() - totalTextHeight / 2f + lineHeight / 2f

        for ((i, line) in cnLines.withIndex()) {
            val y = startY + i * lineHeight
            val x = box.left + 12f

            // Shadow pass: dark semi-transparent offset for contrast on any background
            paint.setShadowLayer(2f, shadowOffset, shadowOffset, Color.argb(128, 0, 0, 0))
            canvas.drawText(line, x, y, paint)
            // Main pass: the sampled text color
            paint.clearShadowLayer()
            paint.color = instruction.textColor
            canvas.drawText(line, x, y, paint)
        }
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
        val padding = 4

        // ── 1. Clear name label area ──
        val clearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = instruction.backgroundColor
            style = Paint.Style.FILL
        }
        canvas.drawRoundRect(
            (box.left - padding).toFloat(), (box.top - padding).toFloat(),
            (box.right + padding * 2).toFloat(), (box.bottom + padding).toFloat(),
            8f, 8f, clearPaint
        )

        // ── 2. Render CN name ──
        // Name labels are slightly smaller than dialogue text (0.85x)
        val name = instruction.translatedText.trim()
        paint.apply {
            color = instruction.textColor
            textSize = baseFontSize * 0.85f
        }

        val x = box.left.toFloat()
        val y = box.centerY() + paint.textSize / 3f

        paint.setShadowLayer(2f, shadowOffset, shadowOffset, Color.argb(128, 0, 0, 0))
        canvas.drawText(name, x, y, paint)
        paint.clearShadowLayer()
        paint.color = instruction.textColor
        canvas.drawText(name, x, y, paint)
    }

    /**
     * Renders a choice button with horizontally centered text.
     */
    private fun renderChoiceButton(
        canvas: Canvas,
        paint: Paint,
        instruction: RenderInstruction
    ) {
        val box = instruction.region.boundingBox
        val padding = 8

        // ── 1. Clear button area ──
        val clearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = instruction.backgroundColor
            style = Paint.Style.FILL
        }
        canvas.drawRoundRect(
            (box.left - padding).toFloat(), (box.top - padding).toFloat(),
            (box.right + padding).toFloat(), (box.bottom + padding).toFloat(),
            10f, 10f, clearPaint
        )

        // ── 2. Render CN choice text (center-aligned) ──
        val text = instruction.translatedText.trim()
        paint.apply {
            color = instruction.textColor
            textSize = baseFontSize * 0.9f  // Choices slightly smaller than dialogue
        }

        // Center the text horizontally within the button bounding box
        val textWidth = paint.measureText(text)
        val x = box.centerX() - textWidth / 2f
        val y = box.centerY() + paint.textSize / 3f

        paint.setShadowLayer(2f, shadowOffset, shadowOffset, Color.argb(128, 0, 0, 0))
        canvas.drawText(text, x, y, paint)
        paint.clearShadowLayer()
        paint.color = instruction.textColor
        canvas.drawText(text, x, y, paint)
    }

    /**
     * Shrinks the font size if any [line] exceeds [maxWidth] pixels.
     *
     * Decrements size by 2px at a time (a good balance between precision and speed).
     * The minimum font size is 14px — below that, Chinese characters become illegible
     * on most device screens.
     */
    private fun adjustFontSize(
        paint: Paint,
        lines: List<String>,
        maxWidth: Float,
        baseSize: Float
    ): Float {
        var size = baseSize
        for (line in lines) {
            paint.textSize = size
            while (paint.measureText(line) > maxWidth && size > 14f) {
                size -= 2f
                paint.textSize = size
            }
        }
        if (size != baseSize) {
            FgoLogger.debug(tag, "Font size adjusted: ${baseSize} → $size")
        }
        return size
    }
}
