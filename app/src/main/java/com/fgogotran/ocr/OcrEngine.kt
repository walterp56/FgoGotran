package com.fgogotran.ocr

import android.graphics.Bitmap
import android.graphics.Rect
import com.fgogotran.util.FgoLogger
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * A single recognized text line with spatial position and confidence.
 * @property boundingBox pixel coordinates of the line on screen
 * @property confidence ML Kit recognition confidence (0.0 – 1.0)
 */
data class OcrTextLine(
    val text: String,
    val boundingBox: Rect,
    val confidence: Float
)

/**
 * Complete OCR result for one screenshot.
 * @property lines individual text lines with bounding boxes (for region classification)
 * @property fullText concatenated all-text result from ML Kit (for hashing/dedup)
 */
data class OcrResult(
    val lines: List<OcrTextLine>,
    val fullText: String
)

/**
 * Wraps Google ML Kit's Japanese text recognizer.
 *
 * The recognizer is created once at construction time (it's a @Singleton)
 * and reused for every screenshot. ML Kit handles its own threading internally;
 * [Tasks.await] blocks the calling coroutine until recognition completes.
 *
 * Called inside `withContext(Dispatchers.Default)` from the accessibility service
 * so blocking does not affect the main thread.
 */
@Singleton
class OcrEngine @Inject constructor() {

    /** ML Kit on-device Japanese recognizer — no cloud dependency. */
    private val recognizer = TextRecognition.getClient(
        JapaneseTextRecognizerOptions.Builder().build()
    )

    private val tag = "OCR"

    /**
     * Runs ML Kit OCR on [bitmap] and returns structured results.
     *
     * @param bitmap the screenshot to recognize (should be ARGB_8888 or RGB_565)
     * @return [OcrResult] with per-line bounding boxes and concatenated full text
     */
    suspend fun recognize(bitmap: Bitmap): OcrResult {
        val startTime = System.currentTimeMillis()
        FgoLogger.debug(tag, "OCR starting on ${bitmap.width}x${bitmap.height}")

        // InputImage.fromBitmap with rotation=0 (screenshot is already upright)
        val image = InputImage.fromBitmap(bitmap, 0)
        val result = recognizer.processSuspending(image)
        val lines = mutableListOf<OcrTextLine>()

        // ML Kit returns TextBlock → TextLine hierarchy.
        // We flatten to lines for region classification.
        for (block in result.getTextBlocks()) {
            for (line in block.getLines()) {
                lines.add(
                    OcrTextLine(
                        text = line.getText(),
                        boundingBox = line.getBoundingBox() ?: Rect(),
                        confidence = line.getConfidence() ?: 0f
                    )
                )
            }
        }

        val elapsed = System.currentTimeMillis() - startTime
        if (lines.isEmpty()) {
            FgoLogger.warn(tag, "OCR returned 0 text lines after ${elapsed}ms")
        } else {
            FgoLogger.info(tag, "OCR complete: ${lines.size} lines, " +
                "${result.text.length} chars, ${elapsed}ms")
        }

        return OcrResult(
            lines = lines,
            fullText = result.text
        )
    }

    private suspend fun com.google.mlkit.vision.text.TextRecognizer.processSuspending(
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
}
