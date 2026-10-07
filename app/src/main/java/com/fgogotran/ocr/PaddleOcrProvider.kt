package com.fgogotran.ocr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Rect
import com.fgogotran.translation.FgoDialogueSymbols
import com.fgogotran.util.FgoLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.operation.buffer.BufferOp
import java.nio.FloatBuffer
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

internal class PaddleOcrProvider(context: Context) : OcrProvider {
    private val runtime = PaddleOcrRuntime(context.applicationContext)

    override suspend fun warmUp() {
        withContext(Dispatchers.Default) {
            runtime.initialize()
        }
    }

    override suspend fun recognize(bitmap: Bitmap): OcrResult {
        return recognize(bitmap, OcrContentKind.GENERAL)
    }

    override suspend fun recognize(bitmap: Bitmap, contentKind: OcrContentKind): OcrResult {
        return withContext(Dispatchers.Default) {
            runtime.recognize(bitmap, contentKind)
        }
    }

    override suspend fun recognizeChoices(bitmap: Bitmap, buttons: List<Rect>): OcrResult =
        withContext(Dispatchers.Default) {
            runtime.recognize(bitmap, OcrContentKind.CHOICE, ChoiceOcrContext.create(bitmap, buttons))
        }

    override fun close() {
        runtime.close()
    }
}

private class PaddleOcrRuntime(
    private val context: Context
) {
    private val tag = "OCR"
    private val lock = Any()

    private var environment: OrtEnvironment? = null
    private var detSession: OrtSession? = null
    private var recSession: OrtSession? = null
    private var dictionary: List<String> = emptyList()
    private var recognitionBatchEnabled = false
    @Volatile
    private var initialized = false

    fun initialize() {
        if (initialized) return
        synchronized(lock) {
            if (initialized) return
            val startedAt = System.currentTimeMillis()
            try {
                val env = OrtEnvironment.getEnvironment()
                val options = OrtSession.SessionOptions().apply {
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                    setIntraOpNumThreads(4)
                }
                try {
                    environment = env
                    detSession = env.createSession(readAsset(DET_MODEL_ASSET), options)
                    recSession = env.createSession(readAsset(REC_MODEL_ASSET), options)
                } finally {
                    options.close()
                }
                dictionary = loadDictionary()
                val recognitionInputShape = recognitionInputShape(recSession)
                recognitionBatchEnabled = supportsDynamicRecognitionBatch(recognitionInputShape)
                initialized = true
                FgoLogger.info(
                    tag,
                    "PaddleOCR initialized: dict=${dictionary.size}, " +
                        "recInput=${recognitionInputShape.contentToString()}, " +
                        "recBatch=$recognitionBatchEnabled, " +
                        "elapsed=${System.currentTimeMillis() - startedAt}ms"
                )
            } catch (e: Exception) {
                close()
                FgoLogger.warn(tag, "PaddleOCR initialization failed", e)
                throw e
            }
        }
    }

    fun close() {
        synchronized(lock) {
            runCatching { detSession?.close() }
            runCatching { recSession?.close() }
            detSession = null
            recSession = null
            environment = null
            dictionary = emptyList()
            recognitionBatchEnabled = false
            initialized = false
        }
    }

    fun recognize(bitmap: Bitmap, contentKind: OcrContentKind, choices: ChoiceOcrContext? = null): OcrResult {
        initialize()
        require(!bitmap.isRecycled) { "Bitmap has been recycled" }

        val startedAt = System.currentTimeMillis()
        FgoLogger.debug(tag, "PaddleOCR starting on ${bitmap.width}x${bitmap.height}")
        val fixedDialogue = contentKind == OcrContentKind.FIXED_JP_DIALOGUE
        val dialogue = contentKind == OcrContentKind.DIALOGUE || fixedDialogue

        // Dialogue annotations must be separated before detection. If emphasis dots reach Paddle's
        // detector, it can split one horizontal main row into dot/glyph fragments and the recognizer
        // never receives the clean Japanese line. Only verified upper dot components are masked;
        // readable ruby and main-band punctuation remain in the image.
        val originalDialoguePixels = if (dialogue) {
            IntArray(bitmap.width * bitmap.height).also { pixels ->
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            }
        } else {
            null
        }
        val annotationCleanup = originalDialoguePixels?.let { pixels ->
            DialogueAnnotationCleaner.clean(pixels, bitmap.width, bitmap.height, retainPixelIndices = fixedDialogue)
        }
        var dialoguePixels = annotationCleanup?.pixels
        val recognitionBitmap = choices?.recognitionBitmap(bitmap) ?: annotationCleanup
            ?.takeIf(DialogueAnnotationCleaner.Result::changed)
            ?.let { cleanup ->
                Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888).apply {
                    setPixels(cleanup.pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                }.also {
                    FgoLogger.debug(
                        tag,
                        "PaddleOCR dialogue annotations cleaned before recognition: " +
                            "dots=${cleanup.maskedComponents}, rows=${cleanup.maskedRows}"
                    )
                }
            } ?: bitmap
        var mainRecognitionBitmap: Bitmap? = null

        return try {
        val detection = detectText(recognitionBitmap)
        val boxes = detection.boxes
            .sortedWith(compareBy({ boxMinY(it) }, { boxMinX(it) }))

        val rubyPlanningStartedAt = System.currentTimeMillis()
        val rubyGroups = when {
            choices != null -> DialogueRubyGroupRecovery.planChoices(choices.analysis,
                boxes.map { boxToRect(it, bitmap.width, bitmap.height).toRubyGroupBox() }, bitmap.width, bitmap.height)
            fixedDialogue && annotationCleanup != null -> DialogueRubyGroupRecovery.planFixedDialogue(
                annotationCleanup.components, boxes.map { boxToRect(it, bitmap.width, bitmap.height).toRubyGroupBox() },
                bitmap.width, bitmap.height)
            annotationCleanup != null -> DialogueRubyGroupRecovery.plan(annotationCleanup.components,
                boxes.map { boxToRect(it, bitmap.width, bitmap.height).toRubyGroupBox() }, bitmap.width, bitmap.height)
            else -> emptyList()
        }
        val rubyPlanningMs = System.currentTimeMillis() - rubyPlanningStartedAt
        // Ordinary dialogue keeps its existing detector crops; only actual ruby needs separation.
        val separatedDialogue = fixedDialogue && rubyGroups.isNotEmpty()
        val rubyComponents = if (fixedDialogue) rubyGroups.flatMap { it.components }.toSet() else emptySet()
        val mainComponents = if (fixedDialogue) annotationCleanup?.components.orEmpty().filterNot { it in rubyComponents }
            else annotationCleanup?.components.orEmpty()
        val mainRows = if (separatedDialogue) DialogueRubyGroupRecovery.mainRows(
            mainComponents, rubyGroups, bitmap.width, bitmap.height) else emptyList()
        val mainBoxes = if (separatedDialogue) mainRows.map { it.second.toRecognitionBox() } else boxes
        val mainTargetCount = mainBoxes.size
        if (separatedDialogue && dialoguePixels != null) {
            dialoguePixels = DialogueRubyGroupRecovery.mainPixels(dialoguePixels, bitmap.width, rubyGroups)
            mainRecognitionBitmap = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888).apply {
                setPixels(dialoguePixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            }
        }
        val mainSource = mainRecognitionBitmap ?: recognitionBitmap
        val supplementaryBoxes = rubyGroups.map { group -> group.bounds.toRecognitionBox() }
        // Fixed dialogue recognizes each complete reading once, separately from its clean main row.
        // Legacy dialogue/choices retain their original targets and supplementary fallback policy.
        val targetBoxes = mainBoxes + supplementaryBoxes
        val recognitionTargets = targetBoxes.mapIndexed { index, box ->
            val bounds = boxToRect(box, recognitionBitmap.width, recognitionBitmap.height)
            val visualDash = (!separatedDialogue || index < mainTargetCount) && dialoguePixels != null && isVisualDialogueDash(
                dialoguePixels, bitmap.width, bitmap.height, bounds
            )
            // A verified visual dash needs neither scaling nor a model recognition call.
            val crop = if (visualDash) null else cropTextLine(
                if (separatedDialogue && index < mainTargetCount) mainSource else recognitionBitmap, box)
            val prepared = if (crop == null) {
                null
            } else {
                try {
                    prepareRecognitionCrop(crop)
                } finally {
                    if (!crop.isRecycled) crop.recycle()
                }
            }
            PaddleRecognitionTarget(
                index = index,
                box = box,
                bounds = bounds,
                prepared = prepared,
                visualDash = visualDash
            )
        }
        val batchedRecognitionStartedAt = System.currentTimeMillis()
        val tightRecognitions = recognizePreparedTargets(
            recognitionTargets, collectPositions = dialogue || choices != null
        )
        if (rubyGroups.isNotEmpty()) FgoLogger.debug(
            tag, "PaddleOCR ${if (choices == null) "ruby" else "choice ruby"} groups planned: added=${rubyGroups.size}, planningMs=$rubyPlanningMs, " +
                "combinedRecognitionMs=${System.currentTimeMillis() - batchedRecognitionStartedAt}"
        )

        val detectedTextBoxes = mutableListOf<PaddleDetectedTextBox>()
        var edgeRecoveryAttempts = 0
        var edgeRecoveryChanges = 0
        val edgeBudget = if (choices != null) CHOICE_EDGE_RECOVERY_MAX_PASSES else DIALOGUE_EDGE_RECOVERY_MAX_PASSES
        for (target in recognitionTargets) {
            if (target.index >= mainTargetCount) break
            val tightRecognition = tightRecognitions[target.index]
            if (target.visualDash) {
                FgoLogger.debug(
                    tag,
                    "PaddleOCR visual dialogue dash recovered from pixels: " +
                        "box=${target.bounds.flattenToString()}, " +
                        "recognition=${tightRecognition?.text.orEmpty()}"
                )
                detectedTextBoxes += PaddleDetectedTextBox(
                    bounds = target.bounds,
                    lowConfidenceEdgeFragment = OcrTextLine(
                        text = FgoDialogueSymbols.LONG_DASH_RUN,
                        boundingBox = target.bounds,
                        confidence = REC_TEXT_SCORE_THRESHOLD
                    )
                )
                continue
            }
            if (tightRecognition == null) {
                detectedTextBoxes += PaddleDetectedTextBox(bounds = target.bounds)
                continue
            }
            val edgeRecovery = if (
                (contentKind == OcrContentKind.GENERAL && choices == null) || edgeRecoveryAttempts < edgeBudget
            ) {
                recoverEdgePunctuation(
                    source = mainSource,
                    box = target.box,
                    tightRecognition = tightRecognition,
                    contentKind = contentKind,
                    choiceBounds = choices?.analysis?.firstOrNull { button ->
                        target.bounds.centerX() in button.bounds.left until button.bounds.right &&
                            target.bounds.centerY() in button.bounds.top until button.bounds.bottom
                    }?.interior?.let { Rect(it.left, it.top, it.right, it.bottom) },
                    choicePixels = choices?.pixels
                )
            } else {
                EdgePunctuationRecovery(tightRecognition)
            }
            if (edgeRecovery.attempted) edgeRecoveryAttempts++
            if (edgeRecovery.recognition.text != tightRecognition.text) edgeRecoveryChanges++
            val recognition = edgeRecovery.recognition
            val text = recognition.text
            val confidence = recognition.confidence
            if (text.isNotBlank() && confidence >= REC_TEXT_SCORE_THRESHOLD) {
                detectedTextBoxes += PaddleDetectedTextBox(
                    bounds = target.bounds,
                    line = OcrTextLine(
                        text = text,
                        boundingBox = target.bounds,
                        confidence = confidence.coerceIn(0f, 1f)
                    ),
                    recoveredMaskCount = recognition.recoveredMaskCount,
                    noisyLeadingQuoteCandidate = edgeRecovery.noisyLeadingQuoteCandidate
                )
            } else {
                val lowConfidenceEdgeFragment = text
                    .takeIf {
                        confidence >= EDGE_RECOVERY_TEXT_SCORE_THRESHOLD &&
                            PaddleEdgePunctuationMerger.isRecoverableDetachedFragment(it) &&
                            (it.hasEdgeQuotationMark() ||
                                (dialogue &&
                                    PaddleEdgePunctuationMerger.isDialoguePauseOrDashFragment(it)))
                    }
                    ?.let {
                        OcrTextLine(
                            text = it,
                            boundingBox = target.bounds,
                            confidence = confidence.coerceIn(0f, 1f)
                        )
                    }
                detectedTextBoxes += PaddleDetectedTextBox(
                    bounds = target.bounds,
                    lowConfidenceEdgeFragment = lowConfidenceEdgeFragment
                )
            }
        }
        if (mainTargetCount > 0) {
            FgoLogger.debug(
                tag,
                "PaddleOCR edge recovery gate: attempts=$edgeRecoveryAttempts/$mainTargetCount, " +
                    "changes=$edgeRecoveryChanges"
            )
        }

        val quoteRecoveredTextBoxes = recoverNoisyLeadingQuoteCandidates(detectedTextBoxes)
        val recoveredLines = recoverSplitSolidMaskRows(
            source = mainSource,
            detectedTextBoxes = quoteRecoveredTextBoxes,
            maskRows = detection.solidMaskRows
        )
        val lowConfidenceEdgeFragments = quoteRecoveredTextBoxes
            .mapNotNull(PaddleDetectedTextBox::lowConfidenceEdgeFragment)
            .filterWithMatchingQuoteEvidence(
                regularLines = recoveredLines,
                allowDialoguePauseOrDash = dialogue
            )
        val mergedLines = recoverDetachedEdgePunctuation(
            lines = recoveredLines,
            lowConfidenceEdgeFragments = lowConfidenceEdgeFragments
        )
            .sortedWith(compareBy({ it.boundingBox.top }, { it.boundingBox.left }))
        val punctuationLines = if (choices != null) {
            val positioned = mergedLines.mapIndexed { index, line ->
                val target = recognitionTargets.firstOrNull { it.index < mainTargetCount && it.bounds == line.boundingBox }
                val value = dialoguePunctuationLine(line, target?.let { tightRecognitions[it.index] }, target?.box)
                ChoicePunctuationRecovery.Line(index, value.text,
                    ChoicePunctuationRecovery.Bounds(value.bounds.left, value.bounds.top, value.bounds.right, value.bounds.bottom),
                    value.confidence, value.tokens)
            }
            var recovery = choices.recover(positioned)
            var positionRetries = 0
            val retryLines = recovery.lines.toMutableList()
            retryLines.indices.forEach { index ->
                val line = retryLines[index]
                if (line.sourceIndex !in recovery.unresolvedSourceIndices || edgeRecoveryAttempts >= edgeBudget) return@forEach
                val crop = cropTextLine(recognitionBitmap,
                    DialogueRubyLayout.Box(line.bounds.left, line.bounds.top, line.bounds.right, line.bounds.bottom).toRecognitionBox())
                    ?: return@forEach
                val recognition = try {
                    edgeRecoveryAttempts++
                    positionRetries++
                    recognizeCrop(crop, collectPositions = true)
                } finally { if (!crop.isRecycled) crop.recycle() }
                if (recognition.confidence >= REC_TEXT_SCORE_THRESHOLD) {
                    val text = PaddleEdgePunctuationMerger.merge(line.text, recognition.text)
                    val original = OcrTextLine(text, Rect(line.bounds.left, line.bounds.top, line.bounds.right, line.bounds.bottom), recognition.confidence)
                    val positionedLine = dialoguePunctuationLine(original, recognition,
                        original.boundingBox.toRubyGroupBox().toRecognitionBox())
                    if (positionedLine.tokens.isNotEmpty()) retryLines[index] = line.copy(text = text, tokens = positionedLine.tokens)
                }
            }
            if (positionRetries > 0) {
                val retried = choices.recover(retryLines)
                recovery = retried.copy(recoveredCount = recovery.recoveredCount + retried.recoveredCount)
            }
            FgoLogger.debug(tag, "PaddleOCR choice punctuation: recovered=${recovery.recoveredCount}, " +
                "positionRetries=$positionRetries, unresolved=${recovery.unresolvedSourceIndices.size}")
            recovery.lines.map { OcrTextLine(it.text,
                Rect(it.bounds.left, it.bounds.top, it.bounds.right, it.bounds.bottom), it.confidence) }
        } else if (dialoguePixels == null) {
            mergedLines
        } else {
            val positionedLines = mergedLines.map { line ->
                val target = recognitionTargets.firstOrNull { it.index < mainTargetCount && it.bounds == line.boundingBox }
                val recognition = target?.let { tightRecognitions[it.index] }
                dialoguePunctuationLine(line, recognition, target?.box)
            }
            var recovery = DialoguePunctuationRecovery.recover(
                pixels = dialoguePixels,
                width = bitmap.width,
                height = bitmap.height,
                components = mainComponents,
                lines = positionedLines
            )
            // Usually the base CTC positions are sufficient. If a mask/edge merge invalidated
            // them, reuse the remaining existing recovery budget on that row, inside this crop.
            // The retry can add edge punctuation, but cannot replace any recognised word.
            val retryLines = recovery.lines.toMutableList()
            var positionRetries = 0
            retryLines.indices.forEach { index ->
                if (edgeRecoveryAttempts >= DIALOGUE_EDGE_RECOVERY_MAX_PASSES) return@forEach
                val line = retryLines[index]
                if (recovery.unresolvedBounds.none { bounds ->
                        bounds.centerX in line.bounds.left.toFloat()..line.bounds.right.toFloat() &&
                            bounds.centerY in line.bounds.top.toFloat()..line.bounds.bottom.toFloat()
                    }) return@forEach
                val box = floatArrayOf(
                    line.bounds.left.toFloat(), line.bounds.top.toFloat(),
                    line.bounds.right.toFloat(), line.bounds.top.toFloat(),
                    line.bounds.right.toFloat(), line.bounds.bottom.toFloat(),
                    line.bounds.left.toFloat(), line.bounds.bottom.toFloat()
                )
                val crop = cropTextLine(mainSource, box) ?: return@forEach
                val recognition = try {
                    edgeRecoveryAttempts++
                    positionRetries++
                    recognizeCrop(crop, collectPositions = true)
                } finally {
                    if (!crop.isRecycled) crop.recycle()
                }
                if (recognition.confidence >= REC_TEXT_SCORE_THRESHOLD) {
                    val text = PaddleEdgePunctuationMerger.merge(line.text, recognition.text)
                    val positioned = dialoguePunctuationLine(
                        OcrTextLine(text, line.bounds.toRect(), recognition.confidence), recognition, box
                    )
                    if (positioned.tokens.isNotEmpty()) retryLines[index] = positioned
                }
            }
            if (positionRetries > 0) {
                val retried = DialoguePunctuationRecovery.recover(
                    dialoguePixels, bitmap.width, bitmap.height,
                    mainComponents, retryLines
                )
                recovery = retried.copy(recoveredCount = recovery.recoveredCount + retried.recoveredCount)
            }
            FgoLogger.debug(
                tag, "PaddleOCR fixed dialogue punctuation: recovered=${recovery.recoveredCount}, " +
                    "positionRetries=$positionRetries, unresolved=${recovery.unresolvedBounds.size}"
            )
            recovery.lines.map { line -> OcrTextLine(line.text, line.bounds.toRect(), line.confidence,
                dialogueRow = if (separatedDialogue) DialogueRubyLayout.mainRow(line.bounds.centerY, bitmap.height) else null,
                characterPositions = if (separatedDialogue) line.tokens.map { OcrCharacterPosition(it.text, it.centerX) } else emptyList()) }
        }
        // Ruby never participates in main-row punctuation recovery. Fixed dialogue retains its
        // band ownership; legacy dialogue/choices keep their supplementary replacement policy.
        val lines = if (fixedDialogue) {
            punctuationLines + rubyGroups.mapIndexedNotNull { index, group ->
                val target = recognitionTargets[mainTargetCount + index]
                val reading = tightRecognitions[target.index]
                FgoLogger.debug(tag, "PaddleOCR fixed ruby: row=${group.dialogueRow}, " +
                    "box=${target.bounds.flattenToString()}, text=${reading?.text.orEmpty()}, " +
                    "confidence=${reading?.confidence ?: 0f}")
                reading?.takeIf { it.text.isNotBlank() && it.confidence >= REC_TEXT_SCORE_THRESHOLD }?.let {
                    OcrTextLine(it.text, target.bounds, it.confidence.coerceIn(0f, 1f),
                        dialogueRow = group.dialogueRow, isDialogueRuby = true)
                }
            }
        } else recoverRubyGroups(
            punctuationLines, rubyGroups, recognitionTargets, tightRecognitions, mainTargetCount,
            choice = choices != null
        )

        val fullText = lines.joinToString("\n") { it.text }
        val elapsed = System.currentTimeMillis() - startedAt
        if (lines.isEmpty()) {
            FgoLogger.warn(tag, "PaddleOCR returned 0 text lines after ${elapsed}ms")
        } else {
            FgoLogger.info(
                tag,
                "PaddleOCR complete: ${lines.size} lines, ${fullText.length} chars, ${elapsed}ms"
            )
        }
        OcrResult(
            lines = lines,
            fullText = fullText,
            engine = OcrEngineId.PADDLE_OCR
        )
        } finally {
            mainRecognitionBitmap?.let { if (!it.isRecycled) it.recycle() }
            if (recognitionBitmap !== bitmap && !recognitionBitmap.isRecycled) {
                recognitionBitmap.recycle()
            }
        }
    }

    private fun Rect.toRubyGroupBox() = DialogueRubyLayout.Box(left, top, right, bottom)

    private fun DialogueRubyLayout.Box.toRecognitionBox() = floatArrayOf(
        left.toFloat(), top.toFloat(), right.toFloat(), top.toFloat(),
        right.toFloat(), bottom.toFloat(), left.toFloat(), bottom.toFloat()
    )

    private fun recoverRubyGroups(
        lines: List<OcrTextLine>,
        groups: List<DialogueRubyGroupRecovery.Group>,
        targets: List<PaddleRecognitionTarget>,
        recognitions: Map<Int, PaddleMaskMergeResult>,
        originalTargetCount: Int,
        choice: Boolean = false
    ): List<OcrTextLine> {
        if (groups.isEmpty()) return lines
        val output = lines.toMutableList()
        groups.forEachIndexed { index, group ->
            val target = targets[originalTargetCount + index]
            val originalBounds = group.originalBoxIndices.map { targets[it].bounds }
            val originals = output.filter { it.boundingBox in originalBounds }
                .sortedBy { it.boundingBox.left }
            val recognition = recognitions[target.index]
            val overlapChanged = DialogueRubyGroupRecovery.hasUnownedOverlap(
                group.bounds, originalBounds.map { it.toRubyGroupBox() }, output.map { it.boundingBox.toRubyGroupBox() }
            )
            val reason = if (overlapChanged) "overlap_changed" else DialogueRubyGroupRecovery.rejectionReason(
                recognition?.let { DialogueRubyGroupRecovery.Reading(it.text, it.confidence) },
                originals.map { DialogueRubyGroupRecovery.Reading(it.text, it.confidence) }
            )
            FgoLogger.debug(
                tag, "PaddleOCR ${if (choice) "choice ruby" else "ruby"} group: box=${target.bounds.flattenToString()}, " +
                    "components=${group.componentCount}, hint=${group.expectedBand}, " +
                    "text=${recognition?.text.orEmpty()}, confidence=${recognition?.confidence ?: 0f}, " +
                    "result=${reason ?: "accepted"}, originals=${originals.size}"
            )
            if (reason == null && recognition != null) {
                output.removeAll(originals.toSet())
                output += OcrTextLine(recognition.text, target.bounds, recognition.confidence.coerceIn(0f, 1f),
                    isRecoveredRubyGroup = choice)
            }
        }
        return output.sortedWith(compareBy({ it.boundingBox.top }, { it.boundingBox.left }))
    }

    private fun readAsset(path: String): ByteArray {
        return context.assets.open(path).use { it.readBytes() }
    }

    private fun loadDictionary(): List<String> {
        val rows = context.assets.open(DICT_ASSET)
            .bufferedReader(Charsets.UTF_8)
            .readLines()
        return buildList(rows.size + 1) {
            add("")
            addAll(rows)
        }
    }

    private fun detectText(bitmap: Bitmap): PaddleDetectionResult {
        val width = bitmap.width
        val height = bitmap.height
        val scale = if (max(width, height) > DET_LIMIT_SIDE_LEN) {
            DET_LIMIT_SIDE_LEN.toFloat() / max(width, height).toFloat()
        } else {
            1f
        }
        val resizedWidth = alignTo32((width * scale).roundToInt())
        val resizedHeight = alignTo32((height * scale).roundToInt())
        val resized = Bitmap.createScaledBitmap(bitmap, resizedWidth, resizedHeight, true)
        val pixels = IntArray(resizedWidth * resizedHeight)
        resized.getPixels(pixels, 0, resizedWidth, 0, 0, resizedWidth, resizedHeight)
        if (resized !== bitmap) resized.recycle()
        val sourceScaleX = width.toFloat() / resizedWidth.toFloat()
        val sourceScaleY = height.toFloat() / resizedHeight.toFloat()
        val solidMaskRows = PaddleSolidMaskDetector
            .detectRows(pixels, resizedWidth, resizedHeight)
            .map { row -> row.scaled(sourceScaleX, sourceScaleY) }

        val pixelCount = resizedWidth * resizedHeight
        val input = FloatArray(pixelCount * 3)
        for (index in 0 until pixelCount) {
            val pixel = pixels[index]
            val blue = (pixel and 0xff) / 255f
            val green = ((pixel shr 8) and 0xff) / 255f
            val red = ((pixel shr 16) and 0xff) / 255f
            input[index] = (blue - DET_MEAN[0]) / DET_STD[0]
            input[pixelCount + index] = (green - DET_MEAN[1]) / DET_STD[1]
            input[pixelCount * 2 + index] = (red - DET_MEAN[2]) / DET_STD[2]
        }

        val env = environment ?: error("PaddleOCR environment is not initialized")
        val session = detSession ?: error("PaddleOCR detection session is not initialized")
        val inputName = session.inputNames.first()
        val tensor = OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(input),
            longArrayOf(1, 3, resizedHeight.toLong(), resizedWidth.toLong())
        )
        val (probabilities, predHeight, predWidth) = tensor.use { inputTensor ->
            session.run(mapOf(inputName to inputTensor)).use { result ->
                val output = firstTensor(result, session)
                val shape = output.info.shape
                val outputHeight = shape[shape.size - 2].toInt()
                val outputWidth = shape[shape.size - 1].toInt()
                val values = FloatArray(outputHeight * outputWidth)
                output.floatBuffer.apply {
                    rewind()
                    get(values, 0, values.size)
                }
                Triple(values, outputHeight, outputWidth)
            }
        }

        val boxes = postprocessDetection(probabilities, predWidth, predHeight, width, height)
        if (solidMaskRows.isNotEmpty()) {
            FgoLogger.debug(
                tag,
                "PaddleOCR full-input solid masks: " +
                    "rows=${solidMaskRows.size}, count=${solidMaskRows.sumOf { it.masks.size }}, " +
                    "separators=${solidMaskRows.sumOf { it.separators.size }}"
            )
        }
        return PaddleDetectionResult(boxes = boxes, solidMaskRows = solidMaskRows)
    }

    private fun postprocessDetection(
        probabilities: FloatArray,
        predWidth: Int,
        predHeight: Int,
        sourceWidth: Int,
        sourceHeight: Int
    ): List<FloatArray> {
        val active = BooleanArray(predWidth * predHeight) { probabilities[it] > DET_THRESHOLD }
        val visited = BooleanArray(active.size)
        val components = mutableListOf<MutableList<Coordinate>>()
        val queue = ArrayDeque<Int>()
        val neighborX = intArrayOf(-1, 0, 1, -1, 1, -1, 0, 1)
        val neighborY = intArrayOf(-1, -1, -1, 0, 0, 1, 1, 1)

        for (start in active.indices) {
            if (!active[start] || visited[start]) continue
            val component = mutableListOf<Coordinate>()
            visited[start] = true
            queue.addLast(start)
            while (queue.isNotEmpty()) {
                val current = queue.removeFirst()
                val x = current % predWidth
                val y = current / predWidth
                component.add(Coordinate(x.toDouble(), y.toDouble()))
                for (offset in neighborX.indices) {
                    val nextX = x + neighborX[offset]
                    val nextY = y + neighborY[offset]
                    if (nextX !in 0 until predWidth || nextY !in 0 until predHeight) continue
                    val nextIndex = nextY * predWidth + nextX
                    if (active[nextIndex] && !visited[nextIndex]) {
                        visited[nextIndex] = true
                        queue.addLast(nextIndex)
                    }
                }
            }
            if (component.size >= DET_MIN_COMPONENT_PIXELS) {
                components.add(component)
            }
        }

        val candidates = components
            .sortedByDescending { it.size }
            .take(DET_MAX_CANDIDATES)
        val sourceScaleX = sourceWidth.toFloat() / predWidth.toFloat()
        val sourceScaleY = sourceHeight.toFloat() / predHeight.toFloat()
        val boxes = mutableListOf<FloatArray>()

        for (component in candidates) {
            val firstBox = minimumAreaBox(component) ?: continue
            if (firstBox.minSide < DET_MIN_BOX_SIDE) continue
            val score = boxMeanScore(probabilities, predWidth, predHeight, firstBox.points)
            if (score < DET_BOX_SCORE_THRESHOLD) continue
            val expandedPolygon = expandPolygon(firstBox.points, DET_UNCLIP_RATIO)
                ?: firstBox.points.toList()
            val expandedBox = minimumAreaBox(expandedPolygon) ?: firstBox
            if (expandedBox.minSide < DET_MIN_BOX_SIDE) continue
            boxes.add(mapBoxToSource(expandedBox.points, sourceScaleX, sourceScaleY, sourceWidth, sourceHeight))
        }

        FgoLogger.debug(tag, "PaddleOCR detection: components=${components.size}, boxes=${boxes.size}")
        return boxes
    }

    private fun minimumAreaBox(points: List<Coordinate>): MiniBox? {
        val hull = convexHull(points)
        if (hull.size < 3) return null

        var bestArea = Double.MAX_VALUE
        var bestBox: MiniBox? = null
        for (index in hull.indices) {
            val next = (index + 1) % hull.size
            val edgeX = hull[next].x - hull[index].x
            val edgeY = hull[next].y - hull[index].y
            val edgeLength = sqrt(edgeX * edgeX + edgeY * edgeY)
            if (edgeLength < 1e-6) continue

            val ux = edgeX / edgeLength
            val uy = edgeY / edgeLength
            val vx = -uy
            val vy = ux
            var minU = Double.MAX_VALUE
            var maxU = -Double.MAX_VALUE
            var minV = Double.MAX_VALUE
            var maxV = -Double.MAX_VALUE

            for (point in hull) {
                val localX = point.x - hull[index].x
                val localY = point.y - hull[index].y
                val projectedU = localX * ux + localY * uy
                val projectedV = localX * vx + localY * vy
                minU = min(minU, projectedU)
                maxU = max(maxU, projectedU)
                minV = min(minV, projectedV)
                maxV = max(maxV, projectedV)
            }

            val boxWidth = maxU - minU
            val boxHeight = maxV - minV
            val area = boxWidth * boxHeight
            if (area >= bestArea) continue

            val centerU = (minU + maxU) / 2.0
            val centerV = (minV + maxV) / 2.0
            val centerX = hull[index].x + centerU * ux + centerV * vx
            val centerY = hull[index].y + centerU * uy + centerV * vy
            val halfWidth = boxWidth / 2.0
            val halfHeight = boxHeight / 2.0
            val corners = arrayOf(
                Coordinate(centerX - halfWidth * ux - halfHeight * vx, centerY - halfWidth * uy - halfHeight * vy),
                Coordinate(centerX + halfWidth * ux - halfHeight * vx, centerY + halfWidth * uy - halfHeight * vy),
                Coordinate(centerX + halfWidth * ux + halfHeight * vx, centerY + halfWidth * uy + halfHeight * vy),
                Coordinate(centerX - halfWidth * ux + halfHeight * vx, centerY - halfWidth * uy + halfHeight * vy)
            )
            bestArea = area
            bestBox = MiniBox(orderQuad(corners), min(boxWidth, boxHeight).toFloat())
        }
        return bestBox
    }

    private fun convexHull(points: List<Coordinate>): List<Coordinate> {
        if (points.size <= 3) return points.distinctBy { it.x to it.y }
        val sorted = points
            .distinctBy { it.x to it.y }
            .sortedWith(compareBy({ it.x }, { it.y }))
        if (sorted.size <= 3) return sorted

        fun cross(origin: Coordinate, a: Coordinate, b: Coordinate): Double {
            return (a.x - origin.x) * (b.y - origin.y) - (a.y - origin.y) * (b.x - origin.x)
        }

        val lower = mutableListOf<Coordinate>()
        for (point in sorted) {
            while (lower.size >= 2 && cross(lower[lower.size - 2], lower.last(), point) <= 0.0) {
                lower.removeAt(lower.lastIndex)
            }
            lower.add(point)
        }

        val upper = mutableListOf<Coordinate>()
        for (point in sorted.asReversed()) {
            while (upper.size >= 2 && cross(upper[upper.size - 2], upper.last(), point) <= 0.0) {
                upper.removeAt(upper.lastIndex)
            }
            upper.add(point)
        }

        lower.removeAt(lower.lastIndex)
        upper.removeAt(upper.lastIndex)
        return lower + upper
    }

    private fun orderQuad(points: Array<Coordinate>): Array<Coordinate> {
        val topLeft = points.minBy { it.x + it.y }
        val bottomRight = points.maxBy { it.x + it.y }
        val topRight = points.minBy { it.y - it.x }
        val bottomLeft = points.maxBy { it.y - it.x }
        return arrayOf(topLeft, topRight, bottomRight, bottomLeft)
    }

    private fun boxMeanScore(
        probabilities: FloatArray,
        width: Int,
        height: Int,
        box: Array<Coordinate>
    ): Float {
        val minX = box.minOf { it.x.toInt() }.coerceIn(0, width - 1)
        val maxX = box.maxOf { it.x.toInt() }.coerceIn(0, width - 1)
        val minY = box.minOf { it.y.toInt() }.coerceIn(0, height - 1)
        val maxY = box.maxOf { it.y.toInt() }.coerceIn(0, height - 1)
        if (minX > maxX || minY > maxY) return 0f

        var total = 0f
        var count = 0
        for (y in minY..maxY) {
            for (x in minX..maxX) {
                if (pointInPolygon(x.toDouble(), y.toDouble(), box)) {
                    total += probabilities[y * width + x]
                    count += 1
                }
            }
        }
        return if (count == 0) 0f else total / count.toFloat()
    }

    private fun pointInPolygon(x: Double, y: Double, polygon: Array<Coordinate>): Boolean {
        var inside = false
        var previous = polygon.lastIndex
        for (current in polygon.indices) {
            val yi = polygon[current].y
            val yj = polygon[previous].y
            val xi = polygon[current].x
            val xj = polygon[previous].x
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) {
                inside = !inside
            }
            previous = current
        }
        return inside
    }

    private fun expandPolygon(points: Array<Coordinate>, ratio: Double): List<Coordinate>? {
        val area = polygonArea(points)
        val perimeter = polygonPerimeter(points)
        if (area <= 0.0 || perimeter <= 1e-6) return null
        val distance = area * ratio / perimeter
        val ring = Array(points.size + 1) { index ->
            if (index < points.size) points[index] else points.first()
        }
        return runCatching {
            val geometry = GEOMETRY_FACTORY.createPolygon(ring)
            val buffered = BufferOp.bufferOp(geometry, distance)
            val coordinates = largestGeometry(buffered)?.coordinates ?: return@runCatching null
            val withoutClosingPoint = if (
                coordinates.size > 1 &&
                coordinates.first().equals2D(coordinates.last())
            ) {
                coordinates.dropLast(1)
            } else {
                coordinates.toList()
            }
            withoutClosingPoint.takeIf { it.size >= 3 }
        }.getOrNull()
    }

    private fun largestGeometry(geometry: Geometry?): Geometry? {
        if (geometry == null || geometry.isEmpty) return null
        var best = geometry.getGeometryN(0)
        for (index in 1 until geometry.numGeometries) {
            val candidate = geometry.getGeometryN(index)
            if (candidate.area > best.area) best = candidate
        }
        return best
    }

    private fun polygonArea(points: Array<Coordinate>): Double {
        var area = 0.0
        for (index in points.indices) {
            val next = (index + 1) % points.size
            area += points[index].x * points[next].y - points[next].x * points[index].y
        }
        return abs(area) / 2.0
    }

    private fun polygonPerimeter(points: Array<Coordinate>): Double {
        var perimeter = 0.0
        for (index in points.indices) {
            val next = (index + 1) % points.size
            val dx = points[next].x - points[index].x
            val dy = points[next].y - points[index].y
            perimeter += sqrt(dx * dx + dy * dy)
        }
        return perimeter
    }

    private fun mapBoxToSource(
        points: Array<Coordinate>,
        scaleX: Float,
        scaleY: Float,
        sourceWidth: Int,
        sourceHeight: Int
    ): FloatArray {
        val ordered = orderQuad(points)
        val mapped = FloatArray(8)
        for (index in ordered.indices) {
            mapped[index * 2] = (ordered[index].x.toFloat() * scaleX)
                .coerceIn(0f, (sourceWidth - 1).toFloat())
            mapped[index * 2 + 1] = (ordered[index].y.toFloat() * scaleY)
                .coerceIn(0f, (sourceHeight - 1).toFloat())
        }
        return mapped
    }

    /**
     * FGO draws a leading dramatic dash as one long, five-pixel-high stroke. Paddle's detector
     * normally finds that stroke, but the recognizer often returns blank or an unrelated glyph.
     * Recover it from geometry only in a dialogue crop. Requiring a bright, nearly continuous
     * horizontal run inside a short detector box excludes ordinary kanji such as `一`.
     */
    private fun isVisualDialogueDash(
        pixels: IntArray,
        sourceWidth: Int,
        sourceHeight: Int,
        bounds: Rect
    ): Boolean {
        if (sourceWidth <= 0 || sourceHeight <= 0 || pixels.size < sourceWidth * sourceHeight) {
            return false
        }
        val clipped = Rect(bounds)
        if (!clipped.intersect(0, 0, sourceWidth, sourceHeight)) return false
        val width = clipped.width()
        val height = clipped.height()
        val minimumWidth = max(DIALOGUE_DASH_MIN_WIDTH, (sourceWidth * DIALOGUE_DASH_MIN_WIDTH_RATIO).roundToInt())
        val maximumHeight = max(
            DIALOGUE_DASH_MAX_HEIGHT,
            (sourceHeight * DIALOGUE_DASH_MAX_HEIGHT_RATIO).roundToInt()
        )
        if (width < minimumWidth || height <= 0 || height > maximumHeight) return false
        if (width < height * DIALOGUE_DASH_MIN_VISUAL_ASPECT_RATIO) return false

        var longestRun = 0
        var foregroundTop = clipped.bottom
        var foregroundBottom = clipped.top
        var foregroundPixels = 0
        for (y in clipped.top until clipped.bottom) {
            var currentRun = 0
            for (x in clipped.left until clipped.right) {
                if (pixels[y * sourceWidth + x].isDialoguePunctuationPixel()) {
                    currentRun++
                    foregroundPixels++
                    foregroundTop = min(foregroundTop, y)
                    foregroundBottom = max(foregroundBottom, y + 1)
                    longestRun = max(longestRun, currentRun)
                } else {
                    currentRun = 0
                }
            }
        }
        if (foregroundPixels < minimumWidth) return false
        if (longestRun < max(DIALOGUE_DASH_MIN_RUN, (width * DIALOGUE_DASH_MIN_RUN_RATIO).roundToInt())) {
            return false
        }
        val foregroundHeight = (foregroundBottom - foregroundTop).coerceAtLeast(0)
        return foregroundHeight in 1..max(
            DIALOGUE_DASH_MAX_STROKE_HEIGHT,
            (height * DIALOGUE_DASH_MAX_STROKE_HEIGHT_RATIO).roundToInt()
        )
    }


    private fun Int.isDialoguePunctuationPixel(): Boolean {
        val red = (this shr 16) and 0xff
        val green = (this shr 8) and 0xff
        val blue = this and 0xff
        return FgoStoryTextPalette.isDialogueInk(red, green, blue)
    }

    private fun DialoguePunctuationRecovery.Bounds.toRect() = Rect(left, top, right, bottom)

    private fun dialoguePunctuationLine(
        line: OcrTextLine,
        recognition: PaddleMaskMergeResult?,
        box: FloatArray?
    ): DialoguePunctuationRecovery.Line {
        val positioned = recognition?.positionedTokens.orEmpty()
        val tokens = if (box != null && positioned.joinToString("") { it.text } == recognition?.text &&
            positioned.all { it.centerX.isFinite() && it.centerX in 0f..1f } &&
            line.boundingBox.width() >= line.boundingBox.height()
        ) {
            val left = (box[0] + box[6]) / 2f
            val right = (box[2] + box[4]) / 2f
            DialoguePunctuationRecovery.tokensForText(line.text,
                positioned.map { DialoguePunctuationRecovery.Token(it.text, left + it.centerX * (right - left)) })
        } else emptyList()
        return DialoguePunctuationRecovery.Line(
            line.text,
            DialoguePunctuationRecovery.Bounds(
                line.boundingBox.left, line.boundingBox.top, line.boundingBox.right, line.boundingBox.bottom
            ), line.confidence, tokens
        )
    }

    private fun recoverEdgePunctuation(
        source: Bitmap,
        box: FloatArray,
        tightRecognition: PaddleMaskMergeResult,
        contentKind: OcrContentKind,
        choiceBounds: Rect? = null,
        choicePixels: IntArray? = null
    ): EdgePunctuationRecovery {
        val dialogue = contentKind == OcrContentKind.DIALOGUE || contentKind == OcrContentKind.FIXED_JP_DIALOGUE
        val lineWidth = max(
            distance(box[0], box[1], box[2], box[3]),
            distance(box[6], box[7], box[4], box[5])
        )
        val lineHeight = max(
            distance(box[0], box[1], box[6], box[7]),
            distance(box[2], box[3], box[4], box[5])
        )
        if (tightRecognition.confidence < REC_TEXT_SCORE_THRESHOLD ||
            !PaddleEdgePunctuationMerger.mayHaveRecoverableEdges(
                tightRecognition.text,
                allowShortBody = dialogue || choiceBounds != null
            )
        ) {
            return EdgePunctuationRecovery(tightRecognition)
        }

        if (lineHeight <= 0f || lineWidth < lineHeight * EDGE_MIN_HORIZONTAL_RATIO) {
            return EdgePunctuationRecovery(tightRecognition)
        }

        val padding = max(
            EDGE_MIN_HORIZONTAL_PADDING.toFloat(),
            lineHeight * EDGE_HORIZONTAL_PADDING_HEIGHT_RATIO
        ).coerceAtMost(source.width * EDGE_MAX_PADDING_WIDTH_RATIO)
        if (contentKind == OcrContentKind.CHOICE && choiceBounds == null) return EdgePunctuationRecovery(tightRecognition)
        val expandedBox = expandTextLineHorizontally(box, padding, source.width, source.height).also { expanded ->
            if (choiceBounds != null) for (index in expanded.indices step 2) {
                expanded[index] = expanded[index].coerceIn(choiceBounds.left.toFloat(), (choiceBounds.right - 1).toFloat())
                expanded[index + 1] = expanded[index + 1].coerceIn(choiceBounds.top.toFloat(), (choiceBounds.bottom - 1).toFloat())
            }
        }
        if (!hasEdgePunctuationEvidence(source, box, expandedBox, dialogue, choicePixels)) {
            return EdgePunctuationRecovery(tightRecognition)
        }
        val expandedCrop = cropTextLine(source, expandedBox)
            ?: return EdgePunctuationRecovery(tightRecognition)
        val paddedRecognition = try {
            recognizeCrop(expandedCrop)
        } finally {
            if (!expandedCrop.isRecycled) expandedCrop.recycle()
        }
        val mergedText = PaddleEdgePunctuationMerger.merge(
            tightText = tightRecognition.text,
            paddedText = paddedRecognition.text
        )
        if (paddedRecognition.confidence < EDGE_RECOVERY_TEXT_SCORE_THRESHOLD) {
            if (paddedRecognition.text.hasEdgeQuotationMark()) {
                FgoLogger.debug(
                    tag,
                    "PaddleOCR edge quotation candidate below confidence threshold: " +
                        "confidence=${paddedRecognition.confidence}, padded=${paddedRecognition.text}"
                )
            }
            return EdgePunctuationRecovery(
                recognition = tightRecognition,
                attempted = true
            )
        }

        if (mergedText == tightRecognition.text) {
            val noisyLeadingQuoteCandidate =
                PaddleEdgePunctuationMerger.findNoisyLeadingQuoteCandidate(
                    tightText = tightRecognition.text,
                    paddedText = paddedRecognition.text
                )
            if (noisyLeadingQuoteCandidate != null) {
                FgoLogger.debug(
                    tag,
                    "PaddleOCR noisy leading quote candidate detected: " +
                        "before=${tightRecognition.text}, padded=${paddedRecognition.text}, " +
                        "ignored=${noisyLeadingQuoteCandidate.ignoredNoise}, " +
                        "candidate=${noisyLeadingQuoteCandidate.recoveredText}"
                )
                return EdgePunctuationRecovery(
                    recognition = tightRecognition,
                    noisyLeadingQuoteCandidate = noisyLeadingQuoteCandidate,
                    attempted = true
                )
            }
            if (paddedRecognition.text != tightRecognition.text &&
                paddedRecognition.text.hasEdgeQuotationMark()
            ) {
                FgoLogger.debug(
                    tag,
                    "PaddleOCR edge quotation candidate rejected: " +
                        "before=${tightRecognition.text}, padded=${paddedRecognition.text}"
                )
            }
            return EdgePunctuationRecovery(
                recognition = tightRecognition,
                attempted = true
            )
        }
        FgoLogger.debug(
            tag,
            "PaddleOCR edge punctuation recovered: " +
                "before=${tightRecognition.text}, padded=${paddedRecognition.text}, after=$mergedText"
        )
        return EdgePunctuationRecovery(
            recognition = tightRecognition.copy(text = mergedText),
            attempted = true
        )
    }

    private fun hasEdgePunctuationEvidence(
        source: Bitmap,
        tightBox: FloatArray,
        expandedBox: FloatArray,
        allowStoryColors: Boolean,
        capturedPixels: IntArray? = null
    ): Boolean {
        val tightBounds = boxToRect(tightBox, source.width, source.height)
        val sampleBounds = boxToRect(expandedBox, source.width, source.height)
        if (sampleBounds == tightBounds) return false

        val pixels = IntArray(sampleBounds.width() * sampleBounds.height())
        if (capturedPixels != null) {
            // The choice probe accepts only white/red ink. Normalize the already captured
            // pixels for the neutral probe so dim red quotes work without admitting blue glow.
            for (y in 0 until sampleBounds.height()) for (x in 0 until sampleBounds.width()) {
                val pixel = capturedPixels[(sampleBounds.top + y) * source.width + sampleBounds.left + x]
                pixels[y * sampleBounds.width() + x] = if (FgoStoryTextPalette.isChoiceInk(
                        (pixel shr 16) and 255, (pixel shr 8) and 255, pixel and 255
                    )) 0xffffffff.toInt() else 0xff000000.toInt()
            }
        } else source.getPixels(
            pixels,
            0,
            sampleBounds.width(),
            sampleBounds.left,
            sampleBounds.top,
            sampleBounds.width(),
            sampleBounds.height()
        )
        return PaddleEdgePunctuationProbe.hasEvidence(
            pixels = pixels,
            width = sampleBounds.width(),
            height = sampleBounds.height(),
            textLeft = tightBounds.left - sampleBounds.left,
            textTop = tightBounds.top - sampleBounds.top,
            textRight = tightBounds.right - sampleBounds.left,
            textBottom = tightBounds.bottom - sampleBounds.top,
            allowStoryColors = allowStoryColors
        )
    }

    private fun recoverNoisyLeadingQuoteCandidates(
        detectedTextBoxes: List<PaddleDetectedTextBox>
    ): List<PaddleDetectedTextBox> {
        if (detectedTextBoxes.none { it.noisyLeadingQuoteCandidate != null }) {
            return detectedTextBoxes
        }

        return detectedTextBoxes.mapIndexed { index, detectedBox ->
            val line = detectedBox.line ?: return@mapIndexed detectedBox
            val candidate = detectedBox.noisyLeadingQuoteCandidate ?: return@mapIndexed detectedBox
            val laterTexts = detectedTextBoxes.asSequence()
                .drop(index + 1)
                .mapNotNull { it.line?.text }
                .toList()
            if (!PaddleEdgePunctuationMerger.hasMatchingClosingQuote(candidate, laterTexts)) {
                FgoLogger.debug(
                    tag,
                    "PaddleOCR noisy leading quote candidate rejected: " +
                        "reason=no_matching_closer, before=${line.text}, " +
                        "candidate=${candidate.recoveredText}"
                )
                return@mapIndexed detectedBox.copy(noisyLeadingQuoteCandidate = null)
            }

            FgoLogger.debug(
                tag,
                "PaddleOCR noisy leading quote recovered: " +
                    "ignored=${candidate.ignoredNoise}, before=${line.text}, " +
                    "after=${candidate.recoveredText}"
            )
            detectedBox.copy(
                line = line.copy(text = candidate.recoveredText),
                noisyLeadingQuoteCandidate = null
            )
        }
    }

    private fun recoverDetachedEdgePunctuation(
        lines: List<OcrTextLine>,
        lowConfidenceEdgeFragments: List<OcrTextLine>
    ): List<OcrTextLine> {
        val candidates = lines + lowConfidenceEdgeFragments
        val positioned = candidates.mapIndexed { index, line ->
            PaddleEdgePunctuationMerger.PositionedLine(
                sourceIndex = index,
                text = line.text,
                left = line.boundingBox.left,
                top = line.boundingBox.top,
                right = line.boundingBox.right,
                bottom = line.boundingBox.bottom
            )
        }
        val merged = PaddleEdgePunctuationMerger.mergeDetachedFragments(positioned)
        val retained = merged.filter { it.sourceIndex < lines.size }
        val recovered = retained.map { positionedLine ->
            lines[positionedLine.sourceIndex].copy(text = positionedLine.text)
        }
        if (recovered.size == lines.size &&
            recovered.indices.all { recovered[it].text == lines[it].text }
        ) {
            return lines
        }

        FgoLogger.debug(
            tag,
            "PaddleOCR detached edge punctuation recovered: " +
                "before=${lines.joinToString(" | ") { it.text }}, " +
                "after=${recovered.joinToString(" | ") { it.text }}"
        )
        return recovered
    }

    private fun List<OcrTextLine>.filterWithMatchingQuoteEvidence(
        regularLines: List<OcrTextLine>,
        allowDialoguePauseOrDash: Boolean
    ): List<OcrTextLine> {
        if (isEmpty()) return emptyList()
        val allCandidates = regularLines + this
        return filter { fragment ->
            if (allowDialoguePauseOrDash &&
                PaddleEdgePunctuationMerger.isDialoguePauseOrDashFragment(fragment.text)
            ) return@filter true
            fragment.text.any { quote ->
                val counterpart = EDGE_QUOTE_COUNTERPARTS[quote] ?: return@any false
                allCandidates.any { other ->
                    other !== fragment && other.text.hasQuoteCounterpartAtEdge(quote, counterpart)
                }
            }
        }
    }

    private fun String.hasQuoteCounterpartAtEdge(candidate: Char, counterpart: Char): Boolean {
        val visible = trim()
        if (visible.isEmpty()) return false
        if (candidate == '"') {
            return visible.first() == counterpart || visible.last() == counterpart
        }
        if (candidate in EDGE_OPENING_QUOTATION_SYMBOLS) {
            val counterpartIndex = visible.lastIndexOf(counterpart)
            return counterpartIndex >= 0 && visible.substring(counterpartIndex + 1).all {
                it in EDGE_AFTER_CLOSING_QUOTE_SYMBOLS
            }
        }
        return candidate in EDGE_CLOSING_QUOTATION_SYMBOLS && visible.first() == counterpart
    }

    private fun String.hasEdgeQuotationMark(): Boolean {
        return any { it in EDGE_QUOTATION_SYMBOLS }
    }

    private fun expandTextLineHorizontally(
        box: FloatArray,
        padding: Float,
        sourceWidth: Int,
        sourceHeight: Int
    ): FloatArray {
        if (box.size < 8 || padding <= 0f) return box

        val topLength = distance(box[0], box[1], box[2], box[3]).coerceAtLeast(1f)
        val bottomLength = distance(box[6], box[7], box[4], box[5]).coerceAtLeast(1f)
        val topUnitX = (box[2] - box[0]) / topLength
        val topUnitY = (box[3] - box[1]) / topLength
        val bottomUnitX = (box[4] - box[6]) / bottomLength
        val bottomUnitY = (box[5] - box[7]) / bottomLength
        val maxX = (sourceWidth - 1).coerceAtLeast(0).toFloat()
        val maxY = (sourceHeight - 1).coerceAtLeast(0).toFloat()

        return floatArrayOf(
            (box[0] - topUnitX * padding).coerceIn(0f, maxX),
            (box[1] - topUnitY * padding).coerceIn(0f, maxY),
            (box[2] + topUnitX * padding).coerceIn(0f, maxX),
            (box[3] + topUnitY * padding).coerceIn(0f, maxY),
            (box[4] + bottomUnitX * padding).coerceIn(0f, maxX),
            (box[5] + bottomUnitY * padding).coerceIn(0f, maxY),
            (box[6] - bottomUnitX * padding).coerceIn(0f, maxX),
            (box[7] - bottomUnitY * padding).coerceIn(0f, maxY)
        )
    }

    private fun cropTextLine(source: Bitmap, box: FloatArray): Bitmap? {
        val cropWidth = max(
            distance(box[0], box[1], box[2], box[3]),
            distance(box[6], box[7], box[4], box[5])
        ).roundToInt().coerceIn(1, source.width)
        val cropHeight = max(
            distance(box[0], box[1], box[6], box[7]),
            distance(box[2], box[3], box[4], box[5])
        ).roundToInt().coerceIn(1, source.height)

        val sourcePoints = floatArrayOf(
            box[0], box[1],
            box[2], box[3],
            box[4], box[5],
            box[6], box[7]
        )
        val destinationPoints = floatArrayOf(
            0f, 0f,
            (cropWidth - 1).toFloat(), 0f,
            (cropWidth - 1).toFloat(), (cropHeight - 1).toFloat(),
            0f, (cropHeight - 1).toFloat()
        )
        val matrix = Matrix()
        if (!matrix.setPolyToPoly(sourcePoints, 0, destinationPoints, 0, 4)) {
            return null
        }

        return runCatching {
            val crop = Bitmap.createBitmap(cropWidth, cropHeight, Bitmap.Config.ARGB_8888)
            Canvas(crop).drawBitmap(source, matrix, null)
            if (crop.height >= crop.width * VERTICAL_TEXT_ROTATE_RATIO) {
                val rotated = Bitmap.createBitmap(
                    crop,
                    0,
                    0,
                    crop.width,
                    crop.height,
                    Matrix().apply { setRotate(-90f) },
                    true
                )
                if (rotated !== crop) crop.recycle()
                rotated
            } else {
                crop
            }
        }.getOrNull()
    }

    private fun recoverSplitSolidMaskRows(
        source: Bitmap,
        detectedTextBoxes: List<PaddleDetectedTextBox>,
        maskRows: List<PaddleSolidMaskRow>
    ): List<OcrTextLine> {
        if (maskRows.isEmpty()) return detectedTextBoxes.mapNotNull(PaddleDetectedTextBox::line)

        val consumedBoxIndexes = mutableSetOf<Int>()
        val recoveredLines = mutableListOf<OcrTextLine>()

        for (row in maskRows) {
            val associatedBoxes = detectedTextBoxes.withIndex()
                .filter { (index, detectedBox) ->
                    index !in consumedBoxIndexes && boxBelongsToMaskRow(detectedBox.bounds, row)
                }
            if (associatedBoxes.isEmpty()) {
                FgoLogger.debug(
                    tag,
                    "PaddleOCR solid-mask row not attached to a text box: " +
                        "count=${row.masks.size}, bounds=${row.left},${row.top}-${row.right},${row.bottom}"
                )
                continue
            }

            val alreadyRecovered = associatedBoxes.size == 1 && associatedBoxes.single().value.let { detectedBox ->
                detectedBox.recoveredMaskCount >= row.masks.size ||
                    (detectedBox.line?.text?.count { it == SOLID_MASK_CHAR } ?: 0) >= row.masks.size
            }
            if (alreadyRecovered) continue

            val recoveryBounds = recoveryBoundsFor(
                row = row,
                boxes = associatedBoxes.map { it.value.bounds },
                sourceWidth = source.width,
                sourceHeight = source.height
            )
            val crop = runCatching {
                Bitmap.createBitmap(
                    source,
                    recoveryBounds.left,
                    recoveryBounds.top,
                    recoveryBounds.width(),
                    recoveryBounds.height()
                )
            }.getOrNull() ?: continue

            val cropMasks = row.masks.map { mask ->
                PaddleSolidMask(
                    left = mask.left - recoveryBounds.left,
                    top = mask.top - recoveryBounds.top,
                    right = mask.right - recoveryBounds.left,
                    bottom = mask.bottom - recoveryBounds.top,
                    confidence = mask.confidence
                )
            }
            val cropSeparators = row.separators.map { separator ->
                PaddleSolidMaskSeparator(
                    left = separator.left - recoveryBounds.left,
                    top = separator.top - recoveryBounds.top,
                    right = separator.right - recoveryBounds.left,
                    bottom = separator.bottom - recoveryBounds.top,
                    confidence = separator.confidence
                )
            }
            val recognition = try {
                recognizeCrop(
                    crop = crop,
                    knownMasks = cropMasks,
                    knownSeparators = cropSeparators
                )
            } finally {
                crop.recycle()
            }

            val previousText = associatedBoxes
                .mapNotNull { it.value.line?.text }
                .joinToString(separator = "")
            val previousMeaningfulChars = previousText.count(Char::isLetterOrDigit)
            val recoveredNonMaskChars = recognition.text.count {
                !it.isWhitespace() && it != SOLID_MASK_CHAR
            }
            val recoveredMeaningfulChars = recognition.text.count(Char::isLetterOrDigit)
            val keepsRecognizedText = previousMeaningfulChars == 0 ||
                recoveredMeaningfulChars >= max(1, previousMeaningfulChars / 2)
            val accepted = recognition.recoveredMaskCount == row.masks.size &&
                recognition.confidence >= REC_TEXT_SCORE_THRESHOLD &&
                recoveredNonMaskChars > 0 &&
                keepsRecognizedText

            if (!accepted) {
                FgoLogger.debug(
                    tag,
                    "PaddleOCR split solid-mask recovery rejected: " +
                        "expected=${row.masks.size}, recovered=${recognition.recoveredMaskCount}, " +
                        "before=$previousText, after=${recognition.text}"
                )
                continue
            }

            associatedBoxes.forEach { consumedBoxIndexes += it.index }
            recoveredLines += OcrTextLine(
                text = recognition.text,
                boundingBox = recoveryBounds,
                confidence = recognition.confidence.coerceIn(0f, 1f)
            )
            FgoLogger.debug(
                tag,
                "PaddleOCR split solid-mask line recovered: " +
                    "count=${row.masks.size}, boxes=${associatedBoxes.size}, " +
                    "before=$previousText, after=${recognition.text}"
            )
        }

        return buildList {
            detectedTextBoxes.forEachIndexed { index, detectedBox ->
                if (index !in consumedBoxIndexes) detectedBox.line?.let(::add)
            }
            addAll(recoveredLines)
        }
    }

    private fun boxBelongsToMaskRow(bounds: Rect, row: PaddleSolidMaskRow): Boolean {
        val verticalOverlap = min(bounds.bottom, row.bottom) - max(bounds.top, row.top)
        val minimumHeight = min(bounds.height(), row.height).coerceAtLeast(1)
        if (verticalOverlap < minimumHeight * MASK_ROW_MIN_VERTICAL_OVERLAP_RATIO) return false
        val boxCenterY = (bounds.top + bounds.bottom) / 2f
        val maximumCenterDifference = max(bounds.height(), row.height) * MASK_ROW_MAX_CENTER_DIFFERENCE_RATIO
        if (abs(boxCenterY - row.centerY) > maximumCenterDifference) return false

        val horizontalGap = when {
            bounds.right < row.left -> row.left - bounds.right
            bounds.left > row.right -> bounds.left - row.right
            else -> 0
        }
        val maximumGap = max(
            row.averageSide * MASK_ROW_MAX_HORIZONTAL_GAP_SIDE_RATIO,
            bounds.height() * MASK_ROW_MAX_HORIZONTAL_GAP_HEIGHT_RATIO
        )
        return horizontalGap <= maximumGap
    }

    private fun recoveryBoundsFor(
        row: PaddleSolidMaskRow,
        boxes: List<Rect>,
        sourceWidth: Int,
        sourceHeight: Int
    ): Rect {
        val bounds = Rect(row.left, row.top, row.right, row.bottom)
        boxes.forEach(bounds::union)
        val padding = max(MASK_ROW_MIN_CROP_PADDING, (row.averageSide * MASK_ROW_CROP_PADDING_RATIO).roundToInt())
        bounds.inset(-padding, -padding)
        bounds.left = bounds.left.coerceIn(0, sourceWidth - 1)
        bounds.top = bounds.top.coerceIn(0, sourceHeight - 1)
        bounds.right = bounds.right.coerceIn(bounds.left + 1, sourceWidth)
        bounds.bottom = bounds.bottom.coerceIn(bounds.top + 1, sourceHeight)
        return bounds
    }

    private fun recognitionInputShape(session: OrtSession?): LongArray {
        if (session == null) return longArrayOf()
        val inputName = session.inputNames.firstOrNull() ?: return longArrayOf()
        val tensorInfo = session.inputInfo[inputName]?.info as? TensorInfo
            ?: return longArrayOf()
        return tensorInfo.shape
    }

    private fun supportsDynamicRecognitionBatch(shape: LongArray): Boolean {
        if (shape.size != 4) return false
        val batch = shape[0]
        val channels = shape[1]
        val height = shape[2]
        val width = shape[3]
        return batch <= 0L &&
            (channels <= 0L || channels == 3L) &&
            (height <= 0L || height == REC_IMAGE_HEIGHT.toLong()) &&
            width <= 0L
    }

    private fun prepareRecognitionCrop(
        crop: Bitmap,
        knownMasks: List<PaddleSolidMask>? = null,
        knownSeparators: List<PaddleSolidMaskSeparator> = emptyList()
    ): PreparedRecognitionCrop {
        val resizedWidth = max(1, ceil(REC_IMAGE_HEIGHT.toDouble() * crop.width / crop.height).toInt())
            .coerceAtMost(REC_MAX_IMAGE_WIDTH)
        val resized = Bitmap.createScaledBitmap(crop, resizedWidth, REC_IMAGE_HEIGHT, true)
        val pixels = IntArray(resizedWidth * REC_IMAGE_HEIGHT)
        resized.getPixels(pixels, 0, resizedWidth, 0, 0, resizedWidth, REC_IMAGE_HEIGHT)
        if (resized !== crop) resized.recycle()
        val solidMasks = knownMasks?.map { mask ->
            mask.scaled(
                scaleX = resizedWidth.toFloat() / crop.width.toFloat(),
                scaleY = REC_IMAGE_HEIGHT.toFloat() / crop.height.toFloat()
            )
        } ?: PaddleSolidMaskDetector.detect(
            pixels = pixels,
            width = resizedWidth,
            height = REC_IMAGE_HEIGHT
        )
        val solidMaskSeparators = knownSeparators.map { separator ->
            separator.scaled(
                scaleX = resizedWidth.toFloat() / crop.width.toFloat(),
                scaleY = REC_IMAGE_HEIGHT.toFloat() / crop.height.toFloat()
            )
        }
        return PreparedRecognitionCrop(
            resizedWidth = resizedWidth,
            pixels = pixels,
            solidMasks = solidMasks,
            solidMaskSeparators = solidMaskSeparators
        )
    }

    private fun recognizePreparedTargets(
        targets: List<PaddleRecognitionTarget>,
        collectPositions: Boolean = false
    ): Map<Int, PaddleMaskMergeResult> {
        val preparedTargets = targets
            .mapNotNull { target ->
                target.prepared?.let { prepared ->
                    PreparedRecognitionTarget(target.index, prepared)
                }
            }
            .sortedBy { it.prepared.resizedWidth }
        if (preparedTargets.isEmpty()) return emptyMap()

        val startedAt = System.currentTimeMillis()
        val recognitions = mutableMapOf<Int, PaddleMaskMergeResult>()
        var cursor = 0
        var modelRuns = 0
        var successfulBatchRuns = 0
        var fellBackToSingle = false

        while (cursor < preparedTargets.size) {
            val requestedSize = if (recognitionBatchEnabled) {
                recognitionBatchSize(preparedTargets, cursor)
            } else {
                1
            }
            val batch = preparedTargets.subList(cursor, cursor + requestedSize)
            val preparedBatch = batch.map(PreparedRecognitionTarget::prepared)
            val batchResults = if (preparedBatch.size > 1) {
                try {
                    modelRuns++
                    runRecognitionBatch(preparedBatch, collectPositions).also { successfulBatchRuns++ }
                } catch (error: Exception) {
                    recognitionBatchEnabled = false
                    fellBackToSingle = true
                    FgoLogger.warn(
                        tag,
                        "PaddleOCR batched recognition failed; using single-crop recognition for this session",
                        error
                    )
                    preparedBatch.map { prepared ->
                        modelRuns++
                        runRecognitionBatch(listOf(prepared), collectPositions).single()
                    }
                }
            } else {
                modelRuns++
                listOf(runRecognitionBatch(preparedBatch, collectPositions).single())
            }
            check(batchResults.size == batch.size) {
                "PaddleOCR recognition result count mismatch: expected=${batch.size}, actual=${batchResults.size}"
            }
            batch.forEachIndexed { index, target ->
                recognitions[target.index] = batchResults[index]
            }
            cursor += batch.size
        }

        FgoLogger.debug(
            tag,
            "PaddleOCR base recognition: boxes=${preparedTargets.size}, " +
                "modelRuns=$modelRuns, batchRuns=$successfulBatchRuns, " +
                "batchEnabled=$recognitionBatchEnabled, fallback=$fellBackToSingle, " +
                "elapsed=${System.currentTimeMillis() - startedAt}ms"
        )
        return recognitions
    }

    private fun recognitionBatchSize(
        sortedTargets: List<PreparedRecognitionTarget>,
        startIndex: Int
    ): Int {
        // Paddle pads every sample to the widest row in the batch. Keep short names
        // away from very long dialogue rows and cap the large [N, T, classes] output.
        val firstWidth = sortedTargets[startIndex].prepared.resizedWidth
        var size = 1
        while (size < REC_MAX_BATCH_SIZE && startIndex + size < sortedTargets.size) {
            val nextWidth = sortedTargets[startIndex + size].prepared.resizedWidth
            if (nextWidth > firstWidth * REC_MAX_BATCH_WIDTH_RATIO) break
            val candidateSize = size + 1
            if (estimatedRecognitionOutputBytes(candidateSize, nextWidth) > REC_MAX_BATCH_OUTPUT_BYTES) break
            size++
        }
        return size
    }

    private fun estimatedRecognitionOutputBytes(batchSize: Int, width: Int): Long {
        val estimatedSequenceLength = (width + REC_SEQUENCE_WIDTH_STRIDE - 1) / REC_SEQUENCE_WIDTH_STRIDE
        return batchSize.toLong() *
            estimatedSequenceLength.toLong() *
            dictionary.size.coerceAtLeast(1).toLong() *
            FLOAT_BYTES
    }

    private fun recognizeCrop(
        crop: Bitmap,
        knownMasks: List<PaddleSolidMask>? = null,
        knownSeparators: List<PaddleSolidMaskSeparator> = emptyList(),
        collectPositions: Boolean = false
    ): PaddleMaskMergeResult {
        val prepared = prepareRecognitionCrop(crop, knownMasks, knownSeparators)
        return runRecognitionBatch(listOf(prepared), collectPositions).single()
    }

    private fun runRecognitionBatch(
        preparedBatch: List<PreparedRecognitionCrop>,
        collectPositions: Boolean = false
    ): List<PaddleMaskMergeResult> {
        require(preparedBatch.isNotEmpty()) { "PaddleOCR recognition batch must not be empty" }
        val batchSize = preparedBatch.size
        val batchWidth = preparedBatch.maxOf(PreparedRecognitionCrop::resizedWidth)
        val planeSize = REC_IMAGE_HEIGHT * batchWidth
        val sampleSize = planeSize * 3
        val input = FloatArray(batchSize * sampleSize)
        preparedBatch.forEachIndexed { sampleIndex, prepared ->
            val sampleOffset = sampleIndex * sampleSize
            for (y in 0 until REC_IMAGE_HEIGHT) {
                val sourceRow = y * prepared.resizedWidth
                val targetRow = y * batchWidth
                for (x in 0 until prepared.resizedWidth) {
                    val pixel = prepared.pixels[sourceRow + x]
                    val target = sampleOffset + targetRow + x
                    input[target] = ((pixel and 0xff) / 255f - 0.5f) / 0.5f
                    input[target + planeSize] = (((pixel shr 8) and 0xff) / 255f - 0.5f) / 0.5f
                    input[target + planeSize * 2] = (((pixel shr 16) and 0xff) / 255f - 0.5f) / 0.5f
                }
            }
        }

        val env = environment ?: error("PaddleOCR environment is not initialized")
        val session = recSession ?: error("PaddleOCR recognition session is not initialized")
        val inputName = session.inputNames.first()
        val tensor = OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(input),
            longArrayOf(batchSize.toLong(), 3, REC_IMAGE_HEIGHT.toLong(), batchWidth.toLong())
        )
        return tensor.use { inputTensor ->
            session.run(mapOf(inputName to inputTensor)).use { result ->
                val output = firstTensor(result, session)
                val shape = output.info.shape
                require(shape.size >= 2) {
                    "Unexpected PaddleOCR recognition output shape: ${shape.contentToString()}"
                }
                val sequenceLength = shape[shape.size - 2].toInt()
                val classCount = shape[shape.size - 1].toInt()
                require(sequenceLength > 0 && classCount > 0) {
                    "Invalid PaddleOCR recognition output shape: ${shape.contentToString()}"
                }
                val outputBatchSize = shape
                    .dropLast(2)
                    .fold(1L) { count, dimension ->
                        require(dimension > 0L) {
                            "Unresolved PaddleOCR recognition output shape: ${shape.contentToString()}"
                        }
                        count * dimension
                    }
                    .toInt()
                require(outputBatchSize == batchSize) {
                    "PaddleOCR recognition batch mismatch: input=$batchSize, output=$outputBatchSize, " +
                        "shape=${shape.contentToString()}"
                }
                val sampleOutputSize = sequenceLength * classCount
                val sampleValues = FloatArray(sampleOutputSize)
                val outputBuffer = output.floatBuffer.apply { rewind() }
                preparedBatch.map { prepared ->
                    outputBuffer.get(sampleValues, 0, sampleValues.size)
                    val tokens = ctcDecode(
                        values = sampleValues,
                        sequenceLength = sequenceLength,
                        classCount = classCount,
                        imageWidth = batchWidth
                    )
                    PaddleSolidMaskMerger.merge(
                        tokens,
                        prepared.solidMasks,
                        prepared.solidMaskSeparators
                    ).also { merged ->
                        if (merged.recoveredMaskCount > 0) {
                            val originalText = tokens.joinToString(separator = "") { it.text }.trim()
                            FgoLogger.debug(
                                tag,
                                "PaddleOCR solid masks recovered: " +
                                    "count=${merged.recoveredMaskCount}, " +
                                    "before=$originalText, after=${merged.text.trim()}"
                            )
                        }
                    }.let { merged ->
                        val text = merged.text.trim()
                        val originalText = tokens.joinToString("") { it.text }
                        val positioned = if (collectPositions && merged.recoveredMaskCount == 0 &&
                            text == originalText.trim()
                        ) {
                            tokens.dropWhile { it.text.isBlank() }.dropLastWhile { it.text.isBlank() }
                                .map { it.copy(centerX = it.centerX / prepared.resizedWidth) }
                        } else emptyList()
                        merged.copy(text = text, positionedTokens = positioned)
                    }
                }
            }
        }
    }

    private fun ctcDecode(
        values: FloatArray,
        sequenceLength: Int,
        classCount: Int,
        imageWidth: Int
    ): List<PaddlePositionedToken> {
        val tokens = mutableListOf<PaddlePositionedToken>()
        var previousIndex = -1

        for (step in 0 until sequenceLength) {
            val offset = step * classCount
            var bestIndex = 0
            var bestValue = values[offset]
            for (classIndex in 1 until classCount) {
                val value = values[offset + classIndex]
                if (value > bestValue) {
                    bestValue = value
                    bestIndex = classIndex
                }
            }
            if (bestIndex != 0 && bestIndex != previousIndex && bestIndex < dictionary.size) {
                tokens += PaddlePositionedToken(
                    text = dictionary[bestIndex],
                    centerX = (step + 0.5f) * imageWidth.toFloat() / sequenceLength.toFloat(),
                    confidence = bestValue.coerceIn(0f, 1f)
                )
            }
            previousIndex = bestIndex
        }
        return tokens
    }

    private fun firstTensor(result: OrtSession.Result, session: OrtSession): OnnxTensor {
        for (name in session.outputNames) {
            val value = result.get(name)
            if (value.isPresent && value.get() is OnnxTensor) {
                return value.get() as OnnxTensor
            }
        }
        error("ONNX session returned no tensor output")
    }

    private fun alignTo32(value: Int): Int {
        return max(32, ((value + 31) / 32) * 32)
    }

    private fun distance(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x2 - x1
        val dy = y2 - y1
        return sqrt(dx * dx + dy * dy)
    }

    private fun boxMinX(box: FloatArray): Float = minOf(box[0], box[2], box[4], box[6])
    private fun boxMinY(box: FloatArray): Float = minOf(box[1], box[3], box[5], box[7])

    private fun boxToRect(box: FloatArray, width: Int, height: Int): Rect {
        val left = boxMinX(box).roundToInt().coerceIn(0, width - 1)
        val top = boxMinY(box).roundToInt().coerceIn(0, height - 1)
        val right = maxOf(box[0], box[2], box[4], box[6]).roundToInt().coerceIn(left + 1, width)
        val bottom = maxOf(box[1], box[3], box[5], box[7]).roundToInt().coerceIn(top + 1, height)
        return Rect(left, top, right, bottom)
    }

    private data class MiniBox(
        val points: Array<Coordinate>,
        val minSide: Float
    )

    private data class PaddleDetectionResult(
        val boxes: List<FloatArray>,
        val solidMaskRows: List<PaddleSolidMaskRow>
    )

    private data class PaddleRecognitionTarget(
        val index: Int,
        val box: FloatArray,
        val bounds: Rect,
        val prepared: PreparedRecognitionCrop?,
        val visualDash: Boolean
    )

    private data class PreparedRecognitionCrop(
        val resizedWidth: Int,
        val pixels: IntArray,
        val solidMasks: List<PaddleSolidMask>,
        val solidMaskSeparators: List<PaddleSolidMaskSeparator>
    )

    private data class PreparedRecognitionTarget(
        val index: Int,
        val prepared: PreparedRecognitionCrop
    )

    private data class PaddleDetectedTextBox(
        val bounds: Rect,
        val line: OcrTextLine? = null,
        val recoveredMaskCount: Int = 0,
        val lowConfidenceEdgeFragment: OcrTextLine? = null,
        val noisyLeadingQuoteCandidate: PaddleEdgePunctuationMerger.NoisyLeadingQuoteCandidate? = null
    )

    private data class EdgePunctuationRecovery(
        val recognition: PaddleMaskMergeResult,
        val noisyLeadingQuoteCandidate: PaddleEdgePunctuationMerger.NoisyLeadingQuoteCandidate? = null,
        val attempted: Boolean = false
    )

    companion object {
        private const val DET_MODEL_ASSET = "ppocrv6/det_v6_small.onnx"
        private const val REC_MODEL_ASSET = "ppocrv6/rec_v6_small.onnx"
        private const val DICT_ASSET = "ppocrv6/ppocrv6_dict.txt"

        private const val DET_LIMIT_SIDE_LEN = 960
        private val DET_MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
        private val DET_STD = floatArrayOf(0.229f, 0.224f, 0.225f)
        private const val DET_THRESHOLD = 0.2f
        private const val DET_BOX_SCORE_THRESHOLD = 0.45f
        private const val DET_UNCLIP_RATIO = 1.4
        private const val DET_MIN_COMPONENT_PIXELS = 3
        private const val DET_MIN_BOX_SIDE = 3f
        private const val DET_MAX_CANDIDATES = 1000

        private const val REC_IMAGE_HEIGHT = 48
        private const val REC_MAX_IMAGE_WIDTH = 3200
        private const val REC_MAX_BATCH_SIZE = 4
        private const val REC_MAX_BATCH_WIDTH_RATIO = 2f
        private const val REC_SEQUENCE_WIDTH_STRIDE = 8
        private const val REC_MAX_BATCH_OUTPUT_BYTES = 40L * 1024L * 1024L
        private const val FLOAT_BYTES = 4L
        private const val REC_TEXT_SCORE_THRESHOLD = 0.5f
        private const val EDGE_RECOVERY_TEXT_SCORE_THRESHOLD = 0.35f
        private const val VERTICAL_TEXT_ROTATE_RATIO = 1.5f
        private const val EDGE_MIN_HORIZONTAL_RATIO = 0.67f
        private const val EDGE_HORIZONTAL_PADDING_HEIGHT_RATIO = 5f
        private const val EDGE_MAX_PADDING_WIDTH_RATIO = 0.25f
        private const val EDGE_MIN_HORIZONTAL_PADDING = 12
        private const val DIALOGUE_EDGE_RECOVERY_MAX_PASSES = 4
        private const val CHOICE_EDGE_RECOVERY_MAX_PASSES = 2
        private const val DIALOGUE_DASH_MIN_WIDTH = 40
        private const val DIALOGUE_DASH_MIN_WIDTH_RATIO = 0.03f
        private const val DIALOGUE_DASH_MAX_HEIGHT = 14
        private const val DIALOGUE_DASH_MAX_HEIGHT_RATIO = 0.10f
        private const val DIALOGUE_DASH_MIN_VISUAL_ASPECT_RATIO = 5f
        private const val DIALOGUE_DASH_MIN_RUN = 30
        private const val DIALOGUE_DASH_MIN_RUN_RATIO = 0.55f
        private const val DIALOGUE_DASH_MAX_STROKE_HEIGHT = 8
        private const val DIALOGUE_DASH_MAX_STROKE_HEIGHT_RATIO = 0.75f
        private val EDGE_QUOTATION_SYMBOLS = setOf('「', '」', '『', '』', '“', '”', '"')
        private val EDGE_OPENING_QUOTATION_SYMBOLS = setOf('「', '『', '“')
        private val EDGE_CLOSING_QUOTATION_SYMBOLS = setOf('」', '』', '”')
        private val EDGE_AFTER_CLOSING_QUOTE_SYMBOLS = setOf(
            '」', '』', '”', '"', '。', '.', '．', '!', '！', '?', '？',
            ',', '，', '、', '…', '‥', '⋯', '—', '―', '─', '━', '－', '-'
        )
        private val EDGE_QUOTE_COUNTERPARTS = mapOf(
            '「' to '」',
            '」' to '「',
            '『' to '』',
            '』' to '『',
            '“' to '”',
            '”' to '“',
            '"' to '"'
        )
        private const val SOLID_MASK_CHAR = '■'
        private const val MASK_ROW_MIN_VERTICAL_OVERLAP_RATIO = 0.45f
        private const val MASK_ROW_MAX_CENTER_DIFFERENCE_RATIO = 0.45f
        private const val MASK_ROW_MAX_HORIZONTAL_GAP_SIDE_RATIO = 2.5f
        private const val MASK_ROW_MAX_HORIZONTAL_GAP_HEIGHT_RATIO = 2f
        private const val MASK_ROW_CROP_PADDING_RATIO = 0.18f
        private const val MASK_ROW_MIN_CROP_PADDING = 2
        private val GEOMETRY_FACTORY = GeometryFactory()
    }
}
