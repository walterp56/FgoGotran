package com.fgogotran.ocr

import android.graphics.Bitmap
import android.graphics.Rect
import com.fgogotran.util.FgoLogger
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max
import kotlin.math.roundToInt

internal enum class MlKitOcrScript(
    val displayName: String,
    val modelLabel: String,
    val engineId: OcrEngineId
) {
    JAPANESE("ML Kit Japanese OCR", "Japanese", OcrEngineId.ML_KIT),
    CHINESE("ML Kit Chinese OCR", "Chinese", OcrEngineId.ML_KIT_CHINESE)
}

internal class MlKitOcrProvider(
    private val script: MlKitOcrScript = MlKitOcrScript.JAPANESE
) : OcrProvider {
    override val preferredInputScale = OcrInputScale.X2
    private val recognizer: TextRecognizer = when (script) {
        MlKitOcrScript.CHINESE -> TextRecognition.getClient(
            ChineseTextRecognizerOptions.Builder().build()
        )
        MlKitOcrScript.JAPANESE -> TextRecognition.getClient(
            JapaneseTextRecognizerOptions.Builder().build()
        )
    }
    private val tag = "OCR"
    @Volatile
    private var warmedUp = false

    override suspend fun warmUp() {
        if (warmedUp) return
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        try {
            FgoLogger.debug(tag, "${script.displayName} warm-up starting")
            val image = InputImage.fromBitmap(bitmap, 0)
            recognizer.processSuspending(image)
            FgoLogger.info(tag, "${script.displayName} warm-up complete")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FgoLogger.warn(tag, "ML Kit OCR warm-up failed; first capture will initialize normally", e)
        } finally {
            bitmap.recycle()
            warmedUp = true
        }
    }

    override suspend fun recognize(bitmap: Bitmap): OcrResult {
        val startTime = System.currentTimeMillis()
        FgoLogger.debug(tag, "${script.displayName} starting on ${bitmap.width}x${bitmap.height}")

        val solidMaskRows = runCatching { detectSolidMaskRows(bitmap) }
            .onFailure { error ->
                FgoLogger.debug(tag, "${script.displayName} solid-mask geometry scan skipped: ${error.message}")
            }
            .getOrDefault(emptyList())
        if (solidMaskRows.isNotEmpty()) {
            FgoLogger.debug(
                tag,
                "${script.displayName} full-input solid masks: " +
                    "rows=${solidMaskRows.size}, count=${solidMaskRows.sumOf { it.masks.size }}, " +
                    "separators=${solidMaskRows.sumOf { it.separators.size }}"
            )
        }

        val image = InputImage.fromBitmap(bitmap, 0)
        val result = recognizer.processSuspending(image)
        val rawLines = result.getTextBlocks().flatMap { block -> block.getLines() }
        val (lines, fullText) = if (solidMaskRows.isEmpty()) {
            rawLines.map { line ->
                OcrTextLine(
                    text = line.getText(),
                    boundingBox = line.getBoundingBox() ?: Rect(),
                    confidence = line.getConfidence() ?: 0f
                )
            } to result.text
        } else {
            val recovery = MlKitSolidMaskRecovery.recover(
                lines = rawLines.map { line -> line.toDetectedLine() },
                maskRows = solidMaskRows
            )
            recovery.changes.forEach { change ->
                FgoLogger.debug(
                    tag,
                    "${script.displayName} split solid-mask line recovered: " +
                        "count=${change.recoveredMaskCount}, separators=${change.recoveredSeparatorCount}, " +
                        "lines=${change.associatedLineCount}, before=${change.before}, after=${change.after}"
                )
            }
            if (recovery.changes.isEmpty()) {
                FgoLogger.debug(
                    tag,
                    "${script.displayName} solid-mask geometry found but no safe line recovery was applied"
                )
            }
            val recoveredLines = recovery.lines.map { line -> line.toOcrTextLine() }
            val recoveredFullText = if (recovery.changes.isEmpty()) {
                result.text
            } else {
                recoveredLines.joinToString("\n", transform = OcrTextLine::text)
            }
            recoveredLines to recoveredFullText
        }

        val elapsed = System.currentTimeMillis() - startTime
        if (lines.isEmpty()) {
            FgoLogger.warn(tag, "${script.displayName} returned 0 text lines after ${elapsed}ms")
        } else {
            FgoLogger.info(
                tag,
                "${script.displayName} complete: ${lines.size} lines, ${fullText.length} chars, ${elapsed}ms"
            )
        }

        return OcrResult(
            lines = lines,
            fullText = fullText,
            engine = script.engineId
        )
    }

    private fun detectSolidMaskRows(bitmap: Bitmap): List<PaddleSolidMaskRow> {
        val longestSide = max(bitmap.width, bitmap.height)
        val scale = if (longestSide > MASK_DETECTION_MAX_SIDE) {
            MASK_DETECTION_MAX_SIDE.toFloat() / longestSide.toFloat()
        } else {
            1f
        }
        val sampleWidth = max(1, (bitmap.width * scale).roundToInt())
        val sampleHeight = max(1, (bitmap.height * scale).roundToInt())
        val sample = if (sampleWidth == bitmap.width && sampleHeight == bitmap.height) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, sampleWidth, sampleHeight, true)
        }

        return try {
            val pixels = IntArray(sampleWidth * sampleHeight)
            sample.getPixels(pixels, 0, sampleWidth, 0, 0, sampleWidth, sampleHeight)
            val sourceScaleX = bitmap.width.toFloat() / sampleWidth.toFloat()
            val sourceScaleY = bitmap.height.toFloat() / sampleHeight.toFloat()
            PaddleSolidMaskDetector.detectRows(pixels, sampleWidth, sampleHeight)
                .map { row -> row.scaled(sourceScaleX, sourceScaleY) }
        } finally {
            if (sample !== bitmap && !sample.isRecycled) sample.recycle()
        }
    }

    private fun Text.Line.toDetectedLine(): MlKitDetectedLine {
        val fragments = positionedFragments()
        val fallbackBounds = fragments.takeIf(List<MlKitTextFragment>::isNotEmpty)?.let { positioned ->
            Rect(
                positioned.minOf(MlKitTextFragment::left),
                positioned.minOf(MlKitTextFragment::top),
                positioned.maxOf(MlKitTextFragment::right),
                positioned.maxOf(MlKitTextFragment::bottom)
            )
        }
        val bounds = getBoundingBox() ?: fallbackBounds ?: Rect()
        return MlKitDetectedLine(
            text = getText(),
            left = bounds.left,
            top = bounds.top,
            right = bounds.right,
            bottom = bounds.bottom,
            confidence = getConfidence() ?: 0f,
            fragments = fragments
        )
    }

    private fun Text.Line.positionedFragments(): List<MlKitTextFragment> {
        val elementFragments = getElements().flatMap { element ->
            val symbolFragments = element.getSymbols().mapNotNull { symbol ->
                val bounds = symbol.getBoundingBox() ?: return@mapNotNull null
                symbol.getText().takeIf(String::isNotEmpty)?.let { symbolText ->
                    MlKitTextFragment(
                        text = symbolText,
                        left = bounds.left,
                        top = bounds.top,
                        right = bounds.right,
                        bottom = bounds.bottom,
                        confidence = symbol.getConfidence() ?: element.getConfidence() ?: 0f
                    )
                }
            }
            if (symbolFragments.isNotEmpty()) {
                symbolFragments
            } else {
                splitAcrossBounds(
                    text = element.getText(),
                    bounds = element.getBoundingBox(),
                    confidence = element.getConfidence() ?: getConfidence() ?: 0f
                )
            }
        }
        return if (elementFragments.isNotEmpty()) {
            elementFragments
        } else {
            splitAcrossBounds(getText(), getBoundingBox(), getConfidence() ?: 0f)
        }
    }

    private fun splitAcrossBounds(
        text: String,
        bounds: Rect?,
        confidence: Float
    ): List<MlKitTextFragment> {
        if (text.isEmpty() || bounds == null || bounds.width() <= 0 || bounds.height() <= 0) return emptyList()
        return text.mapIndexed { index, char ->
            val left = bounds.left + bounds.width() * index / text.length
            val right = (bounds.left + bounds.width() * (index + 1) / text.length)
                .coerceAtLeast(left + 1)
            MlKitTextFragment(
                text = char.toString(),
                left = left,
                top = bounds.top,
                right = right,
                bottom = bounds.bottom,
                confidence = confidence
            )
        }
    }

    private fun MlKitDetectedLine.toOcrTextLine(): OcrTextLine {
        return OcrTextLine(
            text = text,
            boundingBox = Rect(left, top, right, bottom),
            confidence = confidence
        )
    }

    override fun close() {
        recognizer.close()
        warmedUp = false
    }

    private suspend fun TextRecognizer.processSuspending(
        image: InputImage
    ): Text = suspendCancellableCoroutine { continuation ->
        process(image)
            .addOnSuccessListener { result ->
                if (continuation.isActive) continuation.resume(result)
            }
            .addOnFailureListener { error ->
                if (continuation.isActive) continuation.resumeWithException(error)
            }
    }

    private companion object {
        const val MASK_DETECTION_MAX_SIDE = 960
    }
}
