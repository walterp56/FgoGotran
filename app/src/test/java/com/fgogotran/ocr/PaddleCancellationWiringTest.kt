package com.fgogotran.ocr

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Wiring checks only: these do not execute Android bitmaps or native ONNX inference. */
class PaddleCancellationWiringTest {
    private val source = listOf(
        File("src/main/java/com/fgogotran/ocr/PaddleOcrProvider.kt"),
        File("app/src/main/java/com/fgogotran/ocr/PaddleOcrProvider.kt")
    ).first(File::isFile).readText().replace("\r\n", "\n")

    private fun section(start: String, end: String) = source.substringAfter(start).substringBefore(end)

    @Test fun `provider forwards current request context without shared cancellation state`() {
        assertTrue(source.contains("runtime.recognize(bitmap, contentKind, currentCoroutineContext())"))
        assertTrue(source.contains("runtime.recognize(bitmap, OcrContentKind.CHOICE, currentCoroutineContext(), choices)"))
        assertFalse(source.contains("private var requestContext"))
        assertFalse(source.contains("private val requestContext"))
        val warmUp = section("override suspend fun warmUp()", "override suspend fun recognize(bitmap:")
        assertTrue(Regex("ensureActive\\(\\)\\s+runtime.initialize\\(\\)\\s+ensureActive\\(\\)").containsMatchIn(warmUp))
    }

    @Test fun `native post run checks are inside resource owners before output decoding`() {
        assertEquals(2, Regex("session.run\\(mapOf\\(inputName to inputTensor\\)\\)\\.use \\{ result ->\\s+requestContext.ensureActive\\(\\)").findAll(source).count())
        val detection = section("private fun detectText(", "private fun postprocessDetection(")
        assertTrue(detection.contains("tensor.use { inputTensor ->\n            requestContext.ensureActive()"))
        assertTrue(detection.indexOf("requestContext.ensureActive()", detection.indexOf("session.run(")) < detection.indexOf("firstTensor("))
        val recognition = section("private fun runRecognitionBatch(", "private fun ctcDecode(")
        assertTrue(recognition.contains("preparedBatch.map { prepared ->\n                    requestContext.ensureActive()"))
    }

    @Test fun `all recognition and recovery routes carry the same request context`() {
        assertTrue(source.contains("detectText(recognitionBitmap, requestContext)"))
        assertTrue(source.contains("recognitionTargets, requestContext, collectPositions"))
        assertEquals(2, Regex("recognizeCrop\\(crop, requestContext, collectPositions = true\\)").findAll(source).count())
        assertTrue(source.contains("recognizeCrop(expandedCrop, requestContext)"))
        assertTrue(source.contains("runRecognitionBatch(listOf(prepared), requestContext, collectPositions)"))
        assertTrue(source.contains("for (row in maskRows) {\n            requestContext.ensureActive()"))
        assertTrue(source.contains("for (target in recognitionTargets) {\n            requestContext.ensureActive()"))
        assertEquals(2, Regex("retryLines.indices.forEach \\{ index ->\\s+requestContext.ensureActive\\(\\)").findAll(source).count())
    }

    @Test fun `real batch fallback uses the cancellation aware production decision`() {
        val batches = section("private fun recognizePreparedTargets(", "private fun recognitionBatchSize(")
        assertTrue(batches.contains("paddleBatchOrFallback(requestContext, batch = {"))
        assertTrue(batches.substringAfter("}, fallback = { error ->").contains("recognitionBatchEnabled = false"))
        assertFalse(batches.contains("catch (error: Exception)"))
        assertTrue(batches.contains("while (cursor < preparedTargets.size) {\n            requestContext.ensureActive()"))
    }

    @Test fun `initialization and bitmap cleanup do not acquire cancellation traps`() {
        val initialization = section("fun initialize()", "fun close()")
        assertFalse(initialization.contains("ensureActive"))
        assertTrue(source.contains("mainRecognitionBitmap?.let { if (!it.isRecycled) it.recycle() }"))
        assertTrue(source.contains("if (recognitionBitmap !== bitmap && !recognitionBitmap.isRecycled)"))
        val crop = section("private fun cropTextLine(", "private fun recoverSplitSolidMaskRows(")
        assertFalse(crop.contains("ensureActive"))
    }
}
