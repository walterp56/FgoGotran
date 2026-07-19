package com.fgogotran.overlay

import android.content.Context
import android.graphics.*
import com.fgogotran.data.SettingsRepository
import com.fgogotran.ocr.OcrTextLine
import com.fgogotran.translation.FgoDialogueSymbols
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
    val sourceText: String = "",
    val textColor: Int? = null,
    val wideTextSpacing: Boolean = false,
    val targetLocale: String = SettingsRepository.TARGET_LOCALE_SIMPLIFIED,
    val showOriginalText: Boolean = false
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
        private val DIALOGUE_BACKGROUND = Color.rgb(27, 51, 85)
        private val NAME_BACKGROUND = Color.rgb(52, 89, 138)
        private val CHOICE_BACKGROUND = Color.rgb(0, 0, 0)
        private val FGO_TEXT_COLOR = Color.rgb(245, 245, 240)
        private val ORIGINAL_TEXT_COLOR = Color.rgb(80, 235, 235)
        private const val MIN_NAME_PLATE_WIDTH = 160f
        private const val CHOICE_REFERENCE_WIDTH = 1470f
        private const val CHOICE_REFERENCE_HEIGHT = 135f
        private const val CHOICE_MIN_WIDTH_RATIO = 0.78f
        private const val CHOICE_MAX_WIDTH_RATIO = 1.08f
        private const val DIALOGUE_MAX_LINES = 2
        private const val DIALOGUE_TEXT_LEFT_INSET = 108f
        private const val DIALOGUE_TEXT_TOP_INSET = 48f
        private const val DIALOGUE_TEXT_RIGHT_INSET = 46f
        private const val DIALOGUE_TEXT_BOTTOM_INSET = 12f
        private const val BILINGUAL_DIALOGUE_TEXT_TOP_INSET = 24f
        private const val BILINGUAL_DIALOGUE_TEXT_BOTTOM_INSET = 0f
        private const val DYNAMIC_DIALOGUE_HORIZONTAL_PADDING = 34f
        private const val DYNAMIC_DIALOGUE_LEFT_PADDING = 14f
        private const val DYNAMIC_DIALOGUE_VERTICAL_PADDING = 18f
        private const val DYNAMIC_DIALOGUE_TEXT_HORIZONTAL_INSET = 24f
        private const val DYNAMIC_DIALOGUE_TEXT_LEFT_INSET = 14f
        private const val DYNAMIC_DIALOGUE_TEXT_VERTICAL_INSET = 4f
        private const val SOURCE_BOUNDS_RUBY_MAX_CHARS = 14
        private const val SOURCE_BOUNDS_RUBY_HEIGHT_RATIO = 0.72f
        const val DIALOGUE_LINE_HEIGHT_MULTIPLIER = 1.55f
        private const val ORIGINAL_TEXT_SIZE_RATIO = 0.85f
        private const val ORIGINAL_LINE_HEIGHT_MULTIPLIER = 1.18f
        private const val BILINGUAL_PAIR_GAP = 10f
        private const val BILINGUAL_TRANSLATION_LINE_HEIGHT_MULTIPLIER = 1.08f
        private const val BILINGUAL_CHOICE_LINE_HEIGHT_MULTIPLIER = 1.08f
        private const val BILINGUAL_TRANSLATION_TEXT_SIZE = 53f
        private const val BILINGUAL_TRANSLATION_MIN_TEXT_SIZE = 24f
        private const val BILINGUAL_CHOICE_TEXT_SIZE = 53f
        private const val BILINGUAL_CHOICE_MIN_TEXT_SIZE = 24f
        private const val BILINGUAL_ORIGINAL_MAX_TEXT_SIZE = 31f
        private const val BILINGUAL_ORIGINAL_MIN_TEXT_SIZE = 14f
        private const val DIALOGUE_MIN_TEXT_SIZE = 28f
        private const val DIALOGUE_EMERGENCY_MIN_TEXT_SIZE = 22f
        private const val NAME_TEXT_SIZE = 56f
        private const val NAME_TEXT_MIN_SIZE = 31f
        private const val NAME_TEXT_LEFT_INSET = 52f
        private const val NAME_TEXT_TOP_INSET = 6f
        private const val NAME_TEXT_BOTTOM_INSET = 6f
        private const val NAME_TEXT_BASELINE_OFFSET = 12f
        private const val NAME_TEXT_RIGHT_INSET = 20f
        private const val CHOICE_TEXT_SIZE = 53f
        private const val CHOICE_TEXT_MIN_SIZE = 29f
        private const val WIDE_RENDER_SPACE = "\u3000"
        private val TRAILING_DASH_CLEAR_RISK = Regex("""[-ー‐‑‒–—―−─━－一]{2,}\s*$""")
        private val COUNTDOWN_CLEAR_RISK = Regex("""(?:[0-9\uFF10-\uFF19][ \t\u3000]+){2,}[0-9\uFF10-\uFF19]\s*[-ー‐‑‒–—―−─━－一]*\s*$""")
        private val WIDE_RENDER_CONNECTORS = listOf(
            "\u4EE5\u53CA", "\u8FD8\u6709", "\u6216\u8005", "\u4F46\u662F",
            "\u56E0\u6B64", "\u6240\u4EE5", "\u4E0D\u8FC7", "\u7136\u540E",
            "\u7684", "\u4E4B", "\u4E0E", "\u548C", "\u53CA", "\u5E76",
            "\u800C", "\u6216", "\u4F46", "\u4E5F", "\u662F"
        )
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
            textPaint.typeface = FgoTypefaceProvider.storyTypeface(context, instruction.targetLocale)
            when (instruction.region.region) {
                TextRegion.DIALOGUE_BOX -> renderDialogueBox(reusableCanvas, textPaint, instruction)
                TextRegion.NAME_LABEL -> renderNameLabel(reusableCanvas, textPaint, instruction)
                TextRegion.CHOICE_BUTTON -> renderChoiceButton(reusableCanvas, textPaint, instruction)
            }
        }

        return result
    }

    fun renderedChoiceBounds(
        instructions: List<RenderInstruction>,
        screenWidth: Int,
        screenHeight: Int
    ): List<Rect> {
        val scale = screenScale(screenWidth, screenHeight)
        return instructions.mapNotNull { instruction ->
            if (instruction.region.region != TextRegion.CHOICE_BUTTON) return@mapNotNull null
            val box = fixedChoiceRenderBox(
                rawBox = instruction.region.boundingBox,
                canvasWidth = screenWidth,
                canvasHeight = screenHeight,
                scale = scale
            ) ?: return@mapNotNull null

            Rect(
                kotlin.math.floor(box.left).toInt(),
                kotlin.math.floor(box.top).toInt(),
                kotlin.math.ceil(box.right).toInt(),
                kotlin.math.ceil(box.bottom).toInt()
            )
        }
    }

    fun renderedDialogueText(
        instruction: RenderInstruction,
        screenWidth: Int,
        screenHeight: Int
    ): String {
        if (instruction.region.region != TextRegion.DIALOGUE_BOX) {
            return instruction.translatedText.trim()
        }
        val historyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = FgoTypefaceProvider.storyTypeface(context, instruction.targetLocale)
            textAlign = Paint.Align.LEFT
            isSubpixelText = true
            isLinearText = true
        }
        val scale = screenScale(screenWidth, screenHeight)
        return layoutDialogueText(instruction, historyPaint, scale)
            .lines
            .joinToString("\n")
            .trim()
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
        if (instruction.shouldRenderOriginalText()) {
            renderBilingualDialogueBox(canvas, paint, instruction)
            return
        }

        val scale = screenScale(canvas)
        val textColor = instruction.textColor ?: FGO_TEXT_COLOR
        paint.color = textColor
        val layout = layoutDialogueText(instruction, paint, scale)
        val clearBox = layout.clearBox

        canvas.drawRoundRect(
            clearBox.left, clearBox.top,
            clearBox.right, clearBox.bottom,
            12f, 12f, dialogueClearPaint
        )

        val textArea = layout.textArea
        val firstBaseline = textArea.top - paint.fontMetrics.ascent

        canvas.save()
        canvas.clipRect(textArea)
        drawLines(
            canvas = canvas,
            paint = paint,
            lines = layout.lines,
            x = textArea.left,
            firstBaseline = firstBaseline,
            lineHeight = layout.lineHeight,
            textColor = textColor
        )
        canvas.restore()
    }

    private data class DialogueTextLayout(
        val clearBox: RectF,
        val textArea: RectF,
        val lines: List<String>,
        val lineHeight: Float
    )

    private data class BilingualDialogueTextLayout(
        val clearBox: RectF,
        val textArea: RectF,
        val translationLines: List<String>,
        val originalLines: List<String>,
        val translationLineHeight: Float,
        val originalLineHeight: Float,
        val pairGap: Float,
        val translationTextSize: Float,
        val originalTextSize: Float
    )

    private data class BilingualSingleLineLayout(
        val translationText: String,
        val originalText: String,
        val translationLineHeight: Float,
        val originalLineHeight: Float,
        val translationTextSize: Float,
        val originalTextSize: Float
    )

    private data class BilingualLinePairFit(
        val translationLines: List<String>,
        val originalLines: List<String>,
        val translationLineHeight: Float,
        val originalLineHeight: Float,
        val pairGap: Float,
        val translationTextSize: Float,
        val originalTextSize: Float
    )

    private data class DialogueRenderCandidates(
        val preferred: List<String>,
        val fallback: List<String>
    ) {
        val all: List<String>
            get() = preferred + fallback
    }

    private fun renderBilingualDialogueBox(
        canvas: Canvas,
        paint: Paint,
        instruction: RenderInstruction
    ) {
        val scale = screenScale(canvas)
        val textColor = instruction.textColor ?: FGO_TEXT_COLOR
        val layout = layoutBilingualDialogueText(instruction, paint, scale)

        canvas.drawRoundRect(
            layout.clearBox.left, layout.clearBox.top,
            layout.clearBox.right, layout.clearBox.bottom,
            12f, 12f, dialogueClearPaint
        )

        canvas.save()
        canvas.clipRect(layout.textArea)
        drawBilingualLinePairs(
            canvas = canvas,
            paint = paint,
            translationLines = layout.translationLines,
            originalLines = layout.originalLines,
            x = layout.textArea.left,
            top = layout.textArea.top,
            translationTextSize = layout.translationTextSize,
            originalTextSize = layout.originalTextSize,
            translationLineHeight = layout.translationLineHeight,
            originalLineHeight = layout.originalLineHeight,
            pairGap = layout.pairGap,
            translationColor = textColor
        )
        canvas.restore()
    }

    private fun layoutBilingualDialogueText(
        instruction: RenderInstruction,
        paint: Paint,
        scale: Float
    ): BilingualDialogueTextLayout {
        val panelBox = RectF(instruction.region.boundingBox)
        val textArea = fixedBilingualDialogueTextArea(panelBox, scale)
        val fit = fitBilingualLinePairs(
            instruction = instruction,
            paint = paint,
            scale = scale,
            maxWidth = textArea.width(),
            maxHeight = textArea.height()
        )
        val clearBox = bilingualDialogueClearBoxForLayout(
            instruction = instruction,
            panelBox = panelBox,
            textArea = textArea,
            translationLines = fit.translationLines,
            originalLines = fit.originalLines,
            translationLineHeight = fit.translationLineHeight,
            originalLineHeight = fit.originalLineHeight,
            pairGap = fit.pairGap,
            translationTextSize = fit.translationTextSize,
            originalTextSize = fit.originalTextSize,
            paint = paint,
            scale = scale
        )

        return BilingualDialogueTextLayout(
            clearBox = clearBox,
            textArea = textArea,
            translationLines = fit.translationLines,
            originalLines = fit.originalLines,
            translationLineHeight = fit.translationLineHeight,
            originalLineHeight = fit.originalLineHeight,
            pairGap = fit.pairGap,
            translationTextSize = fit.translationTextSize,
            originalTextSize = fit.originalTextSize
        )
    }

    private fun layoutDialogueText(
        instruction: RenderInstruction,
        paint: Paint,
        scale: Float
    ): DialogueTextLayout {
        val panelBox = RectF(instruction.region.boundingBox)
        val textArea = fixedDialogueTextArea(panelBox, scale)
        val candidates = instruction.dialogueRenderCandidates()
        val preferredTextSize = 53f * scale
        val fittedAtPreferredSize = listOf(candidates.preferred, candidates.fallback)
            .asSequence()
            .filter { group -> group.any { it.isNotBlank() } }
            .mapNotNull { candidateGroup ->
                fitDialogueTextAtSizeOrNull(
                    candidates = candidateGroup,
                    paint = paint,
                    textSize = preferredTextSize,
                    maxWidth = textArea.width(),
                    maxHeight = textArea.height(),
                    maxLines = DIALOGUE_MAX_LINES
                )
            }
            .firstOrNull()

        val (lines, lineHeight) = fittedAtPreferredSize ?: fitDialogueText(
            candidates = candidates.all,
            paint = paint,
            initialTextSize = preferredTextSize,
            preferredMinimumTextSize = DIALOGUE_MIN_TEXT_SIZE * scale,
            emergencyMinimumTextSize = DIALOGUE_EMERGENCY_MIN_TEXT_SIZE * scale,
            maxWidth = textArea.width(),
            maxHeight = textArea.height(),
            maxLines = DIALOGUE_MAX_LINES
        )
        val clearBox = dialogueClearBoxForLayout(
            instruction = instruction,
            panelBox = panelBox,
            textArea = textArea,
            lines = lines,
            lineHeight = lineHeight,
            paint = paint,
            scale = scale
        )
        return DialogueTextLayout(clearBox, textArea, lines, lineHeight)
    }

    private fun dialogueClearBoxForLayout(
        instruction: RenderInstruction,
        panelBox: RectF,
        textArea: RectF,
        lines: List<String>,
        lineHeight: Float,
        paint: Paint,
        scale: Float
    ): RectF {
        val originalBounds = originalTextBounds(instruction)?.let(::RectF)
        val clearInsetX = DYNAMIC_DIALOGUE_TEXT_HORIZONTAL_INSET * scale
        val clearLeftInsetX = DYNAMIC_DIALOGUE_TEXT_LEFT_INSET * scale
        val clearInsetY = DYNAMIC_DIALOGUE_TEXT_VERTICAL_INSET * scale
        val sourcePaddingX = DYNAMIC_DIALOGUE_HORIZONTAL_PADDING * scale
        val sourceLeftPaddingX = DYNAMIC_DIALOGUE_LEFT_PADDING * scale
        val sourcePaddingY = DYNAMIC_DIALOGUE_VERTICAL_PADDING * scale

        val textWidth = lines.maxOfOrNull { paint.measureText(it) } ?: 0f
        val textBottom = textArea.top + lineHeight * lines.size.coerceAtLeast(1)
        val sourceLeft = originalBounds?.left?.minus(sourceLeftPaddingX) ?: textArea.left
        val sourceTop = originalBounds?.top?.minus(sourcePaddingY) ?: textArea.top
        val sourceRight = originalBounds?.right?.plus(sourcePaddingX) ?: textArea.left
        val sourceBottom = originalBounds?.bottom?.plus(sourcePaddingY) ?: textArea.top
        val clearRightForRiskyTail = if (instruction.hasRiskyTrailingDialogueText()) {
            textArea.right
        } else {
            textArea.left
        }

        return boundedRect(
            left = minOf(sourceLeft, textArea.left - clearLeftInsetX),
            top = minOf(sourceTop, textArea.top - clearInsetY) - 12f * scale,
            right = maxOf(sourceRight, textArea.left + textWidth + clearInsetX, clearRightForRiskyTail),
            bottom = maxOf(sourceBottom, textBottom + clearInsetY),
            bounds = panelBox
        )
    }

    private fun bilingualDialogueClearBoxForLayout(
        instruction: RenderInstruction,
        panelBox: RectF,
        textArea: RectF,
        translationLines: List<String>,
        originalLines: List<String>,
        translationLineHeight: Float,
        originalLineHeight: Float,
        pairGap: Float,
        translationTextSize: Float,
        originalTextSize: Float,
        paint: Paint,
        scale: Float
    ): RectF {
        val originalBounds = originalTextBounds(instruction)?.let(::RectF)
        val clearInsetX = DYNAMIC_DIALOGUE_TEXT_HORIZONTAL_INSET * scale
        val clearLeftInsetX = DYNAMIC_DIALOGUE_TEXT_LEFT_INSET * scale
        val clearInsetY = DYNAMIC_DIALOGUE_TEXT_VERTICAL_INSET * scale
        val sourcePaddingX = DYNAMIC_DIALOGUE_HORIZONTAL_PADDING * scale
        val sourceLeftPaddingX = DYNAMIC_DIALOGUE_LEFT_PADDING * scale
        val sourcePaddingY = DYNAMIC_DIALOGUE_VERTICAL_PADDING * scale
        val translationWidth = measureMaxLineWidth(paint, translationTextSize, translationLines)
        val originalWidth = measureMaxLineWidth(paint, originalTextSize, originalLines)
        val textBottom = textArea.top + bilingualLinePairsHeight(
            translationLines = translationLines,
            originalLines = originalLines,
            translationLineHeight = translationLineHeight,
            originalLineHeight = originalLineHeight,
            pairGap = pairGap
        )
        val sourceLeft = originalBounds?.left?.minus(sourceLeftPaddingX) ?: textArea.left
        val sourceTop = originalBounds?.top?.minus(sourcePaddingY) ?: textArea.top
        val sourceRight = originalBounds?.right?.plus(sourcePaddingX) ?: textArea.left
        val sourceBottom = originalBounds?.bottom?.plus(sourcePaddingY) ?: textArea.top

        return boundedRect(
            left = minOf(sourceLeft, textArea.left - clearLeftInsetX),
            top = minOf(sourceTop, textArea.top - clearInsetY) - 12f * scale,
            right = maxOf(
                sourceRight,
                textArea.left + translationWidth + clearInsetX,
                textArea.left + originalWidth + clearInsetX
            ),
            bottom = maxOf(sourceBottom, textBottom + clearInsetY),
            bounds = panelBox
        )
    }

    private fun measureMaxLineWidth(
        paint: Paint,
        textSize: Float,
        lines: List<String>
    ): Float {
        val previousTextSize = paint.textSize
        paint.textSize = textSize
        val width = lines.maxOfOrNull { paint.measureText(it) } ?: 0f
        paint.textSize = previousTextSize
        return width
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
        return RectF(
            safeLeft,
            safeTop,
            safeRight,
            safeBottom
        )
    }

    private fun fixedDialogueTextArea(panelBox: RectF, scale: Float): RectF {
        return RectF(
            panelBox.left + DIALOGUE_TEXT_LEFT_INSET * scale,
            panelBox.top + DIALOGUE_TEXT_TOP_INSET * scale,
            panelBox.right - DIALOGUE_TEXT_RIGHT_INSET * scale,
            panelBox.bottom - DIALOGUE_TEXT_BOTTOM_INSET * scale
        )
    }

    private fun fixedBilingualDialogueTextArea(panelBox: RectF, scale: Float): RectF {
        return RectF(
            panelBox.left + DIALOGUE_TEXT_LEFT_INSET * scale,
            panelBox.top + BILINGUAL_DIALOGUE_TEXT_TOP_INSET * scale,
            panelBox.right - DIALOGUE_TEXT_RIGHT_INSET * scale,
            panelBox.bottom - BILINGUAL_DIALOGUE_TEXT_BOTTOM_INSET * scale
        )
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

        paint.apply {
            color = instruction.textColor ?: FGO_TEXT_COLOR
            textSize = NAME_TEXT_SIZE * scale
        }

        val originalNameBounds = originalTextBounds(instruction)
        val originalNameRight = originalNameBounds
            ?.right
            ?.toFloat()
            ?.plus(30f * scale)
            ?: box.left.toFloat()
        val requiredWidth = NAME_TEXT_LEFT_INSET * scale + paint.measureText(name) + 28f * scale
        val renderedRight = maxOf(
            box.left + MIN_NAME_PLATE_WIDTH * scale,
            box.left + requiredWidth,
            originalNameRight + 10f * scale
        )
            .coerceAtMost(canvas.width.toFloat())

        canvas.drawRoundRect(
            box.left + 42f * scale, box.top + 8f * scale,
            renderedRight - 10f * scale, box.bottom - 8f * scale,
            8f, 8f, nameClearPaint
        )

        // FGO draws the speaker name inset from the arrow-shaped leading edge.
        val textArea = RectF(
            box.left + NAME_TEXT_LEFT_INSET * scale,
            box.top + NAME_TEXT_TOP_INSET * scale,
            renderedRight - NAME_TEXT_RIGHT_INSET * scale,
            box.bottom - NAME_TEXT_BOTTOM_INSET * scale
        )
        val fittedName = fitSingleLine(
            text = instruction.translatedText.trim(),
            paint = paint,
            initialTextSize = NAME_TEXT_SIZE * scale,
            minimumTextSize = NAME_TEXT_MIN_SIZE * scale,
            maxWidth = textArea.width()
        )

        canvas.save()
        canvas.clipRect(textArea)
        drawLines(
            canvas = canvas,
            paint = paint,
            lines = listOf(fittedName),
            x = textArea.left,
            firstBaseline = textArea.top - paint.fontMetrics.ascent + NAME_TEXT_BASELINE_OFFSET * scale,
            lineHeight = paint.textSize,
            textColor = instruction.textColor ?: FGO_TEXT_COLOR
        )
        canvas.restore()
    }

    private fun originalTextBounds(instruction: RenderInstruction): Rect? {
        val sourceLines = instruction.region.lines.filter {
            it.text.isNotBlank() && it.boundingBox.width() > 0 && it.boundingBox.height() > 0
        }
        if (sourceLines.isEmpty()) return null

        val boundsLines = if (instruction.region.region == TextRegion.DIALOGUE_BOX) {
            dialogueSourceBoundsLines(sourceLines)
        } else {
            sourceLines
        }
        return unionTextBounds(boundsLines.ifEmpty { sourceLines })
    }

    private fun dialogueSourceBoundsLines(lines: List<OcrTextLine>): List<OcrTextLine> {
        if (lines.size < 2) return lines

        val sorted = lines.sortedWith(compareBy({ it.boundingBox.top }, { it.boundingBox.left }))
        val heights = sorted.map { it.boundingBox.height().coerceAtLeast(1) }.sorted()
        val heightReference = heights[(heights.size * 3 / 4).coerceAtMost(heights.lastIndex)]
        val rubyCandidates = sorted
            .filter { isDialogueRubyBoundsCandidate(it, heightReference) }
            .toSet()
        if (rubyCandidates.isEmpty()) return sorted

        val mainCandidates = sorted.filterNot { it in rubyCandidates }
        if (mainCandidates.isEmpty()) return sorted

        val rubyLines = rubyCandidates.filter { ruby ->
            mainCandidates.any { main -> isDialogueRubyAboveMain(ruby, main, heightReference) }
        }.toSet()

        return sorted.filterNot { it in rubyLines }
    }

    private fun isDialogueRubyBoundsCandidate(line: OcrTextLine, heightReference: Int): Boolean {
        val text = line.text.trim()
        if (text.length !in 1..SOURCE_BOUNDS_RUBY_MAX_CHARS) return false
        if (text.none { it.isLetterOrDigit() || it.isJapaneseOrCjkTextChar() }) return false

        val height = line.boundingBox.height().coerceAtLeast(1)
        return height <= heightReference * SOURCE_BOUNDS_RUBY_HEIGHT_RATIO
    }

    private fun isDialogueRubyAboveMain(
        ruby: OcrTextLine,
        main: OcrTextLine,
        heightReference: Int
    ): Boolean {
        if (main.boundingBox.top < ruby.boundingBox.bottom - heightReference / 3) return false
        if (main.boundingBox.top - ruby.boundingBox.bottom > heightReference) return false
        return horizontalOverlap(ruby.boundingBox, main.boundingBox) >= ruby.boundingBox.width() / 4 ||
                ruby.boundingBox.centerX() in main.boundingBox.left..main.boundingBox.right
    }

    private fun unionTextBounds(lines: List<OcrTextLine>): Rect {
        val bounds = Rect(lines.first().boundingBox)
        lines.drop(1).forEach { line ->
            bounds.union(line.boundingBox)
        }
        return bounds
    }

    private fun horizontalOverlap(first: Rect, second: Rect): Int {
        return (minOf(first.right, second.right) - maxOf(first.left, second.left)).coerceAtLeast(0)
    }

    private fun Char.isJapaneseOrCjkTextChar(): Boolean {
        return this in '\u3040'..'\u30ff' ||
                this in '\u3400'..'\u9fff' ||
                this == '\u3005'
    }

    private fun RenderInstruction.hasRiskyTrailingDialogueText(): Boolean {
        val sourceTail = region.lines
            .map { it.text.trim() }
            .lastOrNull { it.isNotBlank() }
            ?: return false
        return TRAILING_DASH_CLEAR_RISK.containsMatchIn(sourceTail) ||
            COUNTDOWN_CLEAR_RISK.containsMatchIn(sourceTail)
    }

    private fun renderBilingualChoiceButton(
        canvas: Canvas,
        paint: Paint,
        instruction: RenderInstruction
    ) {
        val scale = screenScale(canvas)
        val box = fixedChoiceRenderBox(instruction.region.boundingBox, canvas, scale) ?: run {
            FgoLogger.debug(
                tag,
                "Skipping bilingual choice render with unexpected bounds: ${instruction.region.boundingBox.flattenToString()}"
            )
            return
        }
        val clearInsetX = 47f * scale
        val textInsetX = 70f * scale

        canvas.drawRoundRect(
            box.left + clearInsetX, box.top + 8f * scale,
            box.right - clearInsetX, box.bottom - 8f * scale,
            10f, 10f, choiceClearPaint
        )

        val textArea = RectF(
            box.left + textInsetX,
            box.top + 12f * scale,
            box.right - textInsetX,
            box.bottom - 12f * scale
        )
        val textColor = instruction.textColor ?: FGO_TEXT_COLOR
        val layout = fitBilingualSingleLine(
            translationText = instruction.toChoiceRenderText(),
            originalText = instruction.originalSingleLineText(),
            paint = paint,
            scale = scale,
            initialTranslationTextSize = BILINGUAL_CHOICE_TEXT_SIZE * scale,
            minimumTranslationTextSize = BILINGUAL_CHOICE_MIN_TEXT_SIZE * scale,
            translationLineHeightMultiplier = BILINGUAL_CHOICE_LINE_HEIGHT_MULTIPLIER,
            maxWidth = textArea.width(),
            maxHeight = textArea.height()
        )

        val totalHeight = layout.translationLineHeight + layout.originalLineHeight
        var currentTop = textArea.centerY() - totalHeight / 2f + 8f * scale

        canvas.save()
        canvas.clipRect(textArea)
        paint.textSize = layout.translationTextSize
        drawShadowedText(
            canvas = canvas,
            paint = paint,
            text = layout.translationText,
            x = textArea.centerX() - paint.measureText(layout.translationText) / 2f,
            y = currentTop - paint.fontMetrics.ascent,
            textColor = textColor,
            scale = scale
        )
        currentTop += layout.translationLineHeight

        paint.textSize = layout.originalTextSize
        drawShadowedText(
            canvas = canvas,
            paint = paint,
            text = layout.originalText,
            x = textArea.centerX() - paint.measureText(layout.originalText) / 2f,
            y = currentTop - paint.fontMetrics.ascent,
            textColor = ORIGINAL_TEXT_COLOR,
            scale = scale
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
        if (instruction.shouldRenderOriginalText()) {
            renderBilingualChoiceButton(canvas, paint, instruction)
            return
        }

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
        val text = fitSingleLine(
            text = instruction.toChoiceRenderText(),
            paint = paint,
            initialTextSize = CHOICE_TEXT_SIZE * scale,
            minimumTextSize = CHOICE_TEXT_MIN_SIZE * scale,
            maxWidth = textArea.width()
        )

        // Center the text horizontally within the button bounding box
        val textWidth = paint.measureText(text)
        val x = box.centerX() - textWidth / 2f
        val y = box.centerY() + paint.textSize / 3f + 4f * scale

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
        return fixedChoiceRenderBox(rawBox, canvas.width, canvas.height, scale)
    }

    private fun fixedChoiceRenderBox(
        rawBox: Rect,
        canvasWidth: Int,
        canvasHeight: Int,
        scale: Float
    ): RectF? {
        val expectedWidth = CHOICE_REFERENCE_WIDTH * scale
        val rawWidth = rawBox.width().toFloat()
        val rawHeight = rawBox.height().toFloat()
        if (rawWidth !in expectedWidth * CHOICE_MIN_WIDTH_RATIO..expectedWidth * CHOICE_MAX_WIDTH_RATIO ||
            rawHeight <= 0f
        ) {
            return null
        }

        nearestChoiceSlot(rawBox, canvasWidth, canvasHeight)?.let { fixedSlot ->
            return RectF(
                fixedSlot.left.toFloat(),
                fixedSlot.top.toFloat(),
                fixedSlot.right.toFloat(),
                fixedSlot.bottom.toFloat()
            )
        }

        val expectedHeight = CHOICE_REFERENCE_HEIGHT * scale
        val left = (rawBox.centerX() - expectedWidth / 2f)
            .coerceIn(0f, (canvasWidth - expectedWidth).coerceAtLeast(0f))
        val top = rawBox.top.toFloat()
            .coerceIn(0f, (canvasHeight - expectedHeight).coerceAtLeast(0f))
        return RectF(
            left,
            top,
            left + expectedWidth,
            top + expectedHeight
        ).takeIf { it.width() > 0f && it.height() > 0f }
    }

    private fun nearestChoiceSlot(rawBox: Rect, canvasWidth: Int, canvasHeight: Int): Rect? {
        val screenRegions = FgoViewportLayout.regionsForScreen(canvasWidth, canvasHeight)
        val rawCenterY = rawBox.centerY()
        return screenRegions.choiceSlotLayouts
            .flatten()
            .mapNotNull { slot ->
                val horizontalOverlap = overlapRatio(rawBox.left, rawBox.right, slot.left, slot.right)
                if (horizontalOverlap < CHOICE_MIN_WIDTH_RATIO) return@mapNotNull null
                val verticalOverlap = overlapRatio(rawBox.top, rawBox.bottom, slot.top, slot.bottom)
                val centerDistance = kotlin.math.abs(rawCenterY - slot.centerY())
                if (verticalOverlap < 0.20f && centerDistance > slot.height()) return@mapNotNull null
                val score = verticalOverlap * 1000f - centerDistance.toFloat() / slot.height().coerceAtLeast(1)
                ChoiceSlotRenderMatch(slot, score)
            }
            .maxByOrNull { it.score }
            ?.slot
    }

    private data class ChoiceSlotRenderMatch(
        val slot: Rect,
        val score: Float
    )

    private fun overlapRatio(firstStart: Int, firstEnd: Int, secondStart: Int, secondEnd: Int): Float {
        val overlap = minOf(firstEnd, secondEnd) - maxOf(firstStart, secondStart)
        if (overlap <= 0) return 0f
        return overlap.toFloat() / minOf(firstEnd - firstStart, secondEnd - secondStart).coerceAtLeast(1)
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

    private fun fitDialogueText(
        candidates: List<String>,
        paint: Paint,
        initialTextSize: Float,
        preferredMinimumTextSize: Float,
        emergencyMinimumTextSize: Float,
        maxWidth: Float,
        maxHeight: Float,
        maxLines: Int
    ): Pair<List<String>, Float> {
        val distinctCandidates = distinctDialogueCandidates(candidates)

        listOf(preferredMinimumTextSize, emergencyMinimumTextSize).forEach { minimumTextSize ->
            distinctCandidates.forEach { candidate ->
                fitWrappedTextOrNull(
                    text = candidate,
                    paint = paint,
                    initialTextSize = initialTextSize,
                    minimumTextSize = minimumTextSize,
                    maxWidth = maxWidth,
                    maxHeight = maxHeight,
                    maxLines = maxLines,
                    lineHeightMultiplier = DIALOGUE_LINE_HEIGHT_MULTIPLIER
                )?.let { return it }
            }
        }

        val fallbackText = distinctCandidates.last()
        paint.textSize = emergencyMinimumTextSize
        val lineHeight = emergencyMinimumTextSize * DIALOGUE_LINE_HEIGHT_MULTIPLIER
        val heightLimitedLines = (maxHeight / lineHeight).toInt().coerceAtLeast(1)
        val maximumLines = minOf(heightLimitedLines, maxLines.coerceAtLeast(1))
        FgoLogger.debug(tag, "Dialogue still over 2 lines at emergency size; ellipsizing as final fallback")
        return limitLines(wrapText(fallbackText, paint, maxWidth), maximumLines, paint, maxWidth) to lineHeight
    }

    private fun fitOriginalText(
        candidates: List<String>,
        paint: Paint,
        initialTextSize: Float,
        minimumTextSize: Float,
        maxWidth: Float,
        maxHeight: Float,
        maxLines: Int
    ): Pair<List<String>, Float> {
        val distinctCandidates = candidates
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .ifEmpty { listOf("") }

        distinctCandidates.forEach { candidate ->
            fitWrappedTextOrNull(
                text = candidate,
                paint = paint,
                initialTextSize = initialTextSize,
                minimumTextSize = minimumTextSize,
                maxWidth = maxWidth,
                maxHeight = maxHeight,
                maxLines = maxLines,
                lineHeightMultiplier = ORIGINAL_LINE_HEIGHT_MULTIPLIER
            )?.let { return it }
        }

        val fallbackText = distinctCandidates.last()
        paint.textSize = minimumTextSize
        val lineHeight = minimumTextSize * ORIGINAL_LINE_HEIGHT_MULTIPLIER
        val heightLimitedLines = (maxHeight / lineHeight).toInt().coerceAtLeast(1)
        val maximumLines = minOf(heightLimitedLines, maxLines.coerceAtLeast(1))
        return limitLines(wrapText(fallbackText, paint, maxWidth), maximumLines, paint, maxWidth) to lineHeight
    }

    private fun fitBilingualLinePairs(
        instruction: RenderInstruction,
        paint: Paint,
        scale: Float,
        maxWidth: Float,
        maxHeight: Float
    ): BilingualLinePairFit {
        val translationCandidates = distinctDialogueCandidates(instruction.dialogueRenderCandidates().all)
        val originalCandidates = distinctOriginalCandidates(instruction.originalRenderCandidates())
        val minimumTranslationSize = BILINGUAL_TRANSLATION_MIN_TEXT_SIZE * scale
        val pairGap = BILINGUAL_PAIR_GAP * scale
        var translationTextSize = BILINGUAL_TRANSLATION_TEXT_SIZE * scale

        while (true) {
            val originalTextSize = originalTextSizeFor(translationTextSize, scale)
            val translationLineHeight = translationTextSize * BILINGUAL_TRANSLATION_LINE_HEIGHT_MULTIPLIER
            val originalLineHeight = originalTextSize * ORIGINAL_LINE_HEIGHT_MULTIPLIER

            for (translationCandidate in translationCandidates) {
                paint.textSize = translationTextSize
                val translationLines = wrapText(translationCandidate, paint, maxWidth)
                if (translationLines.size > DIALOGUE_MAX_LINES) continue

                for (originalCandidate in originalCandidates) {
                    paint.textSize = originalTextSize
                    val originalLines = wrapText(originalCandidate, paint, maxWidth)
                    if (originalLines.size > DIALOGUE_MAX_LINES) continue

                    val totalHeight = bilingualLinePairsHeight(
                        translationLines = translationLines,
                        originalLines = originalLines,
                        translationLineHeight = translationLineHeight,
                        originalLineHeight = originalLineHeight,
                        pairGap = pairGap
                    )
                    if (totalHeight <= maxHeight) {
                        paint.textSize = originalTextSize
                        return BilingualLinePairFit(
                            translationLines = translationLines,
                            originalLines = originalLines,
                            translationLineHeight = translationLineHeight,
                            originalLineHeight = originalLineHeight,
                            pairGap = pairGap,
                            translationTextSize = translationTextSize,
                            originalTextSize = originalTextSize
                        )
                    }
                }
            }

            if (translationTextSize <= minimumTranslationSize) break
            translationTextSize = (translationTextSize - 2f).coerceAtLeast(minimumTranslationSize)
        }

        val fallbackTranslationSize = minimumTranslationSize
        val fallbackOriginalSize = originalTextSizeFor(fallbackTranslationSize, scale)
        val translationLineHeight = fallbackTranslationSize * BILINGUAL_TRANSLATION_LINE_HEIGHT_MULTIPLIER
        val originalLineHeight = fallbackOriginalSize * ORIGINAL_LINE_HEIGHT_MULTIPLIER
        paint.textSize = fallbackTranslationSize
        val translationLines = limitLines(
            wrapText(translationCandidates.last(), paint, maxWidth),
            DIALOGUE_MAX_LINES,
            paint,
            maxWidth
        )
        paint.textSize = fallbackOriginalSize
        val originalLines = limitLines(
            wrapText(originalCandidates.last(), paint, maxWidth),
            DIALOGUE_MAX_LINES,
            paint,
            maxWidth
        )
        return BilingualLinePairFit(
            translationLines = translationLines,
            originalLines = originalLines,
            translationLineHeight = translationLineHeight,
            originalLineHeight = originalLineHeight,
            pairGap = pairGap,
            translationTextSize = fallbackTranslationSize,
            originalTextSize = fallbackOriginalSize
        )
    }

    private fun fitBilingualSingleLine(
        translationText: String,
        originalText: String,
        paint: Paint,
        scale: Float,
        initialTranslationTextSize: Float,
        minimumTranslationTextSize: Float,
        translationLineHeightMultiplier: Float,
        maxWidth: Float,
        maxHeight: Float
    ): BilingualSingleLineLayout {
        val cleanTranslationText = translationText.trim()
        val cleanOriginalText = originalText.trim()
        var translationTextSize = initialTranslationTextSize

        while (true) {
            val originalTextSize = originalTextSizeFor(translationTextSize, scale)
            val translationLineHeight = translationTextSize * translationLineHeightMultiplier
            val originalLineHeight = originalTextSize * ORIGINAL_LINE_HEIGHT_MULTIPLIER

            paint.textSize = translationTextSize
            val translationFits = paint.measureText(cleanTranslationText) <= maxWidth
            paint.textSize = originalTextSize
            val originalFits = paint.measureText(cleanOriginalText) <= maxWidth

            if (translationFits && originalFits && translationLineHeight + originalLineHeight <= maxHeight) {
                return BilingualSingleLineLayout(
                    translationText = cleanTranslationText,
                    originalText = cleanOriginalText,
                    translationLineHeight = translationLineHeight,
                    originalLineHeight = originalLineHeight,
                    translationTextSize = translationTextSize,
                    originalTextSize = originalTextSize
                )
            }

            if (translationTextSize <= minimumTranslationTextSize) break
            translationTextSize = (translationTextSize - 2f).coerceAtLeast(minimumTranslationTextSize)
        }

        val fallbackOriginalTextSize = originalTextSizeFor(minimumTranslationTextSize, scale)
        paint.textSize = minimumTranslationTextSize
        val fittedTranslation = ellipsize(cleanTranslationText, paint, maxWidth)
        paint.textSize = fallbackOriginalTextSize
        val fittedOriginal = ellipsize(cleanOriginalText, paint, maxWidth)
        return BilingualSingleLineLayout(
            translationText = fittedTranslation,
            originalText = fittedOriginal,
            translationLineHeight = minimumTranslationTextSize * translationLineHeightMultiplier,
            originalLineHeight = fallbackOriginalTextSize * ORIGINAL_LINE_HEIGHT_MULTIPLIER,
            translationTextSize = minimumTranslationTextSize,
            originalTextSize = fallbackOriginalTextSize
        )
    }

    private fun fitDialogueTextAtSizeOrNull(
        candidates: List<String>,
        paint: Paint,
        textSize: Float,
        maxWidth: Float,
        maxHeight: Float,
        maxLines: Int
    ): Pair<List<String>, Float>? {
        val distinctCandidates = distinctDialogueCandidates(candidates)
        val lineHeight = textSize * DIALOGUE_LINE_HEIGHT_MULTIPLIER
        val heightLimitedLines = (maxHeight / lineHeight).toInt().coerceAtLeast(1)
        val maximumLines = minOf(heightLimitedLines, maxLines.coerceAtLeast(1))
        paint.textSize = textSize
        distinctCandidates.forEach { candidate ->
            val lines = wrapText(candidate, paint, maxWidth)
            if (lines.size <= maximumLines) {
                return lines to lineHeight
            }
        }
        return null
    }

    private fun distinctDialogueCandidates(candidates: List<String>): List<String> {
        return candidates
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .ifEmpty { listOf("") }
    }

    private fun distinctOriginalCandidates(candidates: List<String>): List<String> {
        return candidates
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .ifEmpty { listOf("") }
    }

    private fun originalTextSizeFor(translationTextSize: Float, scale: Float): Float {
        return (translationTextSize * ORIGINAL_TEXT_SIZE_RATIO)
            .coerceAtMost(BILINGUAL_ORIGINAL_MAX_TEXT_SIZE * scale)
            .coerceAtLeast(BILINGUAL_ORIGINAL_MIN_TEXT_SIZE * scale)
    }

    private fun bilingualLinePairsHeight(
        translationLines: List<String>,
        originalLines: List<String>,
        translationLineHeight: Float,
        originalLineHeight: Float,
        pairGap: Float
    ): Float {
        val pairCount = maxOf(translationLines.size, originalLines.size)
        if (pairCount == 0) return 0f

        var height = 0f
        repeat(pairCount) { index ->
            if (index < translationLines.size) height += translationLineHeight
            if (index < originalLines.size) height += originalLineHeight
            if (index < pairCount - 1) height += pairGap
        }
        return height
    }

    private fun fitWrappedTextOrNull(
        text: String,
        paint: Paint,
        initialTextSize: Float,
        minimumTextSize: Float,
        maxWidth: Float,
        maxHeight: Float,
        maxLines: Int,
        lineHeightMultiplier: Float
    ): Pair<List<String>, Float>? {
        var textSize = initialTextSize
        val allowedLines = maxLines.coerceAtLeast(1)
        while (true) {
            paint.textSize = textSize
            val lineHeight = textSize * lineHeightMultiplier
            val heightLimitedLines = (maxHeight / lineHeight).toInt().coerceAtLeast(1)
            val maximumLines = minOf(heightLimitedLines, allowedLines)
            val lines = wrapText(text, paint, maxWidth)
            if (lines.size <= maximumLines) {
                return lines to lineHeight
            }
            if (textSize <= minimumTextSize) {
                return null
            }
            textSize = (textSize - 2f).coerceAtLeast(minimumTextSize)
        }
    }

    private fun RenderInstruction.dialogueRenderCandidates(): DialogueRenderCandidates {
        val normalizedLines = translatedText.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .map { it.normalizeDialogueSymbolsAndSpacing() }
        val mergedLines = mergeDialoguePrefixLines(normalizedLines)
        val linePreserved = mergedLines.joinToString("\n").trim()
        val flattened = mergedLines.joinToString(WIDE_RENDER_SPACE) { it.trim() }
            .replace(Regex("[ \\t]+"), " ")
            .trim()
        val compact = flattened
            .replace(Regex("[\\s$WIDE_RENDER_SPACE]+"), "")
            .trim()

        val preferred = buildList {
            if (wideTextSpacing) add(linePreserved.toFgoWideRenderText())
            add(linePreserved)
        }
        val fallback = buildList {
            add(flattened)
            if (wideTextSpacing) add(flattened.toFgoWideRenderText())
            add(compact)
        }
        return DialogueRenderCandidates(preferred, fallback)
    }

    private fun RenderInstruction.shouldRenderOriginalText(): Boolean {
        return showOriginalText &&
                region.region in setOf(TextRegion.DIALOGUE_BOX, TextRegion.CHOICE_BUTTON) &&
                sourceText.trim().isNotBlank()
    }

    private fun RenderInstruction.originalRenderCandidates(): List<String> {
        val normalizedLines = sourceText
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .lines()
            .map { it.replace(Regex("[ \\t]+"), " ").trim() }
            .filter { it.isNotBlank() }
        val linePreserved = normalizedLines.joinToString("\n").trim()
        val flattened = normalizedLines.joinToString(WIDE_RENDER_SPACE)
            .replace(Regex("[ \\t]+"), " ")
            .trim()
        return listOf(linePreserved, flattened)
            .filter { it.isNotBlank() }
            .distinct()
            .ifEmpty { listOf(sourceText.trim()) }
    }

    private fun RenderInstruction.originalSingleLineText(): String {
        return originalRenderCandidates()
            .firstOrNull { '\n' !in it }
            ?.replace(Regex("[ \\t]+"), " ")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: sourceText
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .lines()
                .map { it.replace(Regex("[ \\t]+"), " ").trim() }
                .filter { it.isNotBlank() }
                .joinToString(WIDE_RENDER_SPACE)
                .trim()
    }

    private fun RenderInstruction.toChoiceRenderText(): String {
        val normalized = translatedText.trim()
            .normalizeDialogueSymbolsAndSpacing()
            .replace(Regex("[ \\t]+"), " ")
        return if (wideTextSpacing) normalized.toFgoWideRenderText() else normalized
    }

    private fun mergeDialoguePrefixLines(lines: List<String>): List<String> {
        if (lines.isEmpty()) return emptyList()
        val merged = mutableListOf<String>()
        var pendingPrefix = ""
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isBlank()) continue
            if (trimmed.isStandaloneDialoguePrefix()) {
                pendingPrefix += trimmed.trimEnd()
                continue
            }
            val nextLine = if (pendingPrefix.isNotBlank()) {
                pendingPrefix + trimmed
            } else {
                trimmed
            }
            merged += nextLine
            pendingPrefix = ""
        }
        if (pendingPrefix.isNotBlank()) {
            merged += pendingPrefix
        }
        return merged
    }

    private fun String.normalizeDialogueSymbolsAndSpacing(): String {
        return FgoDialogueSymbols.normalizeForRender(this)
    }

    private fun String.isStandaloneDialoguePrefix(): Boolean {
        val normalized = normalizeDialogueSymbolsAndSpacing()
            .trim(' ', '\t', '\u3000')
            .trim('「', '」', '『', '』', '（', '）', '(', ')', '[', ']')
        return normalized.isNotBlank() &&
                normalized.all {
                    it in setOf('—', '－', '-', 'ー', '…', '.', '。', '、', ',', '，', ':', '：', '!', '！', '?', '？')
                }
    }

    private fun String.toFgoWideRenderText(): String {
        return lines().joinToString("\n") { line ->
            line.spaceCjkRunsForFgo()
        }
    }

    private fun String.spaceCjkRunsForFgo(): String {
        val out = StringBuilder()
        val run = StringBuilder()

        fun flushRun() {
            if (run.isNotEmpty()) {
                out.append(splitWideCjkRun(run.toString()).joinToString(WIDE_RENDER_SPACE))
                run.clear()
            }
        }

        for (char in this) {
            if (char.isCjkRenderChar()) {
                run.append(char)
            } else {
                flushRun()
                out.append(char)
            }
        }
        flushRun()
        return out.toString()
            .replace(Regex("[ \\t]+"), " ")
            .trim()
    }

    private fun splitWideCjkRun(text: String): List<String> {
        val segments = mutableListOf<String>()
        val content = StringBuilder()
        var index = 0

        fun flushContent() {
            if (content.isNotEmpty()) {
                val raw = content.toString()
                val chunkSize = if (raw.length <= 10) 5 else 4
                segments.addAll(raw.chunked(chunkSize))
                content.clear()
            }
        }

        while (index < text.length) {
            val connector = WIDE_RENDER_CONNECTORS.firstOrNull { text.startsWith(it, index) }
            if (connector != null) {
                flushContent()
                segments.add(connector)
                index += connector.length
            } else {
                content.append(text[index])
                index++
            }
        }
        flushContent()
        return segments.filter { it.isNotBlank() }
    }

    private fun Char.isCjkRenderChar(): Boolean {
        return this in '\u3400'..'\u9FFF'
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

    private fun drawShadowedText(
        canvas: Canvas,
        paint: Paint,
        text: String,
        x: Float,
        y: Float,
        textColor: Int,
        scale: Float
    ) {
        paint.setShadowLayer(2f * scale, shadowOffset * scale, shadowOffset * scale, Color.BLACK)
        paint.color = textColor
        canvas.drawText(text, x, y, paint)
        paint.clearShadowLayer()
        paint.color = textColor
        canvas.drawText(text, x, y, paint)
    }

    private fun drawBilingualLinePairs(
        canvas: Canvas,
        paint: Paint,
        translationLines: List<String>,
        originalLines: List<String>,
        x: Float,
        top: Float,
        translationTextSize: Float,
        originalTextSize: Float,
        translationLineHeight: Float,
        originalLineHeight: Float,
        pairGap: Float,
        translationColor: Int
    ) {
        var currentTop = top
        val pairCount = maxOf(translationLines.size, originalLines.size)
        repeat(pairCount) { index ->
            if (index < translationLines.size) {
                paint.textSize = translationTextSize
                drawLines(
                    canvas = canvas,
                    paint = paint,
                    lines = listOf(translationLines[index]),
                    x = x,
                    firstBaseline = currentTop - paint.fontMetrics.ascent,
                    lineHeight = translationLineHeight,
                    textColor = translationColor
                )
                currentTop += translationLineHeight
            }
            if (index < originalLines.size) {
                paint.textSize = originalTextSize
                drawLines(
                    canvas = canvas,
                    paint = paint,
                    lines = listOf(originalLines[index]),
                    x = x,
                    firstBaseline = currentTop - paint.fontMetrics.ascent,
                    lineHeight = originalLineHeight,
                    textColor = ORIGINAL_TEXT_COLOR
                )
                currentTop += originalLineHeight
            }
            if (index < pairCount - 1) {
                currentTop += pairGap
            }
        }
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
        return screenScale(canvas.width, canvas.height)
    }

    private fun screenScale(screenWidth: Int, screenHeight: Int): Float {
        return FgoViewportLayout.viewportScaleForScreen(screenWidth, screenHeight)
    }
}
