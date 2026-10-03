package com.fgogotran.ocr

import android.graphics.Bitmap
import android.graphics.Rect
import com.fgogotran.util.FgoLogger

/** One captured pixel array/component analysis for a shared crop or a single-button retry. */
internal class ChoiceOcrContext private constructor(
    val pixels: IntArray,
    val width: Int,
    val height: Int,
    val analysis: List<ChoicePunctuationRecovery.ButtonAnalysis>,
    private val maskedDots: Int
) {
    fun recognitionBitmap(original: Bitmap): Bitmap = if (maskedDots == 0) original else {
        FgoLogger.debug("OCR", "Choice emphasis dots cleaned before detection: dots=$maskedDots")
        Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    fun recover(lines: List<ChoicePunctuationRecovery.Line>): ChoicePunctuationRecovery.Result =
        ChoicePunctuationRecovery.recover(pixels, width, height, analysis.map { it.bounds }, lines, analysis)

    /** Engines without CTC positions still get verified edge/standalone punctuation, without a retry. */
    fun recover(result: OcrResult): OcrResult {
        val recovery = recover(result.lines.mapIndexed { index, line ->
            ChoicePunctuationRecovery.Line(index, line.text,
                ChoicePunctuationRecovery.Bounds(line.boundingBox.left, line.boundingBox.top,
                    line.boundingBox.right, line.boundingBox.bottom), line.confidence)
        })
        if (recovery.recoveredCount > 0 || recovery.unresolvedSourceIndices.isNotEmpty()) {
            FgoLogger.debug("OCR", "Choice punctuation: recovered=${recovery.recoveredCount}, " +
                "unresolved=${recovery.unresolvedSourceIndices.size}, positionRetries=0")
        }
        val lines = recovery.lines.map { line ->
            OcrTextLine(line.text, Rect(line.bounds.left, line.bounds.top, line.bounds.right, line.bounds.bottom), line.confidence)
        }
        return result.copy(lines = lines, fullText = lines.joinToString("\n") { it.text })
    }

    companion object {
        fun create(bitmap: Bitmap, buttons: List<Rect>): ChoiceOcrContext {
            val startedAt = System.nanoTime()
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val analysis = ChoicePunctuationRecovery.analyze(pixels, bitmap.width, bitmap.height, buttons.map {
                ChoicePunctuationRecovery.Bounds(it.left, it.top, it.right, it.bottom)
            })
            val dots = ChoicePunctuationRecovery.emphasisDots(analysis)
            dots.forEach { dot ->
                for (y in dot.bounds.top until dot.bounds.bottom) for (x in dot.bounds.left until dot.bounds.right) {
                    val index = y * bitmap.width + x
                    val pixel = pixels[index]
                    if (FgoStoryTextPalette.isChoiceInk((pixel shr 16) and 255, (pixel shr 8) and 255, pixel and 255)) {
                        pixels[index] = 0xff000000.toInt()
                    }
                }
            }
            FgoLogger.debug("OCR", "Choice pixel analysis: buttons=${analysis.size}, " +
                "components=${analysis.sumOf { it.components.size }}, emphasisDots=${dots.size}, " +
                "elapsedMs=${(System.nanoTime() - startedAt) / 1_000_000}")
            return ChoiceOcrContext(pixels, bitmap.width, bitmap.height,
                analysis.map { it.copy(components = it.components.filterNot(dots::contains)) }, dots.size)
        }
    }
}
