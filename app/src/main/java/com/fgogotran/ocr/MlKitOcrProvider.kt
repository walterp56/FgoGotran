package com.fgogotran.ocr

import android.graphics.Bitmap
import android.graphics.Rect
import com.fgogotran.util.FgoLogger
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class MlKitOcrProvider : OcrProvider {
    private val recognizer = TextRecognition.getClient(
        JapaneseTextRecognizerOptions.Builder().build()
    )
    private val tag = "OCR"
    @Volatile
    private var warmedUp = false

    override suspend fun warmUp() {
        if (warmedUp) return
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        try {
            FgoLogger.debug(tag, "ML Kit OCR warm-up starting")
            val image = InputImage.fromBitmap(bitmap, 0)
            recognizer.processSuspending(image)
            FgoLogger.info(tag, "ML Kit OCR warm-up complete")
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
        FgoLogger.debug(tag, "ML Kit OCR starting on ${bitmap.width}x${bitmap.height}")

        val image = InputImage.fromBitmap(bitmap, 0)
        val result = recognizer.processSuspending(image)
        val lines = mutableListOf<OcrTextLine>()

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
            FgoLogger.warn(tag, "ML Kit OCR returned 0 text lines after ${elapsed}ms")
        } else {
            FgoLogger.info(
                tag,
                "ML Kit OCR complete: ${lines.size} lines, ${result.text.length} chars, ${elapsed}ms"
            )
        }

        return OcrResult(
            lines = lines,
            fullText = result.text,
            engine = OcrEngineId.ML_KIT
        )
    }

    override fun close() {
        recognizer.close()
        warmedUp = false
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
